package com.docuware.auth;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {
    @Id public UUID id;
    @Column(nullable = false, length = 100) public String name;
    @Column(nullable = false, unique = true, length = 254) public String email;
    @Column(name = "password_hash", nullable = false, length = 100) public String passwordHash;
    @Column(nullable = false) public Instant createdAt;
    protected User() {}
    public User(String name, String email, String passwordHash) {
        this.id = UUID.randomUUID(); this.name = name; this.email = email;
        this.passwordHash = passwordHash; this.createdAt = Instant.now();
    }
}
