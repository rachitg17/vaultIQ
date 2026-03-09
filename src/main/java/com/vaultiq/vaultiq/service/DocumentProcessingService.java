package com.vaultiq.vaultiq.service;

import com.vaultiq.vaultiq.entity.Document;
import com.vaultiq.vaultiq.entity.DocumentChunk;
import com.vaultiq.vaultiq.repository.DocumentChunkRepository;
import com.vaultiq.vaultiq.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentProcessingService {

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final GeminiService geminiService;

    private static final int CHUNK_SIZE = 500;

    public Document processDocument(MultipartFile file, String uploadedBy, String context) throws Exception {
        log.info("Processing document: {}", file.getOriginalFilename());

        // Save file to disk FIRST — avoids multipart stream being consumed
        String uploadDir = System.getProperty("user.home") + "/vaultiq-uploads/";
        new java.io.File(uploadDir).mkdirs();
        String tempName = System.currentTimeMillis() + "_" + file.getOriginalFilename();
        java.io.File savedFile = new java.io.File(uploadDir + tempName);
        file.transferTo(savedFile);

        // Step 1: Extract text via Groq vision → PDFBox → Tika fallback chain
        String extractedText = extractTextFromFile(savedFile);
        log.info("Extracted {} characters from {}", extractedText.length(), file.getOriginalFilename());

        // Prepend user context if provided
        String textForAI = (context != null && !context.isBlank())
                ? "USER CONTEXT: " + context + "\n\n---\n\n" + extractedText
                : extractedText;

        // Step 2: AI summarization
        String aiAnalysis = geminiService.summarizeDocument(textForAI);
        String summary = parseField(aiAnalysis, "SUMMARY");
        String fileType = parseField(aiAnalysis, "TYPE");
        String entities = parseField(aiAnalysis, "ENTITIES");
        String tags = parseField(aiAnalysis, "TAGS");

        // Step 3: Save document to DB
        Document document = Document.builder()
                .fileName(file.getOriginalFilename())
                .fileType(fileType != null ? fileType : "unknown")
                .extractedText(textForAI)
                .summary(summary)
                .uploadedBy(uploadedBy)
                .uploadedAt(LocalDateTime.now())
                .fileSize(file.getSize())
                .keyEntities(entities)
                .tags(tags)
                .build();

        document = documentRepository.save(document);
        log.info("Saved document with id: {}", document.getId());

        // Step 4: Chunk and save
        List<DocumentChunk> chunks = chunkDocument(textForAI, document);
        documentChunkRepository.saveAll(chunks);

        // Step 5: Update pages + rename file to include document ID
        document.setTotalPages(estimatePages(extractedText));
        java.io.File renamedFile = new java.io.File(uploadDir + document.getId() + "_" + file.getOriginalFilename());
        savedFile.renameTo(renamedFile);
        document.setFilePath(renamedFile.getAbsolutePath());
        documentRepository.save(document);

        log.info("Created {} chunks for document: {}", chunks.size(), document.getFileName());
        return document;
    }

    public ResponseEntity<byte[]> getDocumentFile(String id) {
        try {
            Document doc = documentRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Document not found"));

            java.nio.file.Path filePath = java.nio.file.Paths.get(doc.getFilePath());
            byte[] fileBytes = java.nio.file.Files.readAllBytes(filePath);

            return ResponseEntity.ok()
                    .header("Content-Type", "application/pdf")
                    .header("Content-Disposition", "inline; filename=\"" + doc.getFileName() + "\"")
                    .header("Access-Control-Allow-Origin", "*")
                    .body(fileBytes);
        } catch (Exception e) {
            log.error("Failed to retrieve file for doc {}: {}", id, e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    private String extractTextFromFile(java.io.File file) throws Exception {
        if (file.getName().toLowerCase().endsWith(".pdf")) {

            // Primary — Groq vision (best for tables, forms, designed PDFs)
            try {
                org.apache.pdfbox.pdmodel.PDDocument pdDoc =
                        org.apache.pdfbox.pdmodel.PDDocument.load(file);
                org.apache.pdfbox.rendering.PDFRenderer renderer =
                        new org.apache.pdfbox.rendering.PDFRenderer(pdDoc);

                StringBuilder fullText = new StringBuilder();
                int totalPages = pdDoc.getNumberOfPages();

                for (int page = 0; page < totalPages; page++) {
                    java.awt.image.BufferedImage pageImage =
                            renderer.renderImageWithDPI(page, 200);
                    log.info("Sending page {} of {} to Groq vision", page + 1, totalPages);
                    String pageText = geminiService.extractTextFromPageImage(pageImage);
                    fullText.append("--- Page ").append(page + 1).append(" ---\n");
                    fullText.append(pageText).append("\n\n");
                }

                pdDoc.close();
                String result = fullText.toString().trim();
                log.info("Groq vision extracted {} chars from {} pages", result.length(), totalPages);
                if (result.length() > 100) return result;

            } catch (Exception e) {
                log.warn("Groq vision failed, falling back to PDFBox text stripper: {}", e.getMessage());
            }

            // Fallback 1 — PDFBox text stripper (good for native text PDFs)
            try {
                org.apache.pdfbox.pdmodel.PDDocument pdDoc =
                        org.apache.pdfbox.pdmodel.PDDocument.load(file);
                org.apache.pdfbox.text.PDFTextStripper stripper =
                        new org.apache.pdfbox.text.PDFTextStripper();
                stripper.setSortByPosition(true);
                String text = stripper.getText(pdDoc);
                pdDoc.close();
                if (text.trim().length() > 100) {
                    log.info("PDFBox text stripper extracted {} chars", text.length());
                    return text;
                }
            } catch (Exception e) {
                log.warn("PDFBox fallback failed, falling back to Tika: {}", e.getMessage());
            }
        }

        // Fallback 2 — Tika (handles non-PDF and edge cases)
        try (InputStream inputStream = new java.io.FileInputStream(file)) {
            org.apache.tika.metadata.Metadata metadata = new org.apache.tika.metadata.Metadata();
            org.apache.tika.parser.AutoDetectParser parser =
                    new org.apache.tika.parser.AutoDetectParser();
            org.apache.tika.sax.BodyContentHandler handler =
                    new org.apache.tika.sax.BodyContentHandler(-1);
            parser.parse(inputStream, handler, metadata);
            String text = handler.toString();
            log.info("Tika extracted {} chars", text.length());
            return text;
        }
    }

    private List<DocumentChunk> chunkDocument(String text, Document document) {
        List<DocumentChunk> chunks = new ArrayList<>();
        String[] words = text.split("\\s+");
        int chunkIndex = 0;

        for (int i = 0; i < words.length; i += CHUNK_SIZE) {
            int end = Math.min(i + CHUNK_SIZE, words.length);
            StringBuilder chunkText = new StringBuilder();
            for (int j = i; j < end; j++) {
                chunkText.append(words[j]).append(" ");
            }

            int pageNumber = (i / 300) + 1;
            int paragraphNumber = (i / 100) + 1;

            chunks.add(DocumentChunk.builder()
                    .document(document)
                    .chunkText(chunkText.toString().trim())
                    .chunkIndex(chunkIndex++)
                    .pageNumber(pageNumber)
                    .paragraphNumber(paragraphNumber)
                    .build());
        }
        return chunks;
    }

    private String parseField(String aiResponse, String field) {
        try {
            if (aiResponse == null || aiResponse.isEmpty()) return null;
            String[] lines = aiResponse.split("\n");
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.toLowerCase().startsWith(field.toLowerCase() + ":")) {
                    String value = trimmed.substring(field.length() + 1).trim();
                    if (!value.isEmpty()) return value;
                }
            }
            if (field.equals("SUMMARY")) {
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (trimmed.length() > 30 && !trimmed.contains(":")) {
                        return trimmed;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not parse field {} from AI response", field);
        }
        return null;
    }

    private int estimatePages(String text) {
        return Math.max(1, text.split("\\s+").length / 300);
    }

    public int getChunkCount(String documentId) {
        return documentChunkRepository.findByDocumentIdOrderByChunkIndex(documentId).size();
    }

    public List<Document> getDocumentsByUser(String uploadedBy) {
        return documentRepository.findByUploadedBy(uploadedBy);
    }

    public List<Document> getAllDocuments() {
        return documentRepository.findAll();
    }

    public java.util.Optional<Document> getDocumentById(String id) {
        return documentRepository.findById(id);
    }

    public void deleteDocument(String id) {
        documentChunkRepository.deleteAll(
                documentChunkRepository.findByDocumentIdOrderByChunkIndex(id));
        documentRepository.deleteById(id);
    }
}