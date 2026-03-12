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
    private final SupabaseStorageService storageService;

    private static final int CHUNK_SIZE = 500;

    public Document processDocument(MultipartFile file, String uploadedBy, String context) throws Exception {
        log.info("Processing document: {}", file.getOriginalFilename());

        // Read bytes immediately before stream is consumed
        byte[] fileBytes = file.getBytes();

        // Write to temp file for PDFBox/Tika processing
        String tempDir = System.getProperty("java.io.tmpdir") + "/vaultiq/";
        new java.io.File(tempDir).mkdirs();
        String tempName = System.currentTimeMillis() + "_" + file.getOriginalFilename();
        java.io.File tempFile = new java.io.File(tempDir + tempName);
        java.nio.file.Files.write(tempFile.toPath(), fileBytes);

        // Step 1: Extract text
        String extractedText = extractTextFromFile(tempFile);
        log.info("Extracted {} characters from {}", extractedText.length(), file.getOriginalFilename());

        // Clean up temp file
        tempFile.delete();

        // Prepend user context if provided
        String textForAI = (context != null && !context.isBlank())
                ? "USER CONTEXT: " + context + "\n\n---\n\n" + extractedText
                : extractedText;

        // Step 2: AI summarization
        String aiAnalysis = geminiService.summarizeDocument(textForAI);
        String summary   = parseField(aiAnalysis, "SUMMARY");
        String fileType  = parseField(aiAnalysis, "TYPE");
        String entities  = parseField(aiAnalysis, "ENTITIES");
        String tags      = parseField(aiAnalysis, "TAGS");

        // Step 3: Save document record first to get the UUID
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
                .totalPages(estimatePages(extractedText))
                .build();

        document = documentRepository.save(document);
        log.info("Saved document record with id: {}", document.getId());

        // Step 4: Upload to Supabase Storage using document ID as key
        String storageKey = "documents/" + document.getId() + "_" + file.getOriginalFilename();
        storageService.upload(storageKey, fileBytes, "application/pdf");
        document.setStorageKey(storageKey);
        documentRepository.save(document);
        log.info("Uploaded to Supabase Storage: {}", storageKey);

        // Step 5: Chunk and save
        List<DocumentChunk> chunks = chunkDocument(textForAI, document);
        documentChunkRepository.saveAll(chunks);
        log.info("Created {} chunks for document: {}", chunks.size(), document.getFileName());

        return document;
    }

    public ResponseEntity<byte[]> getDocumentFile(String id) {
        try {
            Document doc = documentRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Document not found: " + id));

            String storageKey = doc.getStorageKey();

            // Fallback: try legacy filePath from disk (won't work on Render but safe locally)
            if (storageKey == null || storageKey.isBlank()) {
                log.warn("No storage key for document {}, trying legacy filePath", id);
                if (doc.getFilePath() != null) {
                    byte[] fileBytes = java.nio.file.Files.readAllBytes(
                            java.nio.file.Paths.get(doc.getFilePath()));
                    return buildPdfResponse(fileBytes, doc.getFileName());
                }
                return ResponseEntity.notFound().build();
            }

            byte[] fileBytes = storageService.download(storageKey);
            return buildPdfResponse(fileBytes, doc.getFileName());

        } catch (Exception e) {
            log.error("Failed to retrieve file for doc {}: {}", id, e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    private ResponseEntity<byte[]> buildPdfResponse(byte[] fileBytes, String fileName) {
        return ResponseEntity.ok()
                .header("Content-Type", "application/pdf")
                .header("Content-Disposition", "inline; filename=\"" + fileName + "\"")
                .header("Content-Length", String.valueOf(fileBytes.length))
                .header("X-Frame-Options", "SAMEORIGIN")
                .body(fileBytes);
    }

    private String extractTextFromFile(java.io.File file) throws Exception {
        if (file.getName().toLowerCase().endsWith(".pdf")) {

            // Primary — Groq vision
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
                if (result.length() > 100) return result;

            } catch (Exception e) {
                log.warn("Groq vision failed, falling back to PDFBox: {}", e.getMessage());
            }

            // Fallback 1 — PDFBox text stripper
            try {
                org.apache.pdfbox.pdmodel.PDDocument pdDoc =
                        org.apache.pdfbox.pdmodel.PDDocument.load(file);
                org.apache.pdfbox.text.PDFTextStripper stripper =
                        new org.apache.pdfbox.text.PDFTextStripper();
                stripper.setSortByPosition(true);
                String text = stripper.getText(pdDoc);
                pdDoc.close();
                if (text.trim().length() > 100) {
                    log.info("PDFBox extracted {} chars", text.length());
                    return text;
                }
            } catch (Exception e) {
                log.warn("PDFBox failed, trying Tika: {}", e.getMessage());
            }
        }

        // Fallback 2 — Tika
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
        String[] lines = text.split("\n");

        int currentPage = 1;
        int chunkIndex = 0;
        StringBuilder chunkText = new StringBuilder();
        int wordCount = 0;

        for (String line : lines) {
            // Detect page markers like "--- Page 14 ---"
            if (line.trim().matches("---\\s*Page\\s*(\\d+)\\s*---")) {
                currentPage = Integer.parseInt(line.trim().replaceAll(".*Page\\s*(\\d+).*", "$1"));
                continue; // skip the marker line itself
            }

            chunkText.append(line).append(" ");
            wordCount += line.split("\\s+").length;

            if (wordCount >= CHUNK_SIZE) {
                int paragraphNumber = chunkIndex + 1;
                chunks.add(DocumentChunk.builder()
                        .document(document)
                        .chunkText(chunkText.toString().trim())
                        .chunkIndex(chunkIndex++)
                        .pageNumber(currentPage)       // ← actual page from marker
                        .paragraphNumber(paragraphNumber)
                        .build());
                chunkText = new StringBuilder();
                wordCount = 0;
            }
        }

        // Save any remaining text as last chunk
        if (!chunkText.isEmpty()) {
            chunks.add(DocumentChunk.builder()
                    .document(document)
                    .chunkText(chunkText.toString().trim())
                    .chunkIndex(chunkIndex)
                    .pageNumber(currentPage)
                    .paragraphNumber(chunkIndex + 1)
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
                    if (trimmed.length() > 30 && !trimmed.contains(":")) return trimmed;
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
        // Delete chunks
        documentChunkRepository.deleteAll(
                documentChunkRepository.findByDocumentIdOrderByChunkIndex(id));

        // Delete from Supabase Storage
        documentRepository.findById(id).ifPresent(doc -> {
            if (doc.getStorageKey() != null) {
                storageService.delete(doc.getStorageKey());
            }
        });

        // Delete DB record
        documentRepository.deleteById(id);
    }
}