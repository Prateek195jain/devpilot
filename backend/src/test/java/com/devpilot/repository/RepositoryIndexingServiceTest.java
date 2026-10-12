package com.devpilot.repository;

import com.devpilot.ai.*;
import com.devpilot.common.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Runs the staged builder contract in addition to the shared async/incremental contract. */
class RepositoryIndexingServiceTest extends RepositorySyncServiceTest {
    @Test void sourceTextIsStoredExactlyWhileEmbeddingInputIsRedacted() {
        String secret="ghp_abcdefghijklmnopqrstuvwxyz"; snapshot(A,Map.of("secret.java",secret));
        indexing.index(repositoryId,A); verify(embeddings).embedDocument("secret.java",SecretRedactor.clean(secret));
        assertEquals(secret,rows(A).getFirst().content); assertEquals(Crypto.hash(secret),rows(A).getFirst().contentHash);
    }
    @Test void malformedDimensionsFailWithoutPublication() {
        for(int count:new int[]{0,767,769}) {
            when(embeddings.embedDocument(anyString(),anyString())).thenReturn(new EmbeddingProvider.Embedding("test-model",768,Collections.nCopies(count,0.1)));
            assertThrows(EmbeddingFailure.class,() -> indexing.index(repositoryId,A)); assertNull(stored().latestIndexedCommitSha);
            assertEquals(IndexingStatus.FAILED,stored().indexingStatus); assertNull(stored().nextIndexingAttemptAt);
        }
    }
    @Test void nonFiniteAndFloatOverflowVectorsFailWithoutPublication() {
        for(double value:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.MAX_VALUE}) {
            when(embeddings.embedDocument(anyString(),anyString())).thenReturn(new EmbeddingProvider.Embedding("test-model",768,Collections.nCopies(768,value)));
            assertThrows(EmbeddingFailure.class,() -> indexing.index(repositoryId,A)); assertNull(stored().latestIndexedCommitSha);
        }
    }
    @Test void localValidationRejectsMovingRefsBeforeAnyProviderCalls() {
        assertThrows(Problem.class,() -> indexing.index(repositoryId,"main")); verifyNoInteractions(embeddings);
    }
    @Test void completeEmptyCommitIsReadyAndRepeatedSyncDoesNotRebuild() {
        snapshot(A,Map.of("empty.md"," \n")); sync.sync(repositoryId); run();
        assertEquals(IndexingStatus.READY,stored().indexingStatus); assertNull(stored().latestIndexedCommitSha);
        sync.sync(repositoryId); assertTrue(jobs.claim(repositoryId).isEmpty()); verify(embeddings,never()).embedDocument(anyString(),anyString());
    }
    @Test void exhaustedRealProviderFailurePreservesPreviousIndex() throws Exception {
        ready(); var ids=rows(A).stream().map(row -> row.id).toList();
        snapshot(B,Map.of("a.java","new\n","z.java","failed\n")); target(B);
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var requests=new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/", exchange -> { requests.incrementAndGet(); exchange.getRequestBody().readAllBytes(); exchange.sendResponseHeaders(429,-1); exchange.close(); });
        server.start();
        try {
            var google=new GoogleEmbeddingProvider("test-only-key","http://127.0.0.1:"+server.getAddress().getPort(),"gemini-embedding-2",768,2,new EmbeddingPacing(1,0,3,1,1,5));
            when(embeddings.embedDocument(anyString(),anyString())).thenReturn(embedding()).thenAnswer(call -> google.embedDocument("private","source"));
            sync.sync(repositoryId); var failure=assertThrows(EmbeddingFailure.class,this::run);
            assertEquals(3,requests.get()); assertEquals(3,failure.attempts()); assertNull(failure.getCause());
            assertFalse(failure.diagnostic().contains("test-only-key")); assertFalse(failure.diagnostic().contains("source"));
            assertEquals(A,stored().latestIndexedCommitSha); assertEquals(ids,rows(A).stream().map(row -> row.id).toList());
            assertEquals(IndexingStatus.WAITING_RETRY,stored().indexingStatus); assertNotNull(stored().nextIndexingAttemptAt);
            assertTrue(rows(B).stream().noneMatch(row -> row.published));
        } finally { server.stop(0); }
    }
}
