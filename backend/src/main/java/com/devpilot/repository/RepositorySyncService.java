package com.devpilot.repository;

import com.devpilot.common.Problem;
import com.devpilot.ai.EmbeddingFailure;
import com.devpilot.common.CurrentUser;
import com.devpilot.github.IntegrationService;
import com.devpilot.github.SourceControlProvider;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Keeps metadata commit and atomic index publication in separate transactions. */
@Service
public class RepositorySyncService {
    private static final Logger log = LoggerFactory.getLogger(RepositorySyncService.class);
    private final RepositoryService metadata;
    private final RepositoryAccess access;
    private final IntegrationService integration;
    private final SourceControlProvider provider;
    private final RepositoryIndexingService indexing;

    public RepositorySyncService(RepositoryService metadata, RepositoryAccess access,
            IntegrationService integration, SourceControlProvider provider, RepositoryIndexingService indexing) {
        this.metadata = metadata;
        this.access = access;
        this.integration = integration;
        this.provider = provider;
        this.indexing = indexing;
    }

    public TrackedRepositoryView importRepo(String fullName) {
        requireNoTransaction();
        TrackedRepositoryView repository = metadata.importRepo(CurrentUser.id(), fullName);
        return indexSyncedRepository(repository.id(),
                "Repository imported, but repository indexing failed. The repository was saved. Refresh the repository list and retry Sync; check integration and embedding settings.");
    }

    public TrackedRepositoryView sync(UUID repositoryId) {
        requireNoTransaction();
        // This proxied call must commit successfully before any indexing work starts.
        metadata.sync(repositoryId);
        return indexSyncedRepository(repositoryId,
                "Repository metadata synced, but repository indexing failed. Previous repository knowledge was preserved. Retry Sync; check integration and embedding settings.");
    }

    private void requireNoTransaction() {
        // Avoid an outer transaction synchronization scope retaining a stale JPA read
        // context across metadata commit and index publication, even with NEVER.
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalTransactionStateException("Repository sync must run outside a transaction");
    }

    /** Shared by import and Sync after their metadata transaction has committed. */
    private TrackedRepositoryView indexSyncedRepository(UUID repositoryId, String failureMessage) {
        TrackedRepository repository = access.require(repositoryId);
        try {
            String commit = provider.latestCommitSha(integration.token(repository.ownerId),
                    repository.fullName, repository.defaultBranch);
            if (commit == null || !commit.matches("[a-fA-F0-9]{40}"))
                throw Problem.upstream("GitHub invalid commit SHA");
            commit = commit.toLowerCase(Locale.ROOT);
            if (!commit.equals(repository.latestIndexedCommitSha)) indexing.index(repositoryId, commit);
        } catch (RuntimeException failure) {
            // Preserve access/auth error semantics if ownership or credentials changed.
            if (failure instanceof Problem problem
                    && (problem.status == HttpStatus.NOT_FOUND || problem.status == HttpStatus.UNAUTHORIZED
                        || problem.status == HttpStatus.FORBIDDEN)) throw problem;
            if (failure instanceof EmbeddingFailure embedding) {
                log.warn("Repository indexing failed after metadata sync for {} {}", repositoryId, embedding.diagnostic());
                throw new Problem(HttpStatus.BAD_GATEWAY, "Repository indexing failed. Please retry shortly.");
            }
            log.warn("Repository indexing failed after metadata sync for {} ({})", repositoryId,
                    failure.getClass().getSimpleName());
            throw new Problem(HttpStatus.BAD_GATEWAY, failureMessage);
        }
        // Reload after publication so the existing response includes the usable index SHA.
        return TrackedRepositoryView.of(access.require(repositoryId));
    }
}
