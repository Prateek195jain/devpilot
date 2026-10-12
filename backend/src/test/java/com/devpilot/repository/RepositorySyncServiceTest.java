package com.devpilot.repository;

import com.devpilot.ai.*;
import com.devpilot.common.*;
import com.devpilot.github.*;
import com.devpilot.user.UserAccount;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DataJpaTest(showSql=false,properties={"app.source-chunking.max-chars=8","app.source-chunking.overlap-chars=0"})
@ActiveProfiles("test") @AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import({RepositorySyncService.class,RepositoryService.class,RepositoryAccess.class,RepositoryIndexJobs.class,
        RepositoryIndexingService.class,RepositorySourceAcquisitionService.class,RepositorySourceChunker.class,SourceFilePolicy.class})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class RepositorySyncServiceTest {
    static final String A="a".repeat(40),B="b".repeat(40),C="c".repeat(40);
    @Autowired RepositorySyncService sync;
    @Autowired RepositoryIndexJobs jobs;
    @Autowired RepositoryIndexingService indexing;
    @Autowired RepositoryStore repositories;
    @MockitoSpyBean RepositoryChunkStore chunks;
    @Autowired TransactionTemplate transaction;
    @Autowired EntityManager em;
    @MockitoBean IntegrationService integration;
    @MockitoBean SourceControlProvider github;
    @MockitoBean EmbeddingProvider embeddings;
    UUID owner,repositoryId;
    final List<UUID> extraOwners=new ArrayList<>();
    final Map<String,byte[]> blobs=new HashMap<>();
    @BeforeEach void setup() {
        transaction.executeWithoutResult(status -> {
            var user=new UserAccount(); user.email=UUID.randomUUID()+"@test.example"; user.name="Index test";
            user.passwordHash="synthetic"; user.role="USER"; em.persist(user); owner=user.id;
            var r=new TrackedRepository(); r.ownerId=owner; r.githubId=42L; r.fullName="test/repo"; r.defaultBranch="main";
            em.persist(r); repositoryId=r.id;
        });
        authenticate(owner);
        when(integration.token(owner)).thenReturn("token");
        when(github.repository("token","test/repo")).thenReturn(new SourceControlProvider.Repo(42L,"test/repo","Updated","main","Java"));
        when(github.readme("token","test/repo")).thenReturn("README");
        when(github.latestCommitSha("token","test/repo","main")).thenAnswer(call -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertNotNull(stored().syncedAt); return A;
        });
        when(github.commitTree(eq("token"),eq("test/repo"),anyString())).thenAnswer(call -> call.getArgument(2));
        when(github.blob(eq("token"),eq("test/repo"),anyString())).thenAnswer(call -> blobs.get(call.getArgument(2)));
        when(embeddings.model()).thenReturn("test-model"); when(embeddings.dimensions()).thenReturn(768);
        when(embeddings.embedDocument(anyString(),anyString())).thenAnswer(call -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()); return embedding();
        });
        snapshot(A,Map.of("one.java","one\n"));
    }
    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        transaction.executeWithoutResult(status -> {
            chunks.deleteAllInBatch(); repositories.deleteAllInBatch(); em.remove(em.find(UserAccount.class,owner)); for(var id:extraOwners) em.remove(em.find(UserAccount.class,id));
        });
    }
    void authenticate(UUID user) { SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user.toString(),null,List.of())); }
    TrackedRepository stored() { return repositories.findById(repositoryId).orElseThrow(); }
    EmbeddingProvider.Embedding embedding() { return new EmbeddingProvider.Embedding("test-model",768,Collections.nCopies(768,0.25)); }
    void snapshot(String commit,Map<String,String> files) {
        List<SourceControlProvider.TreeEntry> entries=new ArrayList<>();
        new TreeMap<>(files).forEach((path,content) -> {
            var bytes=content.getBytes(StandardCharsets.UTF_8); String sha=Crypto.hash(content).substring(0,40); blobs.put(sha,bytes);
            entries.add(new SourceControlProvider.TreeEntry(path,"blob","100644",sha,bytes.length));
        });
        when(github.tree("token","test/repo",commit)).thenReturn(entries);
    }
    void target(String commit) { doReturn(commit).when(github).latestCommitSha("token","test/repo","main"); }
    RepositoryIndexJobs.Job claim() { return jobs.claim(repositoryId).orElseThrow(); }
    void run() { var job=claim(); try { indexing.build(job); } catch(RuntimeException failure) { jobs.fail(job,failure); throw failure; } }
    List<RepositoryChunk> rows(String commit) { return chunks.findByRepositoryIdAndCommitShaOrderBySourcePathAscChunkIndexAsc(repositoryId,commit,Pageable.unpaged()).getContent(); }
    void ready() { sync.sync(repositoryId); run(); assertEquals(A,stored().latestIndexedCommitSha); }

    @Test void syncReturnsQueuedWithoutCallingEmbeddings() {
        var result=sync.sync(repositoryId);
        assertEquals(IndexingStatus.INDEXING,result.indexingStatus()); assertEquals(A,result.indexingTargetCommitSha());
        assertNull(result.latestIndexedCommitSha()); verify(embeddings,never()).embedDocument(anyString(),anyString());
        verify(github,never()).commitTree(anyString(),anyString(),anyString());
        run(); assertEquals(IndexingStatus.READY,stored().indexingStatus); assertEquals(A,stored().latestIndexedCommitSha);
    }
    @Test void importReturnsQueuedWithoutCallingEmbeddings() {
        transaction.executeWithoutResult(status -> repositories.deleteById(repositoryId));
        doAnswer(call -> { repositoryId=repositories.findByOwnerId(owner,Pageable.unpaged()).getContent().getFirst().id; return A; })
                .when(github).latestCommitSha("token","test/repo","main");
        var result=sync.importRepo("test/repo"); assertEquals(IndexingStatus.INDEXING,result.indexingStatus());
        assertNull(result.latestIndexedCommitSha()); verify(embeddings,never()).embedDocument(anyString(),anyString());
        run(); assertEquals(A,stored().latestIndexedCommitSha);
    }
    @Test void backgroundWorkerDoesNotBlockSyncAndDeduplicatesWhileEmbedding() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(embeddings.embedDocument(anyString(),anyString())).thenAnswer(call -> { entered.countDown(); assertTrue(release.await(10,TimeUnit.SECONDS)); return embedding(); });
        var worker=new RepositoryIndexWorker(jobs,indexing,1);
        try {
            sync.sync(repositoryId); worker.tick(); assertTrue(entered.await(5,TimeUnit.SECONDS));
            var repeated=sync.sync(repositoryId); assertEquals(IndexingStatus.INDEXING,repeated.indexingStatus());
            assertTrue(jobs.claim(repositoryId).isEmpty()); worker.tick(); verify(embeddings,times(1)).embedDocument(anyString(),anyString());
            release.countDown(); long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(stored().indexingStatus==IndexingStatus.INDEXING && System.nanoTime()<deadline) Thread.sleep(10);
            assertEquals(IndexingStatus.READY,stored().indexingStatus); assertEquals(A,stored().latestIndexedCommitSha);
        } finally { release.countDown(); worker.stop(); }
    }
    @Test void samePublishedTargetDoesNoWork() {
        ready(); clearInvocations(embeddings,github); sync.sync(repositoryId);
        assertEquals(IndexingStatus.READY,stored().indexingStatus); assertTrue(jobs.claim(repositoryId).isEmpty());
        verify(embeddings,never()).embedDocument(anyString(),anyString()); verify(github,never()).commitTree(anyString(),anyString(),anyString());
    }
    @Test void unchangedBlobSkipsDownloadAndEmbeddingAndNewCommitIsComplete() {
        ready(); snapshot(B,Map.of("one.java","one\n")); target(B); clearInvocations(embeddings,github);
        sync.sync(repositoryId); assertEquals(A,stored().latestIndexedCommitSha); run();
        verify(embeddings,never()).embedDocument(anyString(),anyString()); verify(github,never()).blob(anyString(),anyString(),anyString());
        assertEquals(B,stored().latestIndexedCommitSha); assertEquals(rows(A).getFirst().content,rows(B).getFirst().content);
        assertArrayEquals(rows(A).getFirst().embedding,rows(B).getFirst().embedding);
    }
    @Test void modifiedFileEmbedsOnlyChangedChunk() {
        snapshot(A,Map.of("one.java","same\nold\n")); ready();
        snapshot(B,Map.of("one.java","same\nchanged\n")); target(B); clearInvocations(embeddings); sync.sync(repositoryId); run();
        verify(embeddings,times(1)).embedDocument("one.java","changed\n");
        assertEquals(List.of("same\n","changed\n"),rows(B).stream().map(row -> row.content).toList());
    }
    @Test void addedAndDeletedFilesAreScopedToTargetCommit() {
        ready(); snapshot(B,Map.of("added.java","new\n")); target(B); clearInvocations(embeddings); sync.sync(repositoryId); run();
        verify(embeddings,times(1)).embedDocument("added.java","new\n");
        assertEquals(List.of("added.java"),rows(B).stream().map(row -> row.sourcePath).toList()); assertEquals("one.java",rows(A).getFirst().sourcePath);
    }
    @Test void renameWithUnchangedBlobReusesVector() {
        ready(); snapshot(B,Map.of("renamed.java","one\n")); target(B); clearInvocations(embeddings,github); sync.sync(repositoryId); run();
        verify(embeddings,never()).embedDocument(anyString(),anyString()); verify(github,never()).blob(anyString(),anyString(),anyString());
        assertEquals("renamed.java",rows(B).getFirst().sourcePath); assertArrayEquals(rows(A).getFirst().embedding,rows(B).getFirst().embedding);
    }
    @Test void exactChunkHashReusesEmbeddingInsideModifiedBlob() {
        ready(); snapshot(B,Map.of("copy.java","one\nextra\n")); target(B); clearInvocations(embeddings); sync.sync(repositoryId); run();
        verify(embeddings,times(1)).embedDocument("copy.java","extra\n"); assertEquals(2,rows(B).size());
    }
    @Test void failedRefreshPreservesPublishedIndexAndRetryReusesCheckpoint() {
        ready(); snapshot(B,Map.of("a.java","added\n","z.java","failed\n")); target(B);
        when(embeddings.embedDocument(anyString(),anyString())).thenReturn(embedding()).thenThrow(new EmbeddingFailure(EmbeddingFailure.Kind.RATE_LIMIT));
        sync.sync(repositoryId); var oldIds=rows(A).stream().map(row -> row.id).toList();
        assertThrows(EmbeddingFailure.class,this::run); assertEquals(IndexingStatus.WAITING_RETRY,stored().indexingStatus);
        assertEquals(IndexingErrorCategory.RATE_LIMIT,stored().lastIndexingErrorCategory); assertEquals(A,stored().latestIndexedCommitSha);
        assertEquals(oldIds,rows(A).stream().map(row -> row.id).toList()); assertEquals(1,rows(B).size()); assertFalse(rows(B).getFirst().published);
        doReturn(embedding()).when(embeddings).embedDocument(anyString(),anyString()); clearInvocations(embeddings);
        var checkpoint=rows(B).getFirst();
        assertNotNull(stored().nextIndexingAttemptAt); assertNull(stored().indexingJobToken); assertNull(stored().indexingLeaseUntil);
        assertTrue(jobs.claim(repositoryId).isEmpty()); assertFalse(jobs.pending(10).contains(repositoryId));
        sync.sync(repositoryId); assertEquals(IndexingStatus.WAITING_RETRY,stored().indexingStatus);
        due(); run(); verify(embeddings,times(1)).embedDocument("z.java","failed\n");
        assertEquals(checkpoint.id,rows(B).getFirst().id); assertArrayEquals(checkpoint.embedding,rows(B).getFirst().embedding);
        assertNull(stored().nextIndexingAttemptAt); assertEquals(0,stored().indexingRetryCount);
        assertEquals(B,stored().latestIndexedCommitSha); assertTrue(rows(B).stream().allMatch(row -> row.published));
    }
    void due() {
        transaction.executeWithoutResult(status -> repositories.lock(repositoryId).orElseThrow().nextIndexingAttemptAt=Instant.now().minusSeconds(1));
    }
    @Test void repeatedRateLimitsIncreasePersistedCooldownAndFenceOldAttempt() {
        sync.sync(repositoryId); var first=claim(); jobs.fail(first,new EmbeddingFailure(EmbeddingFailure.Kind.RATE_LIMIT));
        var retryAt=stored().nextIndexingAttemptAt;
        assertEquals(1,stored().indexingRetryCount); assertNull(stored().latestIndexedCommitSha);
        sync.sync(repositoryId); assertEquals(retryAt,stored().nextIndexingAttemptAt);
        due(); assertTrue(jobs.pending(10).contains(repositoryId)); var second=claim();
        assertNotEquals(first.token(),second.token()); assertTrue(jobs.claim(repositoryId).isEmpty());
        jobs.fail(first,new EmbeddingFailure(EmbeddingFailure.Kind.RATE_LIMIT)); assertEquals(IndexingStatus.INDEXING,stored().indexingStatus);
        jobs.fail(second,new EmbeddingFailure(EmbeddingFailure.Kind.RATE_LIMIT));
        assertEquals(2,stored().indexingRetryCount);
        assertTrue(stored().nextIndexingAttemptAt.isAfter(Instant.now().plusSeconds(590)));
        assertEquals(300,jobs.cooldownSeconds(1,null)); assertEquals(600,jobs.cooldownSeconds(2,null));
        assertEquals(1200,jobs.cooldownSeconds(3,null)); assertEquals(1800,jobs.cooldownSeconds(Integer.MAX_VALUE,null));
        assertEquals(1000,jobs.cooldownSeconds(1,1000L)); assertEquals(1800,jobs.cooldownSeconds(1,Long.MAX_VALUE));
    }
    @Test void restartedWorkerAutomaticallyResumesDueCheckpointWithoutSync() throws Exception {
        snapshot(A,Map.of("a.java","added\n","z.java","failed\n"));
        when(embeddings.embedDocument(anyString(),anyString())).thenReturn(embedding()).thenThrow(new EmbeddingFailure(EmbeddingFailure.Kind.RATE_LIMIT));
        sync.sync(repositoryId); assertThrows(EmbeddingFailure.class,this::run);
        var checkpoint=rows(A).getFirst(); assertNull(stored().latestIndexedCommitSha);
        doReturn(embedding()).when(embeddings).embedDocument(anyString(),anyString()); clearInvocations(embeddings);
        var worker=new RepositoryIndexWorker(jobs,indexing,1);
        try {
            worker.tick(); verify(embeddings,never()).embedDocument(anyString(),anyString());
            due(); worker.tick(); long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(stored().indexingStatus!=IndexingStatus.READY && System.nanoTime()<deadline) Thread.sleep(10);
            assertEquals(IndexingStatus.READY,stored().indexingStatus); assertEquals(A,stored().latestIndexedCommitSha);
            verify(embeddings,times(1)).embedDocument("z.java","failed\n"); assertEquals(checkpoint.id,rows(A).getFirst().id);
        } finally { worker.stop(); }
    }
    @Test void newerWaitingTargetCoalescesAndReusesOldCandidate() {
        snapshot(A,Map.of("a.java","added\n","z.java","failed\n")); sync.sync(repositoryId);
        when(embeddings.embedDocument(anyString(),anyString())).thenReturn(embedding()).thenThrow(new EmbeddingFailure(EmbeddingFailure.Kind.RATE_LIMIT));
        assertThrows(EmbeddingFailure.class,this::run);
        snapshot(B,Map.of("a.java","added\n","b.java","new\n")); target(B); sync.sync(repositoryId);
        assertEquals(IndexingStatus.INDEXING,stored().indexingStatus); assertNull(stored().nextIndexingAttemptAt); assertEquals(0,stored().indexingRetryCount);
        doReturn(embedding()).when(embeddings).embedDocument(anyString(),anyString()); clearInvocations(embeddings); run();
        verify(embeddings,times(1)).embedDocument("b.java","new\n"); assertEquals(B,stored().latestIndexedCommitSha);
    }
    @Test void deterministicEmbeddingFailuresNeverScheduleAutomaticRetry() {
        for (var kind:List.of(EmbeddingFailure.Kind.AUTHENTICATION,EmbeddingFailure.Kind.CONFIGURATION,
                EmbeddingFailure.Kind.INVALID_REQUEST,EmbeddingFailure.Kind.INVALID_RESPONSE)) {
            sync.sync(repositoryId); jobs.fail(claim(),new EmbeddingFailure(kind));
            assertEquals(IndexingStatus.FAILED,stored().indexingStatus); assertNull(stored().nextIndexingAttemptAt);
            assertFalse(jobs.pending(10).contains(repositoryId)); assertTrue(jobs.claim(repositoryId).isEmpty());
        }
    }
    @Test void firstFailureKeepsNullShaAndSafeCategory() {
        when(embeddings.embedDocument(anyString(),anyString())).thenThrow(new EmbeddingFailure(EmbeddingFailure.Kind.AUTHENTICATION));
        sync.sync(repositoryId); assertThrows(EmbeddingFailure.class,this::run);
        assertEquals(IndexingStatus.FAILED,stored().indexingStatus); assertNull(stored().latestIndexedCommitSha);
        assertEquals(IndexingErrorCategory.AUTHENTICATION,stored().lastIndexingErrorCategory);
    }
    @Test void publicationFailureLeavesAllCandidateRowsHiddenAndPreviousPointerIntact() {
        ready(); snapshot(B,Map.of("new.java","new\n")); target(B); sync.sync(repositoryId);
        doThrow(new IllegalStateException("synthetic publication failure")).when(chunks).publish(eq(repositoryId),eq(B),any());
        assertThrows(IllegalStateException.class,this::run); assertEquals(A,stored().latestIndexedCommitSha);
        assertTrue(rows(B).stream().noneMatch(row -> row.published)); assertEquals(IndexingStatus.FAILED,stored().indexingStatus);
    }
    @Test void newerTargetWaitsForActiveBuildThenRunsNewest() {
        sync.sync(repositoryId); var active=claim(); snapshot(B,Map.of("b.java","b\n")); target(B); sync.sync(repositoryId);
        snapshot(C,Map.of("c.java","c\n")); target(C); sync.sync(repositoryId); assertTrue(jobs.claim(repositoryId).isEmpty());
        indexing.build(active); assertEquals(A,stored().latestIndexedCommitSha); assertEquals(IndexingStatus.INDEXING,stored().indexingStatus);
        assertEquals(C,stored().indexingTargetCommitSha); run(); assertEquals(C,stored().latestIndexedCommitSha); assertTrue(rows(B).isEmpty());
    }
    @Test void expiredLeaseIsRecoverableAndOldWorkerCannotWrite() {
        sync.sync(repositoryId); var abandoned=claim();
        transaction.executeWithoutResult(status -> repositories.lock(repositoryId).orElseThrow().indexingLeaseUntil=Instant.now().minusSeconds(1));
        var replacement=claim(); assertNotEquals(abandoned.token(),replacement.token());
        assertThrows(Problem.class,() -> indexing.build(abandoned));
        jobs.fail(abandoned,new IllegalStateException("stale worker")); assertEquals(IndexingStatus.INDEXING,stored().indexingStatus);
        indexing.build(replacement);
        assertEquals(IndexingStatus.READY,stored().indexingStatus); assertEquals(A,stored().latestIndexedCommitSha);
    }
    @Test void retryCleansUnvisitedCheckpointPositionsBeforePublication() {
        sync.sync(repositoryId);
        transaction.executeWithoutResult(status -> {
            var extra=new RepositoryChunk(); extra.repositoryId=repositoryId; extra.commitSha=A; extra.sourcePath="removed.java";
            extra.content="stale"; extra.contentHash=Crypto.hash(extra.content); extra.embeddingModel="test-model";
            extra.embedding=new float[768]; java.util.Arrays.fill(extra.embedding,0.25f);
            extra.published=false; extra.buildToken=UUID.randomUUID(); chunks.saveAndFlush(extra);
        });
        run(); assertEquals(java.util.List.of("one.java"),rows(A).stream().map(row -> row.sourcePath).toList());
        assertTrue(rows(A).getFirst().published);
    }
    @Test void immutableShaValidationPreventsQueueingMovingRefs() {
        for(String value:new String[]{"main","abcdefg"}) {
            target(value); assertThrows(Problem.class,() -> sync.sync(repositoryId));
            assertEquals(IndexingStatus.NOT_INDEXED,stored().indexingStatus); assertNull(stored().indexingTargetCommitSha);
        }
        verify(embeddings,never()).embedDocument(anyString(),anyString());
    }
    @Test void metadataFailureDoesNotResolveOrEnqueueTarget() {
        when(github.repository("token","test/repo")).thenThrow(Problem.upstream("GitHub"));
        assertThrows(Problem.class,() -> sync.sync(repositoryId)); assertNull(stored().syncedAt);
        assertEquals(IndexingStatus.NOT_INDEXED,stored().indexingStatus);
        verify(github,never()).latestCommitSha(anyString(),anyString(),anyString());
    }
    @Test void failedSourceResolutionKeepsCommittedMetadataWithoutQueueing() {
        doThrow(Problem.upstream("GitHub")).when(github).latestCommitSha(anyString(),anyString(),anyString());
        assertThrows(Problem.class,() -> sync.sync(repositoryId)); assertNotNull(stored().syncedAt);
        assertEquals(IndexingStatus.NOT_INDEXED,stored().indexingStatus); verify(embeddings,never()).embedDocument(anyString(),anyString());
    }
    @Test void ownershipAndAmbientTransactionsAreRejectedBeforeNetwork() {
        authenticate(UUID.randomUUID()); assertEquals(org.springframework.http.HttpStatus.NOT_FOUND,assertThrows(Problem.class,() -> sync.sync(repositoryId)).status);
        verifyNoInteractions(github,embeddings); authenticate(owner);
        assertThrows(IllegalTransactionStateException.class,() -> transaction.execute(status -> sync.sync(repositoryId)));
    }
    @Test void ownershipIsRecheckedBeforeCandidateWrite() {
        when(embeddings.embedDocument(anyString(),anyString())).thenAnswer(call -> {
            transaction.executeWithoutResult(status -> {
                var other=new UserAccount(); other.email=UUID.randomUUID()+"@test.example"; other.name="Other owner";
                other.passwordHash="synthetic"; other.role="USER"; em.persist(other); extraOwners.add(other.id);
                repositories.lock(repositoryId).orElseThrow().ownerId=other.id;
            }); return embedding();
        });
        sync.sync(repositoryId); assertThrows(Problem.class,this::run); assertTrue(rows(A).isEmpty()); assertNull(stored().latestIndexedCommitSha);
    }
    @Test void modelMismatchCannotReusePublishedEmbedding() {
        ready(); snapshot(B,Map.of("one.java","one\n")); target(B); when(embeddings.model()).thenReturn("new-model");
        when(embeddings.embedDocument(anyString(),anyString())).thenReturn(new EmbeddingProvider.Embedding("new-model",768,Collections.nCopies(768,0.5)));
        clearInvocations(embeddings); sync.sync(repositoryId); run(); verify(embeddings,times(1)).embedDocument(anyString(),anyString());
        assertEquals("new-model",rows(B).getFirst().embeddingModel);
    }
}
