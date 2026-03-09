package com.vaultiq.vaultiq.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {

    private String answer;
    private List<Citation> citations;
    private boolean success;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Citation {
        private String documentName;
        private String documentId;
        private Integer pageNumber;
        private Integer paragraphNumber;
        private String relevantText;
    }

}