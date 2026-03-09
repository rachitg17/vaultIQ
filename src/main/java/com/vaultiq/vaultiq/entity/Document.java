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
    private String fileType; // resume, contract, aadhaar, pan, etc.

    @Column(name = "file_path")
    private String filePath;

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
    private String tags; // comma separated tags

    @Column(columnDefinition = "TEXT")
    private String keyEntities; // names, dates, amounts as JSON string

}