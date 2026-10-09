package com.docuware.ai;

import com.docuware.exception.ApiException;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class OllamaAiProviderTest {
    HttpServer server;
    AtomicReference<JsonNode> request = new AtomicReference<>();
    ObjectMapper mapper = new ObjectMapper();
    OllamaAiProvider provider(String response, int status) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            request.set(mapper.readTree(exchange.getRequestBody()));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        return new OllamaAiProvider(true, "http://127.0.0.1:" + server.getAddress().getPort(), "test-embed", "test-chat");
    }
    @AfterEach void stop() { if (server != null) server.stop(0); }
    @Test void embeddingRequestsDisableSilentTruncation() throws Exception {
        var ai = provider("{\"embeddings\":[[0.5,1,0]]}", 200);
        var result = ai.embed(List.of("document text"));
        assertThat(result).hasSize(1); assertThat(result.getFirst()).containsExactly(0.5f, 1, 0);
        assertThat(request.get().path("model").asText()).isEqualTo("test-embed");
        assertThat(request.get().path("truncate").asBoolean(true)).isFalse();
    }
    @Test void chatUsesSystemInstructionsAndNonStreamingResponse() throws Exception {
        var ai = provider("{\"message\":{\"content\":\"Grounded answer [1]\"}}", 200);
        assertThat(ai.generate("Use only sources", "Source text")).isEqualTo("Grounded answer [1]");
        assertThat(request.get().path("stream").asBoolean(true)).isFalse();
        assertThat(request.get().path("messages").get(0).path("role").asText()).isEqualTo("system");
    }
    @Test void invalidVectorsBecomeSafeProviderErrors() throws Exception {
        var ai = provider("{\"embeddings\":[[0,0,0]]}", 200);
        assertThatThrownBy(() -> ai.embed(List.of("text"))).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.code).isEqualTo("AI_UNAVAILABLE"));
    }
    @Test void upstreamFailureHidesProviderDetails() throws Exception {
        var ai = provider("{\"error\":\"private provider details\"}", 500);
        assertThatThrownBy(() -> ai.generate("instructions", "input")).isInstanceOf(ApiException.class)
            .hasMessage("AI provider is unavailable or returned an invalid response");
    }
    @Test void disabledProviderRequiresConfiguration() {
        var ai = new OllamaAiProvider(false, "http://localhost:1", "embed", "chat");
        assertThatThrownBy(() -> ai.embed(List.of("text"))).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.code).isEqualTo("AI_DISABLED"));
    }
}
