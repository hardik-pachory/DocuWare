package com.docuware.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@io.swagger.v3.oas.annotations.security.SecurityRequirements
public class AuthController {
    private final AuthService service;
    public AuthController(AuthService service) { this.service = service; }
    @PostMapping("/register") @ResponseStatus(HttpStatus.CREATED)
    public AuthService.Token register(@Valid @RequestBody Register request) { return service.register(request); }
    @PostMapping("/login")
    public AuthService.Token login(@Valid @RequestBody Login request) { return service.login(request); }
    public record Register(@NotBlank @Size(max = 100) String name,
                           @NotBlank @Email @Size(max = 254) String email,
                           @NotBlank @Size(min = 12, max = 72) String password) {}
    public record Login(@NotBlank @Email @Size(max = 254) String email,
                        @NotBlank @Size(max = 72) String password) {}
}
