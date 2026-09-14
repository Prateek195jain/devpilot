package com.devpilot.github;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface IntegrationStore extends JpaRepository<GitHubIntegration,UUID> {
    Optional<GitHubIntegration> findByUserId(UUID userId);
}
