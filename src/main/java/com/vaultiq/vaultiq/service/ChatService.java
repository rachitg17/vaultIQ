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

import java.util.*;
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

        List<Document> documents = getRelevantDocuments(request);
        if (documents.isEmpty()) {
            return ChatResponse.builder()
                    .answer("No documents found. Please upload documents first.")
                    .success(false)
                    .build();
        }

        // FIX 1: If single doc chat, skip cross-doc scoring entirely
        boolean isSingleDoc = request.getDocumentIds() != null
                && request.getDocumentIds().size() == 1;

        List<DocumentChunk> relevantChunks = isSingleDoc
                ? findChunksForSingleDoc(request.getQuestion(), documents.getFirst())
                : findRelevantChunks(request.getQuestion(), documents);

        String context = buildContext(relevantChunks);
        String rawAnswer = geminiService.answerQuestion(request.getQuestion(), context);

        // FIX 2: Build citations only from the docs whose chunks were actually used
        // Group chunks by document, pick the top-scoring document's chunks as citations
        List<ChatResponse.Citation> citations = buildSmartCitations(relevantChunks, rawAnswer);

        return ChatResponse.builder()
                .answer(rawAnswer)
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

    // FIX 1: Single doc — just score within that doc, no cross-doc noise
    private List<DocumentChunk> findChunksForSingleDoc(String question, Document doc) {
        List<DocumentChunk> chunks =
                documentChunkRepository.findByDocumentIdOrderByChunkIndex(doc.getId());

        String questionLower = question.toLowerCase();
        String[] questionWords = questionLower.split("\\s+");

        return chunks.stream()
                .map(chunk -> Map.entry(chunk,
                        scoreChunk(chunk.getChunkText(), questionWords, questionLower)))
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(5)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    private List<DocumentChunk> findRelevantChunks(String question, List<Document> documents) {
        List<DocumentChunk> allChunks = new ArrayList<>();
        for (Document doc : documents) {
            allChunks.addAll(
                    documentChunkRepository.findByDocumentIdOrderByChunkIndex(doc.getId()));
        }

        String questionLower = question.toLowerCase();
        String[] questionWords = questionLower.split("\\s+");

        List<Map.Entry<DocumentChunk, Integer>> scored = allChunks.stream()
                .map(chunk -> Map.entry(chunk,
                        scoreChunk(chunk.getChunkText(), questionWords, questionLower)))
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(10)
                .toList();

        if (scored.isEmpty()) return new ArrayList<>();

        int topScore = scored.get(0).getValue();
        int secondScore = scored.size() > 1 ? scored.get(1).getValue() : 0;

        // FIX 3: Relative gap — top doc must score at least 2x the second doc
        // Prevents noise docs with coincidental keyword matches from sneaking in
        if (topScore > 0 && topScore >= secondScore +2) {
            String topDocId = scored.getFirst().getKey().getDocument().getId();
            log.info("Clear winner doc: {} (score {} vs {})", topDocId, topScore, secondScore);
            return scored.stream()
                    .filter(e -> e.getKey().getDocument().getId().equals(topDocId))
                    .map(Map.Entry::getKey)
                    .limit(5)
                    .collect(Collectors.toList());
        }

        // Otherwise return top chunks across docs (still capped at 5)
        return scored.stream()
                .map(Map.Entry::getKey)
                .limit(5)
                .collect(Collectors.toList());
    }

    private int scoreChunk(String text, String[] keywords, String fullQuestion) {
        String lowerText = text.toLowerCase();
        int score = 0;

        for (String keyword : keywords) {
            if (keyword.length() >= 2 && lowerText.contains(keyword)) {
                score += keyword.length() > 4 ? 3 : 1;
            }
        }

        // Sliding window for partial phrase matches
        for (int i = 0; i <= fullQuestion.length() - 3; i++) {
            String sub = fullQuestion.substring(i,
                    Math.min(i + 5, fullQuestion.length()));
            if (sub.length() >= 3 && lowerText.contains(sub)) {
                score += 2;
            }
        }

        // Boost numeric data (prices, percentiles, dates)
        if (lowerText.matches(".*\\d+\\.\\d+.*")) score += 2;

        return score;
    }

    @Transactional
    private String buildContext(List<DocumentChunk> chunks) {
        StringBuilder context = new StringBuilder();
        for (DocumentChunk chunk : chunks) {
            log.info("CHUNK [{}] page={} score context building",
                    chunk.getDocument().getFileName(), chunk.getPageNumber());
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

    // FIX 2: Smart citations — only cite chunks whose document is actually referenced
    // Primary: match by filename mention in answer
    // Fallback: if no filename match, cite only from the top chunk's document
    private List<ChatResponse.Citation> buildSmartCitations(
            List<DocumentChunk> chunks, String answer) {

        if (chunks.isEmpty()) return new ArrayList<>();

        String answerLower = answer.toLowerCase();

        // Try to match which documents are actually mentioned in the answer
        Set<String> mentionedDocIds = chunks.stream()
                .filter(chunk -> {
                    String fname = chunk.getDocument().getFileName().toLowerCase();
                    // Check filename or filename without extension
                    String fnameNoExt = fname.contains(".")
                            ? fname.substring(0, fname.lastIndexOf('.'))
                            : fname;
                    return answerLower.contains(fname) || answerLower.contains(fnameNoExt);
                })
                .map(chunk -> chunk.getDocument().getId())
                .collect(Collectors.toSet());

        List<DocumentChunk> citedChunks;

        if (!mentionedDocIds.isEmpty()) {
            // Only cite chunks from documents actually mentioned in answer
            citedChunks = chunks.stream()
                    .filter(c -> mentionedDocIds.contains(c.getDocument().getId()))
                    .collect(Collectors.toList());
        } else {
            // Fallback: cite only the top chunk's document (most relevant one)
            String topDocId = chunks.get(0).getDocument().getId();
            citedChunks = chunks.stream()
                    .filter(c -> c.getDocument().getId().equals(topDocId))
                    .collect(Collectors.toList());
        }

        // Deduplicate by page to avoid showing same page twice
        return citedChunks.stream()
                .collect(Collectors.toMap(
                        c -> c.getDocument().getId() + "_" + c.getPageNumber(),
                        c -> c,
                        (a, b) -> a,   // keep first on duplicate
                        LinkedHashMap::new
                ))
                .values().stream()
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