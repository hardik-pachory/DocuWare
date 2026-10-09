package com.docuware.document;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "documents")
public class Document {
    @Id public UUID id;
    @Column(nullable = false) public UUID userId;
    @Column(nullable = false) public String originalFilename;
    @Column(nullable = false, unique = true, length = 100) public String storageKey;
    @Column(nullable = false, length = 200) public String title;
    @Column(length = 2000) public String description;
    @Column(nullable = false, length = 150) public String contentType;
    @Column(nullable = false) public long fileSize;
    @Column(nullable = false, length = 10) public String fileType;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    public ProcessingStatus processingStatus;
    public UUID processingToken;
    @Column(length = 200) public String processingError;
    @Column(columnDefinition = "text") public String extractedText;
    @Column(columnDefinition = "text") public String summary;
    @Column(length = 100) public String summaryModel;
    @Column(length = 100) public String embeddingModel;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "document_tags", joinColumns = @JoinColumn(name = "document_id"))
    @Column(name = "tag", length = 50, nullable = false)
    public Set<String> tags = new LinkedHashSet<>();
    @Column(nullable = false) public Instant createdAt;
    @Column(nullable = false) public Instant updatedAt;
    @Version public long version;
    public Document() {}
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
