package com.docuware.processing;

import com.docuware.ai.*;
import com.docuware.document.*;
import java.time.*;
import java.util.*;
import org.slf4j.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class DocumentProcessor {
    private static final Logger log = LoggerFactory.getLogger(DocumentProcessor.class);
    private final DocumentRepository documents;
    private final DocumentService service;
    private final DocumentTextExtractor extractor;
    private final TextChunker chunker;
    private final ChunkRepository chunks;
    private final AiProvider ai;
    private final TransactionTemplate tx;
    private final boolean scheduled;
    public DocumentProcessor(DocumentRepository documents, DocumentService service, DocumentTextExtractor extractor,
            TextChunker chunker, ChunkRepository chunks, AiProvider ai, TransactionTemplate tx,
            @org.springframework.beans.factory.annotation.Value("${app.processing-enabled:true}") boolean scheduled) {
        this.documents = documents; this.service = service; this.extractor = extractor; this.chunker = chunker;
        this.chunks = chunks; this.ai = ai; this.tx = tx; this.scheduled = scheduled;
    }
    @Scheduled(fixedDelayString = "${app.processing-delay-ms:3000}")
    public void tick() {
        if (!scheduled) return;
        try {
            // Ten minutes exceeds one bounded provider call; active work renews its lease between batches.
            tx.executeWithoutResult(status -> documents.recover(Instant.now().minus(Duration.ofMinutes(10))));
            for (Document d : documents.findByProcessingStatusOrderByCreatedAtAsc(ProcessingStatus.DELETING, PageRequest.of(0, 5))) {
                try { service.cleanup(d.id); } catch (Exception e) { log.warn("Deletion will retry id={}", d.id); }
            }
            for (Document d : documents.findByProcessingStatusOrderByCreatedAtAsc(ProcessingStatus.UPLOADED, PageRequest.of(0, 2))) process(d.id);
        } catch (Exception e) { log.error("Processing scheduler failed", e); }
    }
    public void process(UUID id) {
        UUID token = UUID.randomUUID();
        Boolean claimed = tx.execute(status -> documents.claim(id, Instant.now(), token) == 1);
        if (!Boolean.TRUE.equals(claimed)) return;
        Document document = documents.findById(id).orElse(null);
        if (document == null) return;
        try {
            String text = extractor.extract(document);
            List<String> content = chunker.split(text);
            List<float[]> embeddings = ai.enabled() ? new ArrayList<>() : null;
            if (embeddings != null) {
                for (int i = 0; i < content.size(); i += 16) {
                    if (!heartbeat(id, token)) return;
                    List<float[]> batch = ai.embed(content.subList(i, Math.min(i + 16, content.size())));
                    if (!embeddings.isEmpty() && batch.getFirst().length != embeddings.getFirst().length) {
                        throw new IllegalStateException("Embedding dimensionality changed during indexing");
                    }
                    embeddings.addAll(batch);
                }
            }
            Boolean saved = tx.execute(status -> {
                Document current = documents.lockById(id).orElse(null);
                if (!active(current, token)) return false;
                chunks.replace(id, content, embeddings);
                current.extractedText = text;
                current.embeddingModel = ai.enabled() ? ai.embeddingModel() : null;
                current.processingStatus = ProcessingStatus.COMPLETED; current.processingError = null;
                documents.saveAndFlush(current);
                return true;
            });
            if (Boolean.TRUE.equals(saved)) log.info("Document processed id={} chunks={}", id, content.size());
        } catch (Exception e) {
            log.warn("Document processing failed id={} cause={}", id, e.getClass().getSimpleName());
            tx.executeWithoutResult(status -> {
                Document current = documents.lockById(id).orElse(null);
                if (!active(current, token)) return;
                current.processingStatus = ProcessingStatus.FAILED;
                current.processingError = "Text extraction or AI indexing failed. Check the document and provider, then retry.";
                documents.saveAndFlush(current);
            });
        }
    }
    private boolean active(Document d, UUID token) {
        return d != null && d.processingStatus == ProcessingStatus.PROCESSING && token.equals(d.processingToken);
    }
    private boolean heartbeat(UUID id, UUID token) {
        return Boolean.TRUE.equals(tx.execute(status -> documents.heartbeat(id, token, Instant.now()) == 1));
    }
}
