package com.docuware;

import com.docuware.ai.*;
import com.docuware.auth.*;
import com.docuware.document.*;
import com.docuware.processing.DocumentProcessor;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class PostgresVectorTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl); r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword); r.add("spring.flyway.enabled", () -> true);
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        r.add("app.storage-root", () -> Path.of("target", "postgres-documents").toAbsolutePath().toString());
    }
    @MockitoBean AiProvider ai;
    @Autowired UserRepository users;
    @Autowired DocumentService service;
    @Autowired DocumentRepository documents;
    @Autowired DocumentProcessor processor;
    @Autowired ChunkRepository chunks;
    @Autowired IntelligenceService intelligence;
    @Autowired TransactionTemplate tx;
    @BeforeEach void provider() {
        when(ai.enabled()).thenReturn(true); when(ai.embeddingModel()).thenReturn("test-embedding"); when(ai.chatModel()).thenReturn("test-chat");
        when(ai.embed(anyList())).thenAnswer(call -> ((List<?>) call.getArgument(0)).stream().map(t -> new float[]{1, 0, 0}).toList());
        when(ai.generate(anyString(), anyString())).thenReturn("Spring Boot provides dependency injection [1].");
    }
    @Test void migrationsIndexingAndOwnerScopedVectorSearch() {
        User alice = users.saveAndFlush(new User("Alice", UUID.randomUUID() + "@test.com", "hash"));
        User bob = users.saveAndFlush(new User("Bob", UUID.randomUUID() + "@test.com", "hash"));
        var a = service.upload(alice.id, new MockMultipartFile("file", "alice.txt", "text/plain", "Spring Boot dependency injection.".getBytes()), null);
        var b = service.upload(bob.id, new MockMultipartFile("file", "bob.txt", "text/plain", "Private Bob notes.".getBytes()), null);
        processor.process(a.id()); processor.process(b.id());
        var matches = chunks.search(alice.id, null, ai.embeddingModel(), new float[]{1, 0, 0}, 5);
        assertThat(matches).hasSize(1); assertThat(matches.getFirst().documentId()).isEqualTo(a.id());
        assertThat(chunks.search(alice.id, b.id(), ai.embeddingModel(), new float[]{1, 0, 0}, 5)).isEmpty();
        assertThat(chunks.search(alice.id, null, "different-model", new float[]{1, 0, 0}, 5)).isEmpty();
        assertThat(chunks.search(alice.id, null, ai.embeddingModel(), new float[]{1, 0}, 5)).isEmpty();
        var answer = intelligence.ask(alice.id, a.id(), "What does Spring Boot provide?");
        assertThat(answer.sources()).hasSize(1); assertThat(answer.answer()).contains("dependency injection");
        intelligence.summary(alice.id, a.id()); intelligence.summary(alice.id, a.id());
        verify(ai, times(2)).generate(anyString(), anyString()); // ask + one cached summary
        service.delete(alice.id, a.id()); service.delete(bob.id, b.id());
        assertThat(chunks.search(alice.id, null, ai.embeddingModel(), new float[]{1, 0, 0}, 5)).isEmpty();
    }
    @Test void claimIsAtomicAndOldWorkersCannotRenewRecoveredJobs() {
        User user = users.saveAndFlush(new User("Worker", UUID.randomUUID() + "@test.com", "hash"));
        var d = service.upload(user.id, new MockMultipartFile("file", "job.txt", "text/plain", "Job content".getBytes()), null);
        UUID oldToken = UUID.randomUUID(), newToken = UUID.randomUUID();
        assertThat(tx.<Integer>execute(s -> documents.claim(d.id(), Instant.now().minusSeconds(700), oldToken))).isEqualTo(1);
        assertThat(tx.<Integer>execute(s -> documents.claim(d.id(), Instant.now(), newToken))).isZero();
        tx.executeWithoutResult(s -> documents.recover(Instant.now().minusSeconds(600)));
        assertThat(tx.<Integer>execute(s -> documents.claim(d.id(), Instant.now(), newToken))).isEqualTo(1);
        assertThat(tx.<Integer>execute(s -> documents.heartbeat(d.id(), oldToken, Instant.now()))).isZero();
        service.delete(user.id, d.id());
    }
}
