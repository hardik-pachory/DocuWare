package com.docuware.document;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Page;

public final class DocumentDtos {
    private DocumentDtos() {}
    public record Metadata(@Size(max = 200) String title, @Size(max = 2000) String description,
                           @Size(max = 20) Set<@NotBlank @Size(max = 50) String> tags) {}
    public record View(UUID id, String title, String description, String filename, String contentType,
                       long fileSize, String fileType, Set<String> tags, ProcessingStatus processingStatus,
                       String processingError, Instant createdAt, Instant updatedAt) {
        public static View of(Document d) {
            return new View(d.id, d.title, d.description, d.originalFilename, d.contentType, d.fileSize,
                d.fileType, Set.copyOf(d.tags), d.processingStatus, d.processingError, d.createdAt, d.updatedAt);
        }
    }
    public record PageView<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
        public static PageView<View> of(Page<Document> page) {
            return new PageView<>(page.map(View::of).getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
        }
    }
}
