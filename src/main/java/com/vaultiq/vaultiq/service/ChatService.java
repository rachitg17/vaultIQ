package com.vaultiq.vaultiq.service;

import com.vaultiq.vaultiq.dto.ChatRequest;
import com.vaultiq.vaultiq.dto.ChatResponse;
import com.vaultiq.vaultiq.entity.Document;
import com.vaultiq.vaultiq.entity.DocumentChunk;
import com.vaultiq.vaultiq.repository.DocumentChunkRepository;
import com.vaultiq.vaultiq.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final GeminiService geminiService;
    @Transactional
    public ChatResponse chat(ChatRequest request) {
        log.info("Chat request: {}", request.getQuestion());

        // Step 1: Find relevant documents
        List<Document> documents = getRelevantDocuments(request);
        if (documents.isEmpty()) {
            return ChatResponse.builder()
                    .answer("No documents found. Please upload documents first.")
                    .success(false)
                    .build();
        }

        // Step 2: Find relevant chunks using keyword matching
        List<DocumentChunk> relevantChunks = findRelevantChunks(
                request.getQuestion(), documents);

        // Step 3: Build context from chunks
        String context = buildContext(relevantChunks);

        // Step 4: Ask Gemini
        String answer = geminiService.answerQuestion(request.getQuestion(), context);

        // Step 5: Build citations
        List<ChatResponse.Citation> citations = buildCitations(relevantChunks);

        return ChatResponse.builder()
                .answer(answer)
                .citations(citations)
                .success(true)
                .build();
    }

    private List<Document> getRelevantDocuments(ChatRequest request) {
        if (request.getDocumentIds() != null && !request.getDocumentIds().isEmpty()) {
            return documentRepository.findAllById(request.getDocumentIds());
        }
        if (request.getUploadedBy() != null) {
            return documentRepository.findByUploadedBy(request.getUploadedBy());
        }
        return documentRepository.findAll();
    }

    private List<DocumentChunk> findRelevantChunks(String question, List<Document> documents) {
        List<DocumentChunk> allChunks = new ArrayList<>();
        for (Document doc : documents) {
            allChunks.addAll(documentChunkRepository.findByDocumentIdOrderByChunkIndex(doc.getId()));
        }

        String questionLower = question.toLowerCase();
        String[] questionWords = questionLower.split("\\s+");

        // Score all chunks
        List<Map.Entry<DocumentChunk, Integer>> scored = allChunks.stream()
                .map(chunk -> Map.entry(chunk, scoreChunk(chunk.getChunkText(), questionWords, questionLower)))
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(10)
                .collect(Collectors.toList());

        if (scored.isEmpty()) return new ArrayList<>();

        int topScore = scored.get(0).getValue();
        int secondScore = scored.size() > 1 ? scored.get(1).getValue() : 0;

        // If top document is clearly more relevant, focus on that doc only
        if (topScore > secondScore + 3) {
            String topDocId = scored.get(0).getKey().getDocument().getId();
            return scored.stream()
                    .filter(e -> e.getKey().getDocument().getId().equals(topDocId))
                    .map(Map.Entry::getKey)
                    .limit(5)
                    .collect(Collectors.toList());
        }

        // Otherwise return top 5 across all docs
        return scored.stream()
                .map(Map.Entry::getKey)
                .limit(5)
                .collect(Collectors.toList());
    }

    private int scoreChunk(String text, String[] keywords, String fullQuestion) {
        String lowerText = text.toLowerCase();
        int score = 0;

        // Word match — short words like "10", "50" also count
        for (String keyword : keywords) {
            if (keyword.length() >= 2 && lowerText.contains(keyword)) {
                score += keyword.length() > 4 ? 3 : 1;
            }
        }

        // Sliding window — catches partial matches like "udapa", "siagl"
        for (int i = 0; i <= fullQuestion.length() - 3; i++) {
            String sub = fullQuestion.substring(i, Math.min(i + 5, fullQuestion.length()));
            if (sub.length() >= 3 && lowerText.contains(sub)) {
                score += 2;
            }
        }

        // Boost chunks with numeric data (prices, quantities, dates)
        if (lowerText.matches(".*\\d+\\.\\d+.*")) score += 2;

        return score;
    }
    @Transactional
    private String buildContext(List<DocumentChunk> chunks) {
        StringBuilder context = new StringBuilder();

            for (DocumentChunk chunk : chunks) {
                log.info("CHUNK TEXT: {}", chunk.getChunkText()); // ADD THIS
                context.append("--- From: ")
                        .append(chunk.getDocument().getFileName())
                        .append(" | Page: ").append(chunk.getPageNumber())
                        .append(" | Para: ").append(chunk.getParagraphNumber())
                        .append(" ---\n")
                        .append(chunk.getChunkText())
                        .append("\n\n");
            }
            return context.toString();
    }

    private List<ChatResponse.Citation> buildCitations(List<DocumentChunk> chunks) {
        return chunks.stream()
                .map(chunk -> ChatResponse.Citation.builder()
                        .documentName(chunk.getDocument().getFileName())
                        .documentId(chunk.getDocument().getId())
                        .pageNumber(chunk.getPageNumber())
                        .paragraphNumber(chunk.getParagraphNumber())
                        .relevantText(chunk.getChunkText().substring(
                                0, Math.min(200, chunk.getChunkText().length())) + "...")
                        .build())
                .collect(Collectors.toList());
    }
}