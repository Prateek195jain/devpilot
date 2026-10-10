package com.devpilot.repository;

import com.devpilot.ai.EmbeddingProvider;
import com.devpilot.common.Problem;
import com.devpilot.github.*;
import com.devpilot.user.UserAccount;
import jakarta.persistence.EntityManager;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DataJpaTest(showSql = false)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({RepositorySyncService.class, RepositoryService.class, RepositoryAccess.class,
        RepositoryIndexingService.class, RepositorySourceAcquisitionService.class,
        RepositorySourceChunker.class, SourceFilePolicy.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RepositorySyncServiceTest {
    private static final String COMMIT = "a".repeat(40), NEXT = "b".repeat(40);
    @Autowired RepositorySyncService sync;
    @Autowired RepositoryService metadata;
    @Autowired RepositoryAccess access;
    @Autowired RepositoryStore repositories;
    @MockitoSpyBean RepositoryChunkStore chunks;
    @Autowired TransactionTemplate transaction;
    @Autowired EntityManager em;
    @MockitoSpyBean RepositoryIndexingService indexing;
    @MockitoBean IntegrationService integration;
    @MockitoBean SourceControlProvider provider;
    @MockitoBean EmbeddingProvider embeddings;
    UUID owner, repositoryId;

    @BeforeEach void setup() {
        transaction.executeWithoutResult(status -> {
            UserAccount user = new UserAccount();
            user.email = UUID.randomUUID() + "@example.com";
            user.name = "Sync test";
            user.passwordHash = "synthetic";
            user.role = "USER";
            em.persist(user);
            owner = user.id;
            TrackedRepository repository = new TrackedRepository();
            repository.ownerId = owner;
            repository.githubId = 42L;
            repository.fullName = "test/sync";
            repository.defaultBranch = "old-main";
            em.persist(repository);
            repositoryId = repository.id;
        });
        authenticate(owner);
        when(integration.token(owner)).thenReturn("token");
        when(provider.repository("token", "test/sync")).thenReturn(
                new SourceControlProvider.Repo(42L, "test/sync", "Updated description", "release/current", "Java"));
        when(provider.readme("token", "test/sync")).thenReturn("Updated README");
        when(provider.latestCommitSha("token", "test/sync", "release/current")).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertMetadata(); // The metadata transaction has already committed.
            return COMMIT.toUpperCase(Locale.ROOT);
        });
        when(provider.commitTree(eq("token"), eq("test/sync"), anyString())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return "c".repeat(40);
        });
        when(provider.tree("token", "test/sync", "c".repeat(40))).thenReturn(List.of(
                new SourceControlProvider.TreeEntry("README.md", "blob", "100644", "d".repeat(40), 2)));
        when(provider.blob("token", "test/sync", "d".repeat(40))).thenReturn(new byte[] {'o', 'k'});
        when(embeddings.model()).thenReturn("test-model");
        when(embeddings.dimensions()).thenReturn(768);
        when(embeddings.embedDocument(anyString(), anyString())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return new EmbeddingProvider.Embedding("test-model", 768, Collections.nCopies(768, 0.25));
        });
    }

    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        transaction.executeWithoutResult(status -> {
            chunks.deleteCommit(repositoryId, COMMIT);
            chunks.deleteCommit(repositoryId, NEXT);
            repositories.deleteById(repositoryId);
            em.remove(em.find(UserAccount.class, owner));
        });
    }

    private void authenticate(UUID user) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(user.toString(), null, List.of()));
        SecurityContextHolder.setContext(context);
    }

    private TrackedRepository stored() { return repositories.findById(repositoryId).orElseThrow(); }

    private void assertMetadata() {
        var repository = stored();
        assertEquals("Updated description", repository.description);
        assertEquals("release/current", repository.defaultBranch);
        assertEquals("Java", repository.language);
        assertEquals("Updated README", repository.readme);
        assertEquals(42L, repository.githubId);
        assertNotNull(repository.syncedAt);
    }

    @Test void endpointSyncsMetadataThenIndexesLatestDefaultBranchCommitAndReturnsPublishedSha() {
        var controller = new RepositoryController(repositories, access, integration, provider, sync, null);
        var result = controller.sync(repositoryId);
        assertEquals(COMMIT, stored().latestIndexedCommitSha);
        assertEquals(COMMIT, result.latestIndexedCommitSha());
        assertMetadata();
        var order = inOrder(provider, indexing);
        order.verify(provider).repository("token", "test/sync");
        order.verify(provider).readme("token", "test/sync");
        order.verify(provider).latestCommitSha("token", "test/sync", "release/current");
        order.verify(indexing).index(repositoryId, COMMIT);
        assertEquals(1, chunks.count());
        verify(provider).commitTree("token", "test/sync", COMMIT);
    }

    @Test void samePublishedCommitSkipsAcquisitionAndEmbeddingsButStillSyncsMetadata() {
        sync.sync(repositoryId);
        clearInvocations(provider, indexing, embeddings);
        assertEquals(COMMIT, sync.sync(repositoryId).latestIndexedCommitSha());
        verify(provider).repository("token", "test/sync");
        verify(provider).readme("token", "test/sync");
        verify(indexing, never()).index(any(), any());
        verify(provider, never()).commitTree(any(), any(), any());
        verifyNoInteractions(embeddings);
        assertMetadata();
    }

    @Test void indexingFailurePreservesPreviousPublishedCommitAndChunksAndCommittedMetadata() {
        sync.sync(repositoryId);
        var ids = chunks.findAll().stream().map(row -> row.id).toList();
        doReturn(NEXT).when(provider).latestCommitSha("token", "test/sync", "release/current");
        when(embeddings.embedDocument(anyString(), anyString())).thenThrow(new IllegalStateException("synthetic"));
        assertIndexingFailure();
        assertEquals(COMMIT, stored().latestIndexedCommitSha);
        assertEquals(ids, chunks.findAll().stream().map(row -> row.id).toList());
        verify(indexing).index(repositoryId, NEXT);
    }

    @Test void firstIndexingFailureLeavesPointerNullAndNoChunks() {
        when(embeddings.embedDocument(anyString(), anyString())).thenThrow(new IllegalStateException("synthetic"));
        assertIndexingFailure();
        assertNull(stored().latestIndexedCommitSha);
        assertEquals(0, chunks.count());
    }

    @Test void publicationFailureRollsBackIndexButKeepsMetadata() {
        sync.sync(repositoryId);
        doReturn(NEXT).when(provider).latestCommitSha("token", "test/sync", "release/current");
        transaction.executeWithoutResult(status -> {
            var repository = repositories.findById(repositoryId).orElseThrow();
            repository.description = "Description before sync";
        });
        // A failure after inserting a chunk must roll back both chunk and pointer publication.
        doAnswer(invocation -> {
            List<RepositoryChunk> prepared = invocation.getArgument(0);
            chunks.saveAndFlush(prepared.getFirst());
            throw new IllegalStateException("Synthetic publication failure");
        }).when(chunks).saveAllAndFlush(any());
        assertIndexingFailure();
        assertEquals(COMMIT, stored().latestIndexedCommitSha);
        assertEquals(1, chunks.count());
    }

    private void assertIndexingFailure() {
        Problem failure = assertThrows(Problem.class, () -> sync.sync(repositoryId));
        assertEquals(HttpStatus.BAD_GATEWAY, failure.status);
        assertTrue(failure.getMessage().contains("metadata synced"));
        assertTrue(failure.getMessage().contains("indexing failed"));
        assertMetadata();
    }

    @Test void shaResolutionFailureKeepsMetadataAndDoesNotIndex() {
        doThrow(Problem.upstream("GitHub")).when(provider).latestCommitSha("token", "test/sync", "release/current");
        assertIndexingFailure();
        assertNull(stored().latestIndexedCommitSha);
        verifyNoInteractions(indexing, embeddings);
    }

    @Test void movingRefOrShortShaCannotReachIndexer() {
        for (String invalid : Arrays.asList("main", "abc1234", null)) {
            doReturn(invalid).when(provider).latestCommitSha("token", "test/sync", "release/current");
            assertIndexingFailure();
        }
        assertNull(stored().latestIndexedCommitSha);
        verifyNoInteractions(indexing, embeddings);
    }

    @Test void failedMetadataSyncDoesNotResolveOrIndexAndRollsBackMetadata() {
        when(provider.repository("token", "test/sync")).thenThrow(Problem.upstream("GitHub"));
        assertThrows(Problem.class, () -> sync.sync(repositoryId));
        assertEquals("old-main", stored().defaultBranch);
        assertNull(stored().syncedAt);
        verify(provider, never()).latestCommitSha(any(), any(), any());
        verifyNoInteractions(indexing, embeddings);
    }

    @Test void anotherOwnersRepositoryIsRejectedBeforeCredentialsOrNetwork() {
        authenticate(UUID.randomUUID());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(Problem.class, () -> sync.sync(repositoryId)).status);
        verifyNoInteractions(provider, integration, indexing, embeddings);
        assertNull(stored().latestIndexedCommitSha);
    }

    @Test void ambientTransactionIsRejectedBeforeMetadataSync() {
        assertThrows(IllegalTransactionStateException.class,
                () -> transaction.execute(status -> sync.sync(repositoryId)));
        verifyNoInteractions(provider, integration, indexing, embeddings);
    }

    /** Remove the existing fixture so the test exercises creation of a new record. */
    private void prepareImport() {
        transaction.executeWithoutResult(status -> repositories.deleteById(repositoryId));
        doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            var imported = repositories.findByOwnerId(owner, Pageable.unpaged()).getContent();
            assertEquals(1, imported.size());
            repositoryId = imported.getFirst().id;
            assertMetadata(); // Creation and initial metadata are committed before SHA resolution.
            assertNull(stored().latestIndexedCommitSha);
            return COMMIT.toUpperCase(Locale.ROOT);
        }).when(provider).latestCommitSha("token", "test/sync", "release/current");
    }

    @Test void addEndpointCreatesAndIndexesRepositoryUsingSameImmutableShaPathAsSync() {
        prepareImport();
        var controller = new RepositoryController(repositories, access, integration, provider, sync, null);
        var result = controller.create(new RepositoryController.Import("test/sync"));
        assertEquals(repositoryId, result.id());
        assertEquals(owner, result.ownerId());
        assertEquals(COMMIT, result.latestIndexedCommitSha());
        assertEquals(COMMIT, stored().latestIndexedCommitSha);
        assertMetadata();
        var rows = chunks.findAll();
        assertEquals(1, rows.size());
        assertEquals(repositoryId, rows.getFirst().repositoryId);
        assertEquals(COMMIT, rows.getFirst().commitSha);
        assertEquals(40, rows.getFirst().commitSha.length());
        var order = inOrder(provider, indexing);
        order.verify(provider).repository("token", "test/sync");
        order.verify(provider).readme("token", "test/sync");
        order.verify(provider).latestCommitSha("token", "test/sync", "release/current");
        order.verify(indexing).index(repositoryId, COMMIT);
        verify(provider, times(1)).repository("token", "test/sync");
        verify(provider, times(1)).readme("token", "test/sync");
        verify(provider).commitTree("token", "test/sync", COMMIT);

        // A different signed-in user cannot access or sync the newly imported repository.
        authenticate(UUID.randomUUID());
        clearInvocations(provider, integration, indexing, embeddings);
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(Problem.class, () -> access.require(repositoryId)).status);
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(Problem.class, () -> sync.sync(repositoryId)).status);
        verifyNoInteractions(provider, integration, indexing, embeddings);
        assertEquals(COMMIT, stored().latestIndexedCommitSha);
    }

    @Test void addPartialEmbeddingFailureKeepsCreatedMetadataWithoutPublishingChunksOrSha() {
        prepareImport();
        when(provider.tree("token", "test/sync", "c".repeat(40))).thenReturn(List.of(
                new SourceControlProvider.TreeEntry("README.md", "blob", "100644", "d".repeat(40), 2),
                new SourceControlProvider.TreeEntry("other.md", "blob", "100644", "d".repeat(40), 2)));
        when(embeddings.embedDocument(anyString(), anyString()))
                .thenReturn(new EmbeddingProvider.Embedding("test-model", 768, Collections.nCopies(768, 0.25)))
                .thenThrow(new IllegalStateException("Synthetic second embedding failure"));
        assertImportIndexingFailure();
        verify(embeddings, times(2)).embedDocument(anyString(), anyString());
    }

    @Test void addPublicationFailureRollsBackInsertedChunksButKeepsCreatedRepository() {
        prepareImport();
        doAnswer(invocation -> {
            List<RepositoryChunk> prepared = invocation.getArgument(0);
            chunks.saveAndFlush(prepared.getFirst());
            throw new IllegalStateException("Synthetic import publication failure");
        }).when(chunks).saveAllAndFlush(any());
        assertImportIndexingFailure();
    }

    private void assertImportIndexingFailure() {
        Problem failure = assertThrows(Problem.class, () -> sync.importRepo("test/sync"));
        assertEquals(HttpStatus.BAD_GATEWAY, failure.status);
        assertTrue(failure.getMessage().contains("Repository imported"));
        assertTrue(failure.getMessage().contains("indexing failed"));
        assertTrue(failure.getMessage().contains("repository was saved"));
        assertMetadata();
        assertEquals(owner, stored().ownerId);
        assertNull(stored().latestIndexedCommitSha);
        assertEquals(0, chunks.count());
    }

    @Test void addGitHubFailureDoesNotCreateRepositoryOrStartIndexing() {
        prepareImport();
        when(provider.repository("token", "test/sync")).thenThrow(Problem.upstream("GitHub"));
        assertThrows(Problem.class, () -> sync.importRepo("test/sync"));
        assertTrue(repositories.findByOwnerId(owner, Pageable.unpaged()).isEmpty());
        verify(provider, never()).latestCommitSha(any(), any(), any());
        verifyNoInteractions(indexing, embeddings);
    }

    @Test void addUsesCurrentUsersCredentialsAndPreservesAuthFailure() {
        prepareImport();
        when(integration.token(owner)).thenThrow(Problem.unauthorized());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(Problem.class, () -> sync.importRepo("test/sync")).status);
        verify(integration).token(owner);
        verifyNoInteractions(provider, indexing, embeddings);
        assertTrue(repositories.findByOwnerId(owner, Pageable.unpaged()).isEmpty());
    }

    @Test void addRejectsAmbientTransactionBeforeCreatingRepository() {
        assertThrows(IllegalTransactionStateException.class,
                () -> transaction.execute(status -> sync.importRepo("test/sync")));
        verifyNoInteractions(provider, integration, indexing, embeddings);
    }
}
