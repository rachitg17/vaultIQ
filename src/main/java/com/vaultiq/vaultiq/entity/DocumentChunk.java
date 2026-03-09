package com.vaultiq.vaultiq.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "document_chunks")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String chunkText;

    @Column(nullable = false)
    private Integer chunkIndex;

    @Column(nullable = false)
    private Integer pageNumber;

    @Column(nullable = false)
    private Integer paragraphNumber;

    // We store the vector as a string for now
    // PGVector will handle actual vector operations via native queries
    @Column(columnDefinition = "TEXT")
    private String embeddingJson;

}