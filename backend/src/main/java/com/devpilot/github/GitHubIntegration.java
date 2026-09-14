package com.devpilot.github;
import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
import com.devpilot.common.BaseEntity;
@Entity @Table(name="github_integrations") public class GitHubIntegration extends BaseEntity {
    @Column(name="user_id") public UUID userId;
    @Column(name="login",length=100) public String login;
    @Column(name="encrypted_token",columnDefinition="text") public String encryptedToken;
}
