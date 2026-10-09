package com.docuware.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class IntelligenceController {
    private final IntelligenceService service;
    public IntelligenceController(IntelligenceService service) { this.service = service; }
    @GetMapping("/search")
    public List<ChunkRepository.Match> search(@AuthenticationPrincipal Jwt jwt, @RequestParam String q,
                                             @RequestParam(defaultValue = "5") int limit) {
        return service.search(UUID.fromString(jwt.getSubject()), q, limit);
    }
    @PostMapping("/documents/{id}/summary")
    public IntelligenceService.Summary summary(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.summary(UUID.fromString(jwt.getSubject()), id);
    }
    @PostMapping("/documents/{id}/ask")
    public IntelligenceService.Answer ask(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Question request) {
        return service.ask(UUID.fromString(jwt.getSubject()), id, request.question());
    }
    public record Question(@NotBlank @Size(max = 1000) String question) {}
}
