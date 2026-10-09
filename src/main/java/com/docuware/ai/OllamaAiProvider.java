package com.docuware.ai;

import com.docuware.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class OllamaAiProvider implements AiProvider {
    private final boolean enabled;
    private final String embeddingModel, chatModel;
    private final RestClient client;
    public OllamaAiProvider(@Value("${app.ai.enabled}") boolean enabled,
                            @Value("${app.ai.base-url}") String baseUrl,
                            @Value("${app.ai.embedding-model}") String embeddingModel,
                            @Value("${app.ai.chat-model}") String chatModel) {
        this.enabled = enabled; this.embeddingModel = embeddingModel; this.chatModel = chatModel;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(120));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }
    public boolean enabled() { return enabled; }
    public String embeddingModel() { return embeddingModel; }
    public String chatModel() { return chatModel; }
    public List<float[]> embed(List<String> texts) {
        requireEnabled();
        try {
            JsonNode response = client.post().uri("/api/embed").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("model", embeddingModel, "input", texts, "truncate", false)).retrieve().body(JsonNode.class);
            if (response == null || !response.path("embeddings").isArray() || response.path("embeddings").size() != texts.size()) throw unavailable();
            List<float[]> result = new ArrayList<>();
            for (JsonNode vector : response.path("embeddings")) {
                if (!vector.isArray() || vector.isEmpty() || vector.size() > 16000) throw unavailable();
                float[] values = new float[vector.size()]; double magnitude = 0;
                for (int i = 0; i < values.length; i++) {
                    if (!vector.get(i).isNumber()) throw unavailable();
                    values[i] = (float) vector.get(i).asDouble();
                    if (!Float.isFinite(values[i])) throw unavailable();
                    magnitude += (double) values[i] * values[i];
                }
                if (magnitude == 0 || (!result.isEmpty() && result.getFirst().length != values.length)) throw unavailable();
                result.add(values);
            }
            return result;
        } catch (RuntimeException e) { if (e instanceof ApiException api) throw api; throw unavailable(); }
    }
    public String generate(String instructions, String input) {
        requireEnabled();
        try {
            JsonNode response = client.post().uri("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("model", chatModel, "stream", false, "messages", List.of(
                    Map.of("role", "system", "content", instructions), Map.of("role", "user", "content", input)),
                    "options", Map.of("temperature", 0.1, "num_ctx", 8192, "num_predict", 700)))
                .retrieve().body(JsonNode.class);
            String answer = response == null ? "" : response.path("message").path("content").asText("");
            if (answer.isBlank()) throw unavailable();
            return answer;
        } catch (RuntimeException e) { if (e instanceof ApiException api) throw api; throw unavailable(); }
    }
    private void requireEnabled() {
        if (!enabled) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_DISABLED", "Enable and configure the AI provider to use this endpoint");
    }
    private ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE", "AI provider is unavailable or returned an invalid response");
    }
}
