package com.docuware.auth;

import com.docuware.exception.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;
    private final Duration ttl;
    private final String dummyHash;
    public AuthService(UserRepository users, PasswordEncoder passwords, JwtEncoder encoder,
                       @Value("${app.jwt-ttl}") Duration ttl) {
        this.users = users; this.passwords = passwords; this.encoder = encoder; this.ttl = ttl;
        this.dummyHash = passwords.encode("dummy-account-password");
    }
    public Token register(AuthController.Register request) {
        validatePassword(request.password());
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (users.findByEmail(email).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_EXISTS", "Email is already registered");
        }
        return token(users.saveAndFlush(new User(request.name().trim(), email, passwords.encode(request.password()))));
    }
    public Token login(AuthController.Login request) {
        User user = users.findByEmail(request.email().trim().toLowerCase(Locale.ROOT)).orElse(null);
        boolean matches = passwords.matches(request.password(), user == null ? dummyHash : user.passwordHash);
        if (user == null || !matches) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password");
        }
        return token(user);
    }
    private void validatePassword(String value) {
        if (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD", "Password must not exceed 72 UTF-8 bytes");
        }
    }
    private Token token(User user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("docuware").subject(user.id.toString())
            .issuedAt(now).expiresAt(now.plus(ttl)).build();
        String jwt = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new Token(jwt, "Bearer", ttl.toSeconds(), user.id, user.name);
    }
    public record Token(String accessToken, String tokenType, long expiresIn, java.util.UUID userId, String name) {}
}
