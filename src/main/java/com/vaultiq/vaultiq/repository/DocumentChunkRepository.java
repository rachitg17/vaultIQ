package com.vaultiq.vaultiq.repository;

import com.vaultiq.vaultiq.entity.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, String> {

    List<DocumentChunk> findByDocumentIdOrderByChunkIndex(String documentId);

    List<DocumentChunk> findByDocumentIdAndPageNumber(String documentId, Integer pageNumber);

}