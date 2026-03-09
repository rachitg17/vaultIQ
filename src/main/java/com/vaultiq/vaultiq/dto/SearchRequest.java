package com.vaultiq.vaultiq.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchRequest {

    private String query;      // "find government ID from Mumbai"
    private String uploadedBy;
    private String fileType;   // optional filter

}