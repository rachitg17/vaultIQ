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
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class DocumentController {

    private final DocumentProcessingService documentProcessingService;
    private final ChatService chatService;
    private final SearchService searchService;

    // ─── UPLOAD ───────────────────────────────────────────

    @PostMapping("/upload")
    public ResponseEntity<DocumentUploadResponse> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "uploadedBy", defaultValue = "default_user") String uploadedBy,
            @RequestParam(value = "context", required = false) String context) {
        try {
            Document document = documentProcessingService.processDocument(file, uploadedBy,context);
            int chunkCount = documentProcessingService
                    .getChunkCount(document.getId());

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
    public ResponseEntity<List<Document>> getAllDocuments(
            @RequestParam(value = "uploadedBy", required = false) String uploadedBy) {
        List<Document> docs = uploadedBy != null
                ? documentProcessingService.getDocumentsByUser(uploadedBy)
                : documentProcessingService.getAllDocuments();
        return ResponseEntity.ok(docs);
    }
    @GetMapping("/documents/{id}/file")
    public ResponseEntity<byte[]> getDocumentFile(@PathVariable String id) {
        return documentProcessingService.getDocumentFile(id);
    }

    // ─── GET SINGLE DOCUMENT ──────────────────────────────

    @GetMapping("/documents/{id}")
    public ResponseEntity<Document> getDocument(@PathVariable String id) {
        return documentProcessingService.getDocumentById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ─── DELETE DOCUMENT ──────────────────────────────────

    @DeleteMapping("/documents/{id}")
    public ResponseEntity<String> deleteDocument(@PathVariable String id) {
        documentProcessingService.deleteDocument(id);
        return ResponseEntity.ok("Document deleted successfully");
    }

    // ─── CHAT ─────────────────────────────────────────────

    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        ChatResponse response = chatService.chat(request);
        return ResponseEntity.ok(response);
    }

    // ─── SEARCH ───────────────────────────────────────────

    @PostMapping("/search")
    public ResponseEntity<List<Document>> search(@RequestBody SearchRequest request) {
        List<Document> results = searchService.search(request);
        return ResponseEntity.ok(results);
    }

    // ─── HEALTH CHECK ─────────────────────────────────────

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("VaultIQ is running! 🚀");
    }
}