package com.devpilot.auth;
import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
import com.devpilot.common.BaseEntity;
@Entity @Table(name="refresh_tokens") public class RefreshToken extends BaseEntity {
    @Column(name="user_id") public UUID userId;
    @Column(name="token_hash",length=64) public String tokenHash;
    @Column(name="expires_at") public Instant expiresAt;
    @Column(name="revoked_at") public Instant revokedAt;
}
