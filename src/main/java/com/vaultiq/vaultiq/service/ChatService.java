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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final GeminiService geminiService;

    // Keywords that signal the user wants to compare across documents
    private static final List<String> COMPARISON_KEYWORDS = List.of(
            "compare", "comparison", "better", "worse", "difference", "different",
            "which one", "vs", "versus", "both", "all", "across", "between",
            "contrast", "similar", "same", "common", "combined", "together"
    );

    // Max citations shown per document — keeps UI clean
    private static final int MAX_CITATIONS_PER_DOC = 2;

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

        boolean isSingleDoc = request.getDocumentIds() != null
                && request.getDocumentIds().size() == 1;

        boolean isComparison = isComparisonQuery(request.getQuestion());
        log.info("Query type — singleDoc: {}, comparison: {}", isSingleDoc, isComparison);

        List<DocumentChunk> relevantChunks;

        if (isSingleDoc) {
            relevantChunks = findChunksForSingleDoc(request.getQuestion(), documents.getFirst());
        } else if (isComparison && documents.size() > 1) {
            relevantChunks = findChunksForComparison(request.getQuestion(), documents);
        } else {
            relevantChunks = findRelevantChunks(request.getQuestion(), documents);
        }

        String context = buildContext(relevantChunks);
        String rawAnswer = geminiService.answerQuestion(request.getQuestion(), context);

        // Extract page numbers the LLM actually mentioned in its answer
        List<Integer> mentionedPages = extractMentionedPages(rawAnswer);
        log.info("Pages mentioned in answer: {}", mentionedPages);

        List<ChatResponse.Citation> citations = buildSmartCitations(
                relevantChunks, rawAnswer, mentionedPages);

        return ChatResponse.builder()
                .answer(rawAnswer)
                .citations(citations)
                .success(true)
                .build();
    }

    // ── Extract page numbers from LLM answer e.g. "pages 44-45", "page 5" ──
    private List<Integer> extractMentionedPages(String answer) {
        List<Integer> pages = new ArrayList<>();
        Pattern pattern = Pattern.compile(
                "pages?\\s+(\\d+)(?:\\s*[–\\-]\\s*(\\d+))?",
                Pattern.CASE_INSENSITIVE);
        Matcher m = pattern.matcher(answer);
        while (m.find()) {
            pages.add(Integer.parseInt(m.group(1)));
            if (m.group(2) != null) {
                pages.add(Integer.parseInt(m.group(2)));
            }
        }
        return pages;
    }

    private boolean isComparisonQuery(String question) {
        String lower = question.toLowerCase();
        return COMPARISON_KEYWORDS.stream().anyMatch(lower::contains);
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

    // Single doc — score within that doc only
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

    // Comparison — take top N chunks from EACH document, guaranteed representation
    private List<DocumentChunk> findChunksForComparison(String question, List<Document> documents) {
        String questionLower = question.toLowerCase();
        String[] questionWords = questionLower.split("\\s+");

        List<DocumentChunk> result = new ArrayList<>();
        int chunksPerDoc = Math.max(1, 8 / documents.size());

        for (Document doc : documents) {
            List<DocumentChunk> chunks =
                    documentChunkRepository.findByDocumentIdOrderByChunkIndex(doc.getId());

            List<DocumentChunk> topChunks = chunks.stream()
                    .map(chunk -> Map.entry(chunk,
                            scoreChunk(chunk.getChunkText(), questionWords, questionLower)))
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(chunksPerDoc)
                    .map(Map.Entry::getKey)
                    .toList();

            log.info("Comparison: picked {} chunks from '{}'", topChunks.size(), doc.getFileName());
            result.addAll(topChunks);
        }

        return result;
    }

    // Normal multi-doc — scored with gap detection (tightened to 3x)
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

        // Tightened from 2x → 3x: only focus on single doc if clearly dominant
        if (topScore > 0 && topScore >= secondScore * 3) {
            String topDocId = scored.getFirst().getKey().getDocument().getId();
            log.info("Clear winner doc (score {} vs {}), focusing on it", topScore, secondScore);
            return scored.stream()
                    .filter(e -> e.getKey().getDocument().getId().equals(topDocId))
                    .map(Map.Entry::getKey)
                    .limit(5)
                    .collect(Collectors.toList());
        }

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

        for (int i = 0; i <= fullQuestion.length() - 3; i++) {
            String sub = fullQuestion.substring(i,
                    Math.min(i + 5, fullQuestion.length()));
            if (sub.length() >= 3 && lowerText.contains(sub)) {
                score += 2;
            }
        }

        if (lowerText.matches(".*\\d+\\.\\d+.*")) score += 2;

        return score;
    }

    @Transactional
    private String buildContext(List<DocumentChunk> chunks) {
        StringBuilder context = new StringBuilder();
        for (DocumentChunk chunk : chunks) {
            log.info("CHUNK [{}] page={}", chunk.getDocument().getFileName(), chunk.getPageNumber());
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

    private List<ChatResponse.Citation> buildSmartCitations(
            List<DocumentChunk> chunks, String answer, List<Integer> mentionedPages) {

        if (chunks.isEmpty()) return new ArrayList<>();

        String answerLower = answer.toLowerCase();

        // Step 1 — find which docs the LLM actually mentioned by filename
        Set<String> mentionedDocIds = chunks.stream()
                .filter(chunk -> {
                    String fname = chunk.getDocument().getFileName().toLowerCase();
                    String fnameNoExt = fname.contains(".")
                            ? fname.substring(0, fname.lastIndexOf('.'))
                            : fname;
                    return answerLower.contains(fname) || answerLower.contains(fnameNoExt);
                })
                .map(chunk -> chunk.getDocument().getId())
                .collect(Collectors.toSet());

        List<DocumentChunk> citedChunks;

        if (!mentionedDocIds.isEmpty()) {
            citedChunks = chunks.stream()
                    .filter(c -> mentionedDocIds.contains(c.getDocument().getId()))
                    .collect(Collectors.toList());
        } else {
            // Fallback: one citation per unique doc in context
            Map<String, DocumentChunk> onePerDoc = new LinkedHashMap<>();
            for (DocumentChunk c : chunks) {
                onePerDoc.putIfAbsent(c.getDocument().getId(), c);
            }
            citedChunks = new ArrayList<>(onePerDoc.values());
        }

        // Step 2 — deduplicate by doc + page
        List<DocumentChunk> deduped = new ArrayList<>(
                citedChunks.stream()
                        .collect(Collectors.toMap(
                                c -> c.getDocument().getId() + "_" + c.getPageNumber(),
                                c -> c,
                                (a, b) -> a,
                                LinkedHashMap::new
                        ))
                        .values()
        );

        // Step 3 — sort: chunks whose page was mentioned in the answer come FIRST
        deduped.sort((a, b) -> {
            boolean aMatch = mentionedPages.contains(a.getPageNumber());
            boolean bMatch = mentionedPages.contains(b.getPageNumber());
            if (aMatch && !bMatch) return -1;
            if (!aMatch && bMatch) return 1;
            return 0;
        });

        // Step 4 — hard cap: max MAX_CITATIONS_PER_DOC per document
        Map<String, Integer> countPerDoc = new HashMap<>();
        List<DocumentChunk> capped = new ArrayList<>();
        for (DocumentChunk chunk : deduped) {
            String docId = chunk.getDocument().getId();
            int count = countPerDoc.getOrDefault(docId, 0);
            if (count < MAX_CITATIONS_PER_DOC) {
                capped.add(chunk);
                countPerDoc.put(docId, count + 1);
            }
        }

        log.info("Citations after all filters: {} chips", capped.size());

        return capped.stream()
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