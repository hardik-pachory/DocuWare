package com.docuware;

import com.docuware.document.*;
import com.docuware.processing.DocumentProcessor;
import com.docuware.storage.StorageService;
import com.docuware.exception.ApiException;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import java.time.Instant;
import org.springframework.test.web.servlet.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiFlowTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DocumentProcessor processor;
    @Autowired DocumentRepository documents;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder encoder;
    @Autowired DocumentService service;
    @MockitoSpyBean StorageService storage;
    static final Path STORAGE = Path.of("target", "test-documents").toAbsolutePath();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.storage-root", STORAGE::toString);
    }
    @BeforeEach void chunksTable() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS document_chunks(id UUID PRIMARY KEY, document_id UUID REFERENCES documents(id) ON DELETE CASCADE, chunk_index INTEGER NOT NULL, content TEXT NOT NULL, embedding VARCHAR)");
    }
    String register() throws Exception {
        return register("user-" + UUID.randomUUID() + "@example.com");
    }
    String register(String email) throws Exception {
        var result = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("name", "Test User", "email", email, "password", "long-secure-password"))))
            .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("accessToken").asText();
    }
    JsonNode upload(String token, String filename, String mime, byte[] bytes) throws Exception {
        var file = new MockMultipartFile("file", filename, mime, bytes);
        var metadata = new MockMultipartFile("metadata", "", "application/json",
            "{\"title\":\"Spring Notes\",\"description\":\"Backend guide\",\"tags\":[\"Java\",\"spring\"]}".getBytes(StandardCharsets.UTF_8));
        var result = mvc.perform(multipart("/api/v1/documents").file(file).file(metadata).header("Authorization", "Bearer " + token))
            .andExpect(status().isCreated()).andExpect(header().exists("Location")).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }
    @Test void completeDocumentLifecycle() throws Exception {
        String token = register(); byte[] content = "Spring Boot provides dependency injection and REST APIs.".getBytes(StandardCharsets.UTF_8);
        JsonNode uploaded = upload(token, "notes.txt", "text/plain", content);
        String id = uploaded.path("id").asText();
        assertThat(uploaded.path("processingStatus").asText()).isEqualTo("UPLOADED");
        processor.process(UUID.fromString(id));
        mvc.perform(get("/api/v1/documents/" + id).header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.processingStatus").value("COMPLETED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_chunks WHERE document_id = ?", Integer.class, UUID.fromString(id))).isEqualTo(1);
        mvc.perform(get("/api/v1/documents").param("tag", "java").param("fileType", "txt").param("createdAfter", "2026-01-01")
            .header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(patch("/api/v1/documents/" + id).header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Renamed\",\"tags\":[]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Renamed")).andExpect(jsonPath("$.tags").isEmpty());
        mvc.perform(get("/api/v1/documents/" + id + "/download").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andExpect(content().bytes(content));
        mvc.perform(post("/api/v1/documents/" + id + "/summary").header("Authorization", "Bearer " + token))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value("AI_DISABLED"));
        mvc.perform(delete("/api/v1/documents/" + id).header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());
        assertThat(Files.exists(STORAGE.resolve(id + ".txt"))).isFalse();
        assertThat(documents.existsById(UUID.fromString(id))).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_chunks WHERE document_id = ?", Integer.class, UUID.fromString(id))).isZero();
    }
    @Test void crossUserAccessIsDeniedEverywhere() throws Exception {
        String alice = register(), bob = register();
        String id = upload(alice, "private.txt", "text/plain", "Private notes".getBytes()).path("id").asText();
        for (String suffix : List.of("", "/download")) {
            mvc.perform(get("/api/v1/documents/" + id + suffix).header("Authorization", "Bearer " + bob)).andExpect(status().isNotFound());
        }
        for (String suffix : List.of("/summary", "/retry-processing")) {
            mvc.perform(post("/api/v1/documents/" + id + suffix).header("Authorization", "Bearer " + bob)).andExpect(status().isNotFound());
        }
        mvc.perform(post("/api/v1/documents/" + id + "/ask").header("Authorization", "Bearer " + bob)
            .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"What is in the file?\"}")).andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/documents/" + id).header("Authorization", "Bearer " + bob)
            .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Stolen\"}")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/documents/" + id).header("Authorization", "Bearer " + bob)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/documents").header("Authorization", "Bearer " + bob)).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(delete("/api/v1/documents/" + id).header("Authorization", "Bearer " + alice)).andExpect(status().isNoContent());
    }
    @Test void authenticationValidationAndLogin() throws Exception {
        String email = "auth-" + UUID.randomUUID() + "@example.com";
        register(email);
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("email", email.toUpperCase(Locale.ROOT), "password", "long-secure-password"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.tokenType").value("Bearer"));
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("email", email, "password", "incorrect-password"))))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("INVALID_CREDENTIALS"));
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("name", "User", "email", email, "password", "long-secure-password"))))
            .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"User\",\"email\":\"invalid\",\"password\":\"short\"}"))
            .andExpect(status().isBadRequest());
    }
    @Test void protectedEndpointsRejectMissingAndForgedTokens() throws Exception {
        mvc.perform(get("/api/v1/documents")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
        mvc.perform(get("/api/v1/documents").header("Authorization", "Bearer forged.jwt.token"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }
    @Test void invalidUploadsRejectedWithoutMetadataOrFiles() throws Exception {
        String token = register();
        for (MockMultipartFile file : List.of(
            new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]),
            new MockMultipartFile("file", "fake.pdf", "application/pdf", "Not a PDF".getBytes()),
            new MockMultipartFile("file", "fake.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Not a DOCX".getBytes()),
            new MockMultipartFile("file", "program.exe", "application/octet-stream", "executable".getBytes()),
            new MockMultipartFile("file", "fake.txt", "text/plain", new byte[]{0, 1, 2}),
            new MockMultipartFile("file", "wrong.txt", "image/png", "text".getBytes()))) {
            mvc.perform(multipart("/api/v1/documents").file(file).header("Authorization", "Bearer " + token)).andExpect(status().is4xxClientError());
        }
        mvc.perform(get("/api/v1/documents").header("Authorization", "Bearer " + token)).andExpect(jsonPath("$.totalElements").value(0));
    }
    @Test void filenamesSanitizedAndPaginationBounded() throws Exception {
        String token = register();
        String id = upload(token, "../../notes.txt", "text/plain", "notes".getBytes()).path("id").asText();
        mvc.perform(get("/api/v1/documents/" + id).header("Authorization", "Bearer " + token)).andExpect(jsonPath("$.filename").value("notes.txt"));
        mvc.perform(get("/api/v1/documents").param("size", "10000").header("Authorization", "Bearer " + token)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/documents").param("sort", "storageKey,asc").header("Authorization", "Bearer " + token)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/documents").param("createdAfter", "bad-date").header("Authorization", "Bearer " + token)).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/documents/" + id).header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());
    }
    @Test void processingFailureRetryAndDuplicateProtection() throws Exception {
        String token = register();
        String id = upload(token, "broken.pdf", "application/pdf", "%PDF-broken".getBytes()).path("id").asText();
        mvc.perform(post("/api/v1/documents/" + id + "/retry-processing").header("Authorization", "Bearer " + token)).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/documents/" + id + "/summary").header("Authorization", "Bearer " + token)).andExpect(status().isConflict());
        processor.process(UUID.fromString(id));
        mvc.perform(get("/api/v1/documents/" + id).header("Authorization", "Bearer " + token)).andExpect(jsonPath("$.processingStatus").value("FAILED"));
        mvc.perform(post("/api/v1/documents/" + id + "/retry-processing").header("Authorization", "Bearer " + token))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.processingStatus").value("UPLOADED"));
        mvc.perform(delete("/api/v1/documents/" + id).header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());
    }
    @Test void documentationAndHealthAvailable() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
            .andExpect(jsonPath("$.paths['/api/v1/documents'].post.requestBody.content['multipart/form-data'].encoding.metadata.contentType").value("application/json"))
            .andExpect(jsonPath("$.paths['/api/v1/documents'].post.requestBody.content['multipart/form-data'].schema.properties.file.format").value("binary"));
    }
    @Test void frontendIsPublicWhileDocumentsRemainProtected() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk());
        mvc.perform(get("/index.html")).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("DocuWare")));
        mvc.perform(get("/assets/app.css")).andExpect(status().isOk());
        mvc.perform(get("/assets/app.js")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/documents")).andExpect(status().isUnauthorized());
    }
    @Test void metadataWithoutJsonContentTypeGetsActionableError() throws Exception {
        String token = register();
        var file = new MockMultipartFile("file", "notes.txt", "text/plain", "Spring notes".getBytes(StandardCharsets.UTF_8));
        var metadata = new MockMultipartFile("metadata", "", null, "{\"title\":\"Notes\"}".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/v1/documents").file(file).file(metadata).header("Authorization", "Bearer " + token))
            .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.error").value("UNSUPPORTED_MEDIA_TYPE"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("application/json")));
    }
    @Test void expiredAndWrongIssuerTokensRejected() throws Exception {
        for (String issuer : List.of("docuware", "another-service")) {
            Instant now = Instant.now();
            var claims = JwtClaimsSet.builder().issuer(issuer).subject(UUID.randomUUID().toString())
                .issuedAt(now.minusSeconds(600)).expiresAt(issuer.equals("docuware") ? now.minusSeconds(300) : now.plusSeconds(300)).build();
            String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
            mvc.perform(get("/api/v1/documents").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        }
    }
    @Test void failedStorageDeletionRemainsRecoverable() throws Exception {
        String token = register();
        String id = upload(token, "retry.txt", "text/plain", "Recoverable document".getBytes()).path("id").asText();
        String key = id + ".txt";
        doThrow(new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", "Storage unavailable"))
            .doCallRealMethod().when(storage).delete(key);
        mvc.perform(delete("/api/v1/documents/" + id).header("Authorization", "Bearer " + token)).andExpect(status().isServiceUnavailable());
        assertThat(documents.findById(UUID.fromString(id)).orElseThrow().processingStatus).isEqualTo(ProcessingStatus.DELETING);
        mvc.perform(get("/api/v1/documents/" + id).header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        service.cleanup(UUID.fromString(id));
        assertThat(documents.existsById(UUID.fromString(id))).isFalse();
        assertThat(Files.exists(STORAGE.resolve(key))).isFalse();
    }
}
