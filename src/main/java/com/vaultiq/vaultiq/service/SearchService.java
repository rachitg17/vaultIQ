package com.vaultiq.vaultiq.service;

import com.vaultiq.vaultiq.dto.SearchRequest;
import com.vaultiq.vaultiq.entity.Document;
import com.vaultiq.vaultiq.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

    private final DocumentRepository documentRepository;
    private final GeminiService geminiService;

    public List<Document> search(SearchRequest request) {
        log.info("Searching for: {}", request.getQuery());

        // For short/numeric queries — just do direct text search, skip AI
        boolean isSimpleQuery = request.getQuery().length() < 10 ||
                request.getQuery().matches(".*\\d{4,}.*");

        // Step 1: Keyword search in DB
        List<Document> keywordResults = documentRepository
                .searchByKeyword(request.getQuery());

        if (isSimpleQuery) {
            return keywordResults; // return immediately for numbers/short queries
        }

        // Step 2: AI scoring only for descriptive queries
        List<Document> allDocs = request.getUploadedBy() != null
                ? documentRepository.findByUploadedBy(request.getUploadedBy())
                : documentRepository.findAll();

        List<Document> aiScoredResults = scoreDocumentsWithAI(
                request.getQuery(), allDocs);

        List<Document> merged = mergeResults(keywordResults, aiScoredResults);

        if (request.getFileType() != null && !request.getFileType().isEmpty()) {
            merged = merged.stream()
                    .filter(d -> d.getFileType().equalsIgnoreCase(request.getFileType()))
                    .collect(Collectors.toList());
        }

        log.info("Found {} documents for query: {}", merged.size(), request.getQuery());
        return merged;
    }

    private List<Document> scoreDocumentsWithAI(String query, List<Document> documents) {
        List<Document> scored = new ArrayList<>();

        for (Document doc : documents) {
            // Build a mini context from summary + entities + tags
            String docContext = String.format(
                    "Filename: %s\nType: %s\nSummary: %s\nEntities: %s\nTags: %s",
                    doc.getFileName(),
                    doc.getFileType(),
                    doc.getSummary() != null ? doc.getSummary() : "",
                    doc.getKeyEntities() != null ? doc.getKeyEntities() : "",
                    doc.getTags() != null ? doc.getTags() : ""
            );

            String prompt = """
                Does this document match the search query?
                Query: "%s"
                Document info: %s
                
                Reply with only YES or NO.
                """.formatted(query, docContext);

            try {
                String response = geminiService.generateContent(prompt);
                if (response.trim().toUpperCase().startsWith("YES")) {
                    scored.add(doc);
                }
            } catch (Exception e) {
                log.warn("AI scoring failed for doc: {}", doc.getFileName());
            }
        }
        return scored;
    }

    private List<Document> mergeResults(List<Document> list1, List<Document> list2) {
        List<Document> merged = new ArrayList<>(list1);
        for (Document doc : list2) {
            boolean alreadyExists = merged.stream()
                    .anyMatch(d -> d.getId().equals(doc.getId()));
            if (!alreadyExists) {
                merged.add(doc);
            }
        }
        return merged;
    }
}