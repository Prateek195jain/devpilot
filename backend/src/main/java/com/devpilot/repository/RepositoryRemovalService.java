package com.devpilot.repository;

import com.devpilot.common.CurrentUser;
import com.devpilot.common.Problem;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Removes local tracking and owned data only; never contacts a source-control provider. */
@Service
public class RepositoryRemovalService {
    private static final Logger log = LoggerFactory.getLogger(RepositoryRemovalService.class);
    private static final List<String> DELETIONS = List.of(
            "delete from messages where conversation_id in (select id from conversations where repository_id = ?)",
            "delete from conversations where repository_id = ?",
            "delete from technical_debt where repository_id = ?",
            "delete from findings where repository_id = ?",
            "delete from analyses where repository_id = ?",
            "delete from engineering_memories where repository_id = ?",
            "delete from generated_documents where repository_id = ?",
            "delete from repository_chunks where repository_id = ?",
            "delete from repositories where id = ?");
    private final RepositoryAccess access;
    private final RepositoryStore repositories;
    private final JdbcTemplate jdbc;

    public RepositoryRemovalService(RepositoryAccess access, RepositoryStore repositories, JdbcTemplate jdbc) {
        this.access = access;
        this.repositories = repositories;
        this.jdbc = jdbc;
    }

    @Transactional
    public void remove(UUID repositoryId) {
        access.require(repositoryId);
        // Serialize with index publication and other repository writers; recheck the owner.
        TrackedRepository repository = repositories.lock(repositoryId).orElseThrow(Problem::notFound);
        if (!CurrentUser.id().equals(repository.ownerId)) throw Problem.notFound();
        try {
            for (String sql : DELETIONS) jdbc.update(sql, repositoryId);
        } catch (RuntimeException failure) {
            log.warn("Repository removal failed for {} ({})", repositoryId, failure.getClass().getSimpleName());
            throw new Problem(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Repository removal failed. No data was removed. Refresh and retry; if the problem continues, contact support.");
        }
    }
}
