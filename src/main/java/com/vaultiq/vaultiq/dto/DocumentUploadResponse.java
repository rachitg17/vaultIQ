package com.vaultiq.vaultiq.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentUploadResponse {

    private String documentId;
    private String fileName;
    private String fileType;
    private String summary;
    private Integer totalPages;
    private Integer totalChunks;
    private String message;
    private boolean success;

}