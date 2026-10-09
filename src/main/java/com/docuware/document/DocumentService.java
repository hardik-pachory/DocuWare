package com.docuware.document;

import com.docuware.exception.ApiException;
import com.docuware.storage.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.slf4j.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import jakarta.persistence.criteria.JoinType;

@Service
public class DocumentService {
    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    private final DocumentRepository documents;
    private final StorageService storage;
    private final FileValidator validator;
    private final TransactionTemplate tx;
    public DocumentService(DocumentRepository documents, StorageService storage, FileValidator validator, TransactionTemplate tx) {
        this.documents = documents; this.storage = storage; this.validator = validator; this.tx = tx;
    }
    public DocumentDtos.View upload(UUID owner, MultipartFile file, DocumentDtos.Metadata metadata) {
        FileValidator.Validated validated = validator.validate(file);
        Document d = new Document(); d.id = UUID.randomUUID(); d.userId = owner;
        d.originalFilename = validated.filename(); d.fileType = validated.extension();
        d.storageKey = d.id + "." + d.fileType; d.contentType = validated.contentType(); d.fileSize = file.getSize();
        d.title = metadata == null || metadata.title() == null ? d.originalFilename : metadata.title().trim();
        if (d.title.isBlank()) throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_TITLE", "Title must not be blank");
        if (metadata != null) { d.description = metadata.description(); if (metadata.tags() != null) d.tags = normalizeTags(metadata.tags()); }
        d.createdAt = Instant.now(); d.updatedAt = d.createdAt; d.processingStatus = ProcessingStatus.UPLOADED;
        try { storage.store(d.storageKey, file.getInputStream()); }
        catch (IOException e) { throw new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", "Unable to read upload"); }
        try {
            DocumentDtos.View view = tx.execute(status -> DocumentDtos.View.of(documents.saveAndFlush(d)));
            log.info("Document uploaded id={} owner={} bytes={}", d.id, owner, d.fileSize);
            return view;
        } catch (RuntimeException e) {
            try { storage.delete(d.storageKey); } catch (RuntimeException cleanup) { log.error("Upload compensation failed key={}", d.storageKey, cleanup); }
            throw e;
        }
    }
    public Document owned(UUID owner, UUID id) {
        Document d = documents.findByIdAndUserId(id, owner).orElseThrow(ApiException::notFound);
        if (d.processingStatus == ProcessingStatus.DELETING) throw ApiException.notFound();
        return d;
    }
    public DocumentDtos.View get(UUID owner, UUID id) { return DocumentDtos.View.of(owned(owner, id)); }
    public DocumentDtos.PageView<DocumentDtos.View> list(UUID owner, int page, int size, String sort,
                                                       String type, String tag, Instant after) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid pagination");
        String[] order = sort.split(",", -1);
        if (order.length > 2 || !Set.of("createdAt", "updatedAt", "title", "fileSize").contains(order[0])) throw new IllegalArgumentException("Invalid sort");
        Sort.Direction direction = order.length == 2 ? Sort.Direction.fromString(order[1]) : Sort.Direction.DESC;
        Specification<Document> spec = (root, query, cb) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            predicates.add(cb.equal(root.get("userId"), owner));
            predicates.add(cb.notEqual(root.get("processingStatus"), ProcessingStatus.DELETING));
            if (type != null) predicates.add(cb.equal(root.get("fileType"), type.toLowerCase(Locale.ROOT)));
            if (tag != null) predicates.add(cb.isMember(tag.trim().toLowerCase(Locale.ROOT), root.get("tags")));
            if (after != null) predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), after));
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        return DocumentDtos.PageView.of(documents.findAll(spec, PageRequest.of(page, size, Sort.by(direction, order[0]).and(Sort.by("id")))));
    }
    public DocumentDtos.View patch(UUID owner, UUID id, DocumentDtos.Metadata metadata) {
        return tx.execute(status -> {
            Document d = lockOwned(owner, id);
            if (metadata.title() != null) {
                if (metadata.title().isBlank()) throw new IllegalArgumentException("Title must not be blank");
                d.title = metadata.title().trim();
            }
            if (metadata.description() != null) d.description = metadata.description();
            if (metadata.tags() != null) d.tags = normalizeTags(metadata.tags());
            return DocumentDtos.View.of(documents.saveAndFlush(d));
        });
    }
    public void delete(UUID owner, UUID id) {
        tx.executeWithoutResult(status -> {
            Document d = documents.lockById(id).orElseThrow(ApiException::notFound);
            if (!d.userId.equals(owner)) throw ApiException.notFound();
            d.processingStatus = ProcessingStatus.DELETING; documents.saveAndFlush(d);
        });
        cleanup(id);
    }
    public void cleanup(UUID id) {
        tx.executeWithoutResult(status -> {
            Document d = documents.lockById(id).orElse(null);
            if (d == null || d.processingStatus != ProcessingStatus.DELETING) return;
            storage.delete(d.storageKey);
            documents.delete(d); documents.flush();
            log.info("Document deleted id={}", id);
        });
    }
    public DocumentDtos.View retry(UUID owner, UUID id) {
        return tx.execute(status -> {
            Document d = lockOwned(owner, id);
            if (d.processingStatus != ProcessingStatus.FAILED && d.processingStatus != ProcessingStatus.COMPLETED) {
                throw ApiException.conflict("Document is already queued or processing");
            }
            d.processingStatus = ProcessingStatus.UPLOADED; d.processingError = null;
            d.summary = null; d.summaryModel = null; d.embeddingModel = null;
            return DocumentDtos.View.of(documents.saveAndFlush(d));
        });
    }
    private Document lockOwned(UUID owner, UUID id) {
        Document d = documents.lockById(id).orElseThrow(ApiException::notFound);
        if (!d.userId.equals(owner) || d.processingStatus == ProcessingStatus.DELETING) throw ApiException.notFound();
        return d;
    }
    private Set<String> normalizeTags(Set<String> tags) {
        Set<String> result = new LinkedHashSet<>(); tags.forEach(t -> result.add(t.trim().toLowerCase(Locale.ROOT))); return result;
    }
}
