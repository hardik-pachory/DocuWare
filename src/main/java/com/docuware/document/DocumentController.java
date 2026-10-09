package com.docuware.document;

import com.docuware.storage.StorageService;
import jakarta.validation.Valid;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {
    private final DocumentService service;
    private final StorageService storage;
    public DocumentController(DocumentService service, StorageService storage) { this.service = service; this.storage = storage; }
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @io.swagger.v3.oas.annotations.Operation(requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
        content = @io.swagger.v3.oas.annotations.media.Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
            encoding = @io.swagger.v3.oas.annotations.media.Encoding(name = "metadata", contentType = MediaType.APPLICATION_JSON_VALUE))))
    public ResponseEntity<DocumentDtos.View> upload(@AuthenticationPrincipal Jwt jwt, @RequestPart("file") MultipartFile file,
            @Valid @RequestPart(value = "metadata", required = false) DocumentDtos.Metadata metadata) {
        var view = service.upload(owner(jwt), file, metadata);
        return ResponseEntity.created(URI.create("/api/v1/documents/" + view.id())).body(view);
    }
    @GetMapping
    public DocumentDtos.PageView<DocumentDtos.View> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort, @RequestParam(required = false) String fileType,
            @RequestParam(required = false) String tag, @RequestParam(required = false) String createdAfter) {
        Instant after = null;
        if (createdAfter != null) {
            try { after = createdAfter.length() == 10 ? LocalDate.parse(createdAfter).atStartOfDay(ZoneOffset.UTC).toInstant() : Instant.parse(createdAfter); }
            catch (DateTimeException e) { throw new IllegalArgumentException("Invalid createdAfter"); }
        }
        return service.list(owner(jwt), page, size, sort, fileType, tag, after);
    }
    @GetMapping("/{id}")
    public DocumentDtos.View get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { return service.get(owner(jwt), id); }
    @PatchMapping("/{id}")
    public DocumentDtos.View patch(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody DocumentDtos.Metadata metadata) {
        return service.patch(owner(jwt), id, metadata);
    }
    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Document d = service.owned(owner(jwt), id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(d.contentType)).contentLength(d.fileSize)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(d.originalFilename, StandardCharsets.UTF_8).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "no-store").body(storage.load(d.storageKey));
    }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { service.delete(owner(jwt), id); }
    @PostMapping("/{id}/retry-processing") @ResponseStatus(HttpStatus.ACCEPTED)
    public DocumentDtos.View retry(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { return service.retry(owner(jwt), id); }
    private UUID owner(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
}
