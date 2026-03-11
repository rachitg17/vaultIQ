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
        log.info("Searching for: {} by user: {}", request.getQuery(), request.getUploadedBy());

        String userId = request.getUploadedBy();

        // GUARD: if no user context, return empty — never leak other users' docs
        if (userId == null || userId.isBlank()) {
            log.warn("Search attempted with no uploadedBy — returning empty");
            return new ArrayList<>();
        }

        boolean isSimpleQuery = request.getQuery().length() < 10 ||
                request.getQuery().matches(".*\\d{4,}.*");

        // FIX 1: Always use user-scoped keyword search
        List<Document> keywordResults = documentRepository
                .searchByKeywordAndUser(request.getQuery(), userId);

        if (isSimpleQuery) {
            // FIX 2: Simple queries now also properly scoped
            return keywordResults;
        }

        // FIX 3: AI scoring only over current user's documents — never findAll()
        List<Document> userDocs = documentRepository.findByUploadedBy(userId);
        List<Document> aiScoredResults = scoreDocumentsWithAI(request.getQuery(), userDocs);

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