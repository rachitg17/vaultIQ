package com.vaultiq.vaultiq.controller;

import com.vaultiq.vaultiq.dto.ChatRequest;
import com.vaultiq.vaultiq.dto.ChatResponse;
import com.vaultiq.vaultiq.dto.DocumentUploadResponse;
import com.vaultiq.vaultiq.dto.SearchRequest;
import com.vaultiq.vaultiq.entity.Document;
import com.vaultiq.vaultiq.service.ChatService;
import com.vaultiq.vaultiq.service.DocumentProcessingService;
import com.vaultiq.vaultiq.service.SearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@CrossOrigin(origins = "*", allowCredentials = "true")
public class DocumentController {

    private final DocumentProcessingService documentProcessingService;
    private final ChatService chatService;
    private final SearchService searchService;

    // ─── UPLOAD ───────────────────────────────────────────

    @PostMapping("/upload")
    public ResponseEntity<DocumentUploadResponse> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "context", required = false) String context,
            Authentication authentication) {
        try {
            String userId = getCurrentUserId(authentication);
            Document document = documentProcessingService.processDocument(file, userId, context);
            int chunkCount = documentProcessingService.getChunkCount(document.getId());

            return ResponseEntity.ok(DocumentUploadResponse.builder()
                    .documentId(document.getId())
                    .fileName(document.getFileName())
                    .fileType(document.getFileType())
                    .summary(document.getSummary())
                    .totalPages(document.getTotalPages())
                    .totalChunks(chunkCount)
                    .message("Document uploaded and processed successfully!")
                    .success(true)
                    .build());

        } catch (Exception e) {
            log.error("Upload failed: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(DocumentUploadResponse.builder()
                            .message("Upload failed: " + e.getMessage())
                            .success(false)
                            .build());
        }
    }

    // ─── GET ALL DOCUMENTS ────────────────────────────────

    @GetMapping("/documents")
    public ResponseEntity<List<Document>> getAllDocuments(Authentication authentication) {
        String userId = getCurrentUserId(authentication);
        return ResponseEntity.ok(documentProcessingService.getDocumentsByUser(userId));
    }

    // ─── GET SINGLE DOCUMENT ──────────────────────────────

    @GetMapping("/documents/{id}")
    public ResponseEntity<Document> getDocument(
            @PathVariable String id,
            Authentication authentication) {
        String userId = getCurrentUserId(authentication);
        return documentProcessingService.getDocumentById(id)
                .filter(doc -> doc.getUploadedBy().equals(userId))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ─── GET DOCUMENT FILE ────────────────────────────────

    @GetMapping("/documents/{id}/file")
    public ResponseEntity<byte[]> getDocumentFile(
            @PathVariable String id,
            Authentication authentication) {
        String userId = getCurrentUserId(authentication);
        // Verify document belongs to this user
        boolean owned = documentProcessingService.getDocumentById(id)
                .map(doc -> doc.getUploadedBy().equals(userId))
                .orElse(false);
        if (!owned) return ResponseEntity.status(403).build();
        return documentProcessingService.getDocumentFile(id);
    }

    // ─── DELETE DOCUMENT ──────────────────────────────────

    @DeleteMapping("/documents/{id}")
    public ResponseEntity<String> deleteDocument(
            @PathVariable String id,
            Authentication authentication) {
        String userId = getCurrentUserId(authentication);
        boolean owned = documentProcessingService.getDocumentById(id)
                .map(doc -> doc.getUploadedBy().equals(userId))
                .orElse(false);
        if (!owned) return ResponseEntity.status(403).body("Access denied");
        documentProcessingService.deleteDocument(id);
        return ResponseEntity.ok("Document deleted successfully");
    }

    // ─── CHAT ─────────────────────────────────────────────

    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(
            @RequestBody ChatRequest request,
            Authentication authentication) {
        String userId = getCurrentUserId(authentication);
        request.setUploadedBy(userId);
        ChatResponse response = chatService.chat(request);
        return ResponseEntity.ok(response);
    }

    // ─── SEARCH ───────────────────────────────────────────

    @PostMapping("/search")
    public ResponseEntity<List<Document>> search(
            @RequestBody SearchRequest request,
            Authentication authentication) {
        String userId = getCurrentUserId(authentication);
        request.setUploadedBy(userId);
        List<Document> results = searchService.search(request);
        return ResponseEntity.ok(results);
    }

    // ─── HEALTH CHECK ─────────────────────────────────────

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("VaultIQ is running! 🚀");
    }

    // ─── HELPER ───────────────────────────────────────────

    private String getCurrentUserId(Authentication authentication) {
        return (String) authentication.getPrincipal();
    }
}