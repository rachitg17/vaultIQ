package com.vaultiq.vaultiq.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "documents")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String fileName;

    @Column(nullable = false)
    private String fileType;

    @Column(name = "file_path")
    private String filePath; // legacy — no longer used

    // Supabase Storage object key e.g. "documents/uuid_filename.pdf"
    @Column(name = "storage_key")
    private String storageKey;

    @Column(columnDefinition = "TEXT")
    private String extractedText;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(nullable = false)
    private String uploadedBy;

    @Column(nullable = false)
    private LocalDateTime uploadedAt;

    @Column
    private Integer totalPages;

    @Column
    private Long fileSize;

    @Column(columnDefinition = "TEXT")
    private String tags;

    @Column(columnDefinition = "TEXT")
    private String keyEntities;
}