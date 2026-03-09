package com.vaultiq.vaultiq.repository;

import com.vaultiq.vaultiq.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentRepository extends JpaRepository<Document, String> {

    List<Document> findByUploadedBy(String uploadedBy);

    List<Document> findByFileType(String fileType);

    @Query("SELECT d FROM Document d WHERE " +
            "LOWER(d.fileName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "LOWER(d.tags) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "LOWER(d.keyEntities) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "LOWER(d.summary) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "LOWER(d.extractedText) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<Document> searchByKeyword(String keyword);

}