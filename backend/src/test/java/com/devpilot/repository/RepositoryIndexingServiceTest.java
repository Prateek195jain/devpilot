package com.devpilot.repository;

import com.devpilot.ai.*;
import com.devpilot.common.*;
import com.devpilot.user.UserAccount;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
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

@DataJpaTest(showSql = false, properties = {"app.source-chunking.max-chars=4", "app.source-chunking.overlap-chars=0"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({RepositoryIndexingService.class, RepositorySourceChunker.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RepositoryIndexingServiceTest {
    private static final String COMMIT = "a".repeat(40);
    @Autowired RepositoryIndexingService service;
    @Autowired RepositorySourceChunker chunker;
    @Autowired RepositoryStore repositories;
    @Autowired TransactionTemplate transaction;
    @Autowired EntityManager em;
    @MockitoBean RepositorySourceAcquisitionService acquisition;
    @MockitoBean EmbeddingProvider embeddings;
    @MockitoSpyBean RepositoryChunkStore chunks;
    UUID repositoryId;
    UUID owner;
    RepositorySources sources;
    List<String> calls;

    @BeforeEach void setup() {
        transaction.executeWithoutResult(status -> {
            UserAccount user = new UserAccount();
            user.email = UUID.randomUUID() + "@example.com";
            user.name = "Indexing test";
            user.passwordHash = "synthetic";
            user.role = "USER";
            em.persist(user);
            owner = user.id;
            TrackedRepository repository = new TrackedRepository();
            repository.ownerId = owner;
            repository.githubId = 42L;
            repository.fullName = "test/indexing";
            repository.defaultBranch = "main";
            em.persist(repository);
            repositoryId = repository.id;
        });
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(owner.toString(), null, List.of()));
        SecurityContextHolder.setContext(context);
        sources = new RepositorySources(repositoryId, COMMIT, List.of(file("z.java", "zz"), file("a.java", "a\r\nb\r\nc\n")));
        when(acquisition.acquire(repositoryId, COMMIT)).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return sources;
        });
        when(embeddings.model()).thenReturn("test-model");
        when(embeddings.dimensions()).thenReturn(768);
        calls = new ArrayList<>();
        when(embeddings.embedDocument(anyString(), anyString())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            calls.add(invocation.getArgument(0) + ":" + invocation.getArgument(1));
            return embedding("test-model", 768, 768, 0.25);
        });
    }

    @AfterEach void clearSecurity() { SecurityContextHolder.clearContext(); }

    private RepositorySources.File file(String path, String content) {
        return new RepositorySources.File(path, content, content.getBytes(StandardCharsets.UTF_8).length, Crypto.hash(content));
    }

    private EmbeddingProvider.Embedding embedding(String model, int dimensions, int count, double value) {
        return new EmbeddingProvider.Embedding(model, dimensions, Collections.nCopies(count, value));
    }

    private List<RepositoryChunk> stored(String commit) {
        return chunks.findByRepositoryIdAndCommitShaOrderBySourcePathAscChunkIndexAsc(repositoryId, commit, Pageable.unpaged()).getContent();
    }

    private String indexedCommit() {
        return repositories.findById(repositoryId).orElseThrow().latestIndexedCommitSha;
    }

    @Test void latestSuccessfulIndexIsRepositoryScopedAndIndependentOfShaOrder() {
        assertNull(indexedCommit());
        String newer = "b".repeat(40);
        when(acquisition.acquire(repositoryId,newer)).thenReturn(new RepositorySources(repositoryId,newer,sources.files()));
        service.index(repositoryId,newer);
        assertEquals(newer,indexedCommit());
        service.index(repositoryId,COMMIT);
        assertEquals(COMMIT,indexedCommit());
        UUID other = transaction.execute(status -> {
            var repository = new TrackedRepository();
            repository.ownerId=owner; repository.githubId=43L;
            repository.fullName="test/other"; repository.defaultBranch="main";
            return repositories.saveAndFlush(repository).id;
        });
        assertNull(repositories.findById(other).orElseThrow().latestIndexedCommitSha);
    }

    @Test void failureForAnotherRevisionDoesNotPublishItsSha() {
        service.index(repositoryId,COMMIT);
        String failed = "b".repeat(40);
        when(acquisition.acquire(repositoryId,failed)).thenReturn(new RepositorySources(repositoryId,failed,sources.files()));
        doThrow(new EmbeddingFailure(EmbeddingFailure.Kind.TEMPORARY))
                .when(embeddings).embedDocument(anyString(),anyString());
        assertThrows(EmbeddingFailure.class,()->service.index(repositoryId,failed));
        assertEquals(COMMIT,indexedCommit());
        assertTrue(stored(failed).isEmpty());
    }

    @Test void embedsAndPersistsExactMetadataInDeterministicOrder() {
        var result = service.index(repositoryId, COMMIT.toUpperCase(Locale.ROOT));
        assertEquals(new RepositoryIndexingService.Result(repositoryId, COMMIT, 2, 3), result);
        assertEquals(COMMIT,indexedCommit());
        assertEquals(List.of("a.java:a\nb\n", "a.java:c\n", "z.java:zz"), calls);
        var expected = chunker.chunk(sources);
        var saved = stored(COMMIT);
        assertEquals(expected.size(), saved.size());
        for (int i = 0; i < saved.size(); i++) {
            var source = expected.get(i);
            var row = saved.get(i);
            assertEquals(source.repositoryId(), row.repositoryId);
            assertEquals(source.commitSha(), row.commitSha);
            assertEquals(source.sourcePath(), row.sourcePath);
            assertEquals(source.chunkIndex(), row.chunkIndex);
            assertEquals(source.content(), row.content);
            assertEquals(source.contentHash(), row.contentHash);
            assertEquals("test-model", row.embeddingModel);
            assertEquals(768, row.embedding.length);
            for (float value : row.embedding) assertEquals(0.25f, value);
        }
    }

    @Test void repeatReplacesCommitWithoutDuplicatesAndRemovesStalePositions() {
        service.index(repositoryId, COMMIT);
        var firstIds = stored(COMMIT).stream().map(c -> c.id).toList();
        service.index(repositoryId, COMMIT);
        assertEquals(3, stored(COMMIT).size());
        assertTrue(stored(COMMIT).stream().noneMatch(c -> firstIds.contains(c.id)));
        String otherCommit = "b".repeat(40);
        when(acquisition.acquire(repositoryId, otherCommit)).thenReturn(new RepositorySources(repositoryId, otherCommit, sources.files()));
        service.index(repositoryId, otherCommit);
        sources = new RepositorySources(repositoryId, COMMIT, List.of(file("a.java", "new")));
        service.index(repositoryId, COMMIT);
        assertEquals(List.of("new"), stored(COMMIT).stream().map(c -> c.content).toList());
        assertEquals(3, stored(otherCommit).size());
    }

    @Test void successfulEmptySnapshotClearsOldIndex() {
        service.index(repositoryId, COMMIT);
        sources = new RepositorySources(repositoryId, COMMIT, List.of(file("blank.md", " \n")));
        clearInvocations(embeddings);
        assertEquals(0, service.index(repositoryId, COMMIT).chunksIndexed());
        assertTrue(stored(COMMIT).isEmpty());
        verify(embeddings, never()).embedDocument(anyString(), anyString());
        assertNull(indexedCommit());
    }

    @Test void acquisitionAndChunkingFailuresNeverWrite() {
        when(acquisition.acquire(repositoryId, COMMIT)).thenThrow(Problem.upstream("GitHub"));
        assertThrows(Problem.class, () -> service.index(repositoryId, COMMIT));
        assertTrue(stored(COMMIT).isEmpty());
        doReturn(new RepositorySources(repositoryId, COMMIT, List.of(file("same", "a"), file("same", "b"))))
                .when(acquisition).acquire(repositoryId, COMMIT);
        assertThrows(IllegalArgumentException.class, () -> service.index(repositoryId, COMMIT));
        assertTrue(stored(COMMIT).isEmpty());
        verifyNoInteractions(embeddings);
        verify(chunks, never()).deleteCommit(any(), any());
    }

    @Test void partialEmbeddingFailureLeavesNoNewIndexAndPreservesExistingIndex() {
        EmbeddingFailure exhausted = exhaustedGoogleFailure();
        assertEquals(3, exhausted.attempts());
        when(embeddings.embedDocument(anyString(), anyString()))
                .thenReturn(embedding("test-model", 768, 768, 0.25))
                .thenThrow(exhausted);
        assertThrows(EmbeddingFailure.class, () -> service.index(repositoryId, COMMIT));
        assertTrue(stored(COMMIT).isEmpty());
        assertNull(indexedCommit());
        doReturn(embedding("test-model", 768, 768, 0.25)).when(embeddings).embedDocument(anyString(), anyString());
        service.index(repositoryId, COMMIT);
        var ids = stored(COMMIT).stream().map(c -> c.id).toList();
        when(embeddings.embedDocument(anyString(), anyString()))
                .thenReturn(embedding("test-model", 768, 768, 0.5))
                .thenThrow(exhausted);
        assertThrows(EmbeddingFailure.class, () -> service.index(repositoryId, COMMIT));
        assertEquals(ids, stored(COMMIT).stream().map(c -> c.id).toList());
        assertEquals(COMMIT,indexedCommit());
    }
    private EmbeddingFailure exhaustedGoogleFailure() {
        try {
            var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
            });
            server.start();
            try {
                var google = new GoogleEmbeddingProvider("test-key", "http://127.0.0.1:" + server.getAddress().getPort(),
                        "gemini-embedding-2", 768, 2);
                return assertThrows(EmbeddingFailure.class, () -> google.embedDocument("file", "source"));
            } finally { server.stop(0); }
        } catch (java.io.IOException e) { throw new AssertionError(e); }
    }

    @ParameterizedTest @ValueSource(ints = {0, 767, 769})
    void rejectsIncorrectResponseDimensionsAndValueCount(int dimensions) {
        when(embeddings.embedDocument(anyString(), anyString())).thenReturn(embedding("test-model", dimensions, 768, 0.25));
        assertThrows(EmbeddingFailure.class, () -> service.index(repositoryId, COMMIT));
        when(embeddings.embedDocument(anyString(), anyString())).thenReturn(embedding("test-model", 768, dimensions, 0.25));
        assertThrows(EmbeddingFailure.class, () -> service.index(repositoryId, COMMIT));
        assertTrue(stored(COMMIT).isEmpty());
    }

    @Test void rejectsWrongConfigurationAndModelMetadata() {
        when(embeddings.dimensions()).thenReturn(3072);
        assertEquals(EmbeddingFailure.Kind.CONFIGURATION, assertThrows(EmbeddingFailure.class,
                () -> service.index(repositoryId, COMMIT)).kind());
        verify(embeddings, never()).embedDocument(anyString(), anyString());
        when(embeddings.dimensions()).thenReturn(768);
        when(embeddings.embedDocument(anyString(), anyString())).thenReturn(embedding("different-model", 768, 768, 0.25));
        assertEquals(EmbeddingFailure.Kind.INVALID_RESPONSE, assertThrows(EmbeddingFailure.class,
                () -> service.index(repositoryId, COMMIT)).kind());
        assertTrue(stored(COMMIT).isEmpty());
    }

    @Test void disabledEmbeddingProviderFailsWithoutWriting() {
        @SuppressWarnings("unchecked")
        ObjectProvider<EmbeddingProvider> disabled = mock(ObjectProvider.class);
        var indexing = new RepositoryIndexingService(acquisition, chunker, disabled, repositories, chunks, transaction);
        assertEquals(EmbeddingFailure.Kind.CONFIGURATION, assertThrows(EmbeddingFailure.class,
                () -> indexing.index(repositoryId, COMMIT)).kind());
        assertTrue(stored(COMMIT).isEmpty());
        verify(chunks, never()).deleteCommit(any(), any());
    }

    @Test void concurrentReplacementsLeaveOneCompleteIndex() throws Exception {
        CountDownLatch bothEmbedding = new CountDownLatch(2);
        when(embeddings.embedDocument(anyString(), anyString())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            bothEmbedding.countDown();
            assertTrue(bothEmbedding.await(10, TimeUnit.SECONDS));
            return embedding("test-model", 768, 768, 0.25);
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<RepositoryIndexingService.Result> task = () -> {
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(new UsernamePasswordAuthenticationToken(owner.toString(), null, List.of()));
                SecurityContextHolder.setContext(context);
                try { return service.index(repositoryId, COMMIT); }
                finally { SecurityContextHolder.clearContext(); }
            };
            var first = executor.submit(task);
            var second = executor.submit(task);
            assertEquals(3, first.get(15, TimeUnit.SECONDS).chunksIndexed());
            assertEquals(3, second.get(15, TimeUnit.SECONDS).chunksIndexed());
            assertEquals(3, stored(COMMIT).size());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test void rejectsNonFiniteAndFloatOverflowValues() {
        for (double value : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.MAX_VALUE}) {
            when(embeddings.embedDocument(anyString(), anyString())).thenReturn(embedding("test-model", 768, 768, value));
            assertThrows(EmbeddingFailure.class, () -> service.index(repositoryId, COMMIT));
        }
        assertTrue(stored(COMMIT).isEmpty());
    }

    @Test void databaseFailureRollsBackDeletionAndPartialInsertion() {
        service.index(repositoryId, COMMIT);
        var ids = stored(COMMIT).stream().map(c -> c.id).toList();
        doAnswer(invocation -> {
            List<RepositoryChunk> rows = invocation.getArgument(0);
            chunks.saveAndFlush(rows.getFirst());
            throw new IllegalStateException("Synthetic persistence failure");
        }).when(chunks).saveAllAndFlush(any());
        assertThrows(IllegalStateException.class, () -> service.index(repositoryId, COMMIT));
        assertEquals(ids, stored(COMMIT).stream().map(c -> c.id).toList());
    }

    @Test void ownershipIsRecheckedBeforeReplacement() {
        when(embeddings.embedDocument(anyString(), anyString())).thenAnswer(invocation -> {
            transaction.executeWithoutResult(status -> {
                var repository = repositories.findById(repositoryId).orElseThrow();
                repository.ownerId = UUID.randomUUID();
                repositories.saveAndFlush(repository);
            });
            return embedding("test-model", 768, 768, 0.25);
        });
        assertThrows(Problem.class, () -> service.index(repositoryId, COMMIT));
        assertTrue(stored(COMMIT).isEmpty());
    }

    @Test void rejectsMovingRefsAndAmbientTransactionsBeforeNetworkCalls() {
        assertThrows(Problem.class, () -> service.index(repositoryId, "main"));
        assertThrows(IllegalTransactionStateException.class, () -> transaction.execute(status -> service.index(repositoryId, COMMIT)));
        verifyNoInteractions(acquisition, embeddings);
    }

    @Test void redactsProviderInputWhilePersistingExactSourceText() {
        String text = "ghp_abcdefghijklmnopqrstuvwxyz";
        sources = new RepositorySources(repositoryId, COMMIT, List.of(file("secret.java", text)));
        service.index(repositoryId, COMMIT);
        verify(embeddings).embedDocument("secret.java", SecretRedactor.clean(text));
        assertEquals(text, stored(COMMIT).getFirst().content);
        assertEquals(Crypto.hash(text), stored(COMMIT).getFirst().contentHash);
    }
}
