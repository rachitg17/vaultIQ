package com.vaultiq.vaultiq.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class GeminiService {

    @Value("${groq.api.key}")
    private String apiKey;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String GROQ_URL =
            "https://api.groq.com/openai/v1/chat/completions";
    private static final String MODEL = "llama-3.1-8b-instant";

    public String generateContent(String prompt) {
        try {
            String requestBody = """
                    {
                        "model": "%s",
                        "messages": [
                            {
                                "role": "user",
                                "content": %s
                            }
                        ],
                        "max_tokens": 1024
                    }
                    """.formatted(MODEL,
                    objectMapper.writeValueAsString(prompt));

            Request request = new Request.Builder()
                    .url(GROQ_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .post(RequestBody.create(requestBody,
                            MediaType.parse("application/json")))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                assert response.body() != null;
                String responseBody = response.body().string();
                log.info("Groq response received");
                JsonNode root = objectMapper.readTree(responseBody);

                if (root.has("error")) {
                    throw new RuntimeException("Groq API error: " +
                            root.path("error").path("message").asText());
                }

                return root.path("choices")
                        .get(0)
                        .path("message")
                        .path("content")
                        .asText();
            }
        } catch (Exception e) {
            log.error("Groq API call failed: {}", e.getMessage());
            throw new RuntimeException("Groq API call failed: " + e.getMessage(), e);
        }
    }

    public String summarizeDocument(String extractedText) {
        String shortText = extractedText.substring(0,
                Math.min(extractedText.length(), 3000));

        String prompt = """
            Analyze this document. You MUST respond in exactly this format with these exact labels:
            SUMMARY: <3-4 sentence summary of what this document is>
            TYPE: <single word: resume/contract/report/invoice/scorecard/marksheet/certificate/aadhaar/pan/other>
            ENTITIES: <key names, dates, numbers, IDs found>
            TAGS: <5 comma separated keywords>
            
            Do not add any extra text before or after. Start directly with SUMMARY:
            
            Document content:
            %s
            """.formatted(shortText);

        return generateContent(prompt);
    }

    public String answerQuestion(String question, String context) {
        String prompt =
                "You are an expert document analysis assistant.\n\n" +
                        "DOCUMENT CONTEXT:\n" +
                        "================\n" +
                        context +
                        "\n================\n\n" +
                        "QUESTION: " + question + "\n\n" +
                        "RULES FOR ANSWERING:\n" +
                        // FIX: Remove markdown/bolding instruction — causes raw ** in frontend
                        // FIX: Remove pipe ban from rule 1 since it's now rule 2
                        "1. FORMAT: Write in clean plain text only. Do NOT use markdown, asterisks, " +
                        "bold syntax (**text**), bullet symbols, or the pipe character (|) anywhere in your response.\n" +
                        "2. TABULAR DATA: If the context contains pipe-separated rows (table data), " +
                        "read the columns carefully and present the values as a clean numbered or " +
                        "comma-separated list. Map each header to its value. Never output raw pipe characters.\n" +
                        "3. ACCURACY: Match names, numbers, dates, and percentiles exactly as written " +
                        "in the context. Do not round, estimate, or calculate unless explicitly asked.\n" +
                        "4. CONCISENESS: Answer directly and completely. No intro phrases like " +
                        "'Based on the document' or 'According to'. Just the facts.\n" +
                        "5. MULTI-SOURCE: If information comes from multiple documents, mention each " +
                        "source filename naturally in the sentence so citations can be matched.\n" +
                        "6. NOT FOUND: Only say information is missing if you have genuinely searched " +
                        "all provided context and found nothing relevant.\n\n" +
                        "ANSWER:";

        return generateContent(prompt);
    }

    public String extractTextFromPageImage(java.awt.image.BufferedImage pageImage) {
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(pageImage, "png", baos);
            String base64Image = java.util.Base64.getEncoder()
                    .encodeToString(baos.toByteArray());

            ObjectMapper mapper = new ObjectMapper();
            ObjectNode root = mapper.createObjectNode();
            root.put("model", "meta-llama/llama-4-scout-17b-16e-instruct");
            root.put("max_tokens", 2000);

            ArrayNode messages = root.putArray("messages");
            ObjectNode message = messages.addObject();
            message.put("role", "user");

            ArrayNode content = message.putArray("content");

            ObjectNode imageBlock = content.addObject();
            imageBlock.put("type", "image_url");
            imageBlock.putObject("image_url")
                    .put("url", "data:image/png;base64," + base64Image);

            ObjectNode textBlock = content.addObject();
            textBlock.put("type", "text");
            textBlock.put("text",
                    "Extract ALL text from this document page exactly as it appears. " +
                            "For tables: write each row on a new line, separate each column value with | character. " +
                            "Include the header row first. " +
                            "Preserve all numbers, names, and values accurately. " +
                            "Do not summarize or skip anything. Output raw extracted text only.");

            String requestBody = mapper.writeValueAsString(root);

            Request request = new Request.Builder()
                    .url("https://api.groq.com/openai/v1/chat/completions")
                    .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .build();

            try (Response response = client.newCall(request).execute()) {
                assert response.body() != null;
                String responseBody = response.body().string();
                JsonNode responseRoot = mapper.readTree(responseBody);

                if (responseRoot.has("error")) {
                    log.error("Groq vision error: {}",
                            responseRoot.path("error").path("message").asText());
                    return "";
                }

                return responseRoot.path("choices").get(0)
                        .path("message").path("content").asText();
            }
        } catch (Exception e) {
            log.error("Groq vision extraction failed: {}", e.getMessage());
            return "";
        }
    }
}