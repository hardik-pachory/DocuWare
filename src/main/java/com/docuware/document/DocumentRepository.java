package com.docuware.document;

import java.time.Instant;
import java.util.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface DocumentRepository extends JpaRepository<Document, UUID>, JpaSpecificationExecutor<Document> {
    Optional<Document> findByIdAndUserId(UUID id, UUID userId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Document d where d.id = :id")
    Optional<Document> lockById(@Param("id") UUID id);
    List<Document> findByProcessingStatusOrderByCreatedAtAsc(ProcessingStatus status, Pageable page);
    @Modifying(clearAutomatically = true)
    @Query("update Document d set d.processingStatus = com.docuware.document.ProcessingStatus.PROCESSING, d.processingToken = :token, d.updatedAt = :now, d.version = d.version + 1 where d.id = :id and d.processingStatus = com.docuware.document.ProcessingStatus.UPLOADED")
    int claim(@Param("id") UUID id, @Param("now") Instant now, @Param("token") UUID token);
    @Modifying(clearAutomatically = true)
    @Query("update Document d set d.updatedAt = :now where d.id = :id and d.processingToken = :token and d.processingStatus = com.docuware.document.ProcessingStatus.PROCESSING")
    int heartbeat(@Param("id") UUID id, @Param("token") UUID token, @Param("now") Instant now);
    @Modifying(clearAutomatically = true)
    @Query("update Document d set d.processingStatus = com.docuware.document.ProcessingStatus.UPLOADED, d.processingToken = null, d.version = d.version + 1 where d.processingStatus = com.docuware.document.ProcessingStatus.PROCESSING and d.updatedAt < :cutoff")
    int recover(@Param("cutoff") Instant cutoff);
}
