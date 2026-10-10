package com.devpilot.repository;

import com.devpilot.ai.*;
import com.devpilot.common.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RepositoryRetrievalServiceTest {
    private final UUID repositoryId = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private static final String COMMIT = "a".repeat(40);
    private final RepositoryStore repositories = mock(RepositoryStore.class);
    private final RepositoryChunkStore chunks = mock(RepositoryChunkStore.class);
    private final EmbeddingProvider embeddings = mock(EmbeddingProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<EmbeddingProvider> providers = mock(ObjectProvider.class);
    private final RepositoryRetrievalService service = new RepositoryRetrievalService(
            new RepositoryAccess(repositories), providers, chunks, 50);

    @BeforeEach void setup() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(owner.toString(), null, List.of()));
        when(repositories.findByIdAndOwnerId(repositoryId, owner)).thenReturn(Optional.of(new TrackedRepository()));
        when(providers.getIfAvailable()).thenReturn(embeddings);
        when(embeddings.model()).thenReturn("test-model");
        when(embeddings.dimensions()).thenReturn(768);
        when(embeddings.embedQuery(anyString())).thenReturn(embedding("test-model", 768, 768, 0.25));
    }

    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }

    private EmbeddingProvider.Embedding embedding(String model, int dimensions, int count, double value) {
        return new EmbeddingProvider.Embedding(model, dimensions, Collections.nCopies(count, value));
    }

    @Test void scopesSearchCapsLimitAndReturnsImmutableOrderedMetadata() {
        var first = new RepositoryChunkMatch(repositoryId, COMMIT, "z.java", 0, "first", 0.1);
        var second = new RepositoryChunkMatch(repositoryId, COMMIT, "a.java", 1, "second", 0.2);
        when(chunks.search(eq(repositoryId), eq(owner), eq(COMMIT), eq("test-model"), any(), eq(50)))
                .thenReturn(new ArrayList<>(List.of(first, second)));
        var result = service.retrieve(repositoryId, COMMIT.toUpperCase(Locale.ROOT), "find auth", Integer.MAX_VALUE);
        assertEquals(List.of(first, second), result);
        assertThrows(UnsupportedOperationException.class, () -> result.add(first));
        var order = inOrder(repositories, embeddings, chunks);
        order.verify(repositories).findByIdAndOwnerId(repositoryId, owner);
        order.verify(embeddings).embedQuery("find auth");
        order.verify(chunks).search(eq(repositoryId), eq(owner), eq(COMMIT), eq("test-model"),
                argThat(vector -> vector.length == 768 && vector[0] == 0.25f), eq(50));
    }

    @Test void missingCommitReturnsEmptyWithoutFallback() {
        assertEquals(List.of(), service.retrieve(repositoryId, COMMIT, "query", 3));
        verify(chunks).search(eq(repositoryId), eq(owner), eq(COMMIT), eq("test-model"), any(), eq(3));
        verifyNoMoreInteractions(chunks);
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={" ", "\n\t"})
    void rejectsBlankQuery(String query) {
        assertThrows(Problem.class, () -> service.retrieve(repositoryId, COMMIT, query, 1));
        verifyNoInteractions(providers, embeddings, chunks);
    }

    @ParameterizedTest @ValueSource(ints={0, -1, Integer.MIN_VALUE})
    void rejectsInvalidLimit(int limit) {
        assertThrows(Problem.class, () -> service.retrieve(repositoryId, COMMIT, "query", limit));
        verifyNoInteractions(providers, embeddings, chunks);
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"main", "abc123", "g123456789012345678901234567890123456789"})
    void rejectsInvalidCommit(String commit) {
        assertThrows(Problem.class, () -> service.retrieve(repositoryId, commit, "query", 1));
        verifyNoInteractions(providers, embeddings, chunks);
    }

    @Test void rejectsMissingRepository() {
        assertThrows(Problem.class, () -> service.retrieve(null, COMMIT, "query", 1));
        verifyNoInteractions(repositories, providers, embeddings, chunks);
    }

    @Test void unauthorizedRepositoryNeverEmbedsOrSearches() {
        when(repositories.findByIdAndOwnerId(repositoryId, owner)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(Problem.class,
                () -> service.retrieve(repositoryId, COMMIT, "query", 1)).status.value());
        verifyNoInteractions(providers, embeddings, chunks);
    }

    @ParameterizedTest @ValueSource(ints={0, 767, 769})
    void validatesDimensionsAndValueCount(int dimensions) {
        when(embeddings.embedQuery(anyString())).thenReturn(embedding("test-model", dimensions, 768, 0.25));
        invalidResponse();
        when(embeddings.embedQuery(anyString())).thenReturn(embedding("test-model", 768, dimensions, 0.25));
        invalidResponse();
    }

    @Test void rejectsWrongModelNullResponseNonfiniteOverflowAndZeroVectors() {
        when(embeddings.embedQuery(anyString())).thenReturn(embedding("other-model", 768, 768, 0.25));
        invalidResponse();
        when(embeddings.embedQuery(anyString())).thenReturn(null);
        invalidResponse();
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.MAX_VALUE, 0, Double.MIN_VALUE}) {
            when(embeddings.embedQuery(anyString())).thenReturn(embedding("test-model", 768, 768, value));
            invalidResponse();
        }
    }

    private void invalidResponse() {
        assertEquals(EmbeddingFailure.Kind.INVALID_RESPONSE, assertThrows(EmbeddingFailure.class,
                () -> service.retrieve(repositoryId, COMMIT, "query", 1)).kind());
        verifyNoInteractions(chunks);
    }

    @Test void preservesEmbeddingFailureAndRetryMetadata() {
        var failure = new EmbeddingFailure(EmbeddingFailure.Kind.RATE_LIMIT, 12L);
        when(embeddings.embedQuery(anyString())).thenThrow(failure);
        assertSame(failure, assertThrows(EmbeddingFailure.class,
                () -> service.retrieve(repositoryId, COMMIT, "query", 1)));
        verifyNoInteractions(chunks);
    }

    @Test void rejectsDisabledOrMisconfiguredProvider() {
        when(providers.getIfAvailable()).thenReturn(null);
        configurationFailure();
        when(providers.getIfAvailable()).thenReturn(embeddings);
        when(embeddings.dimensions()).thenReturn(3072);
        configurationFailure();
        when(embeddings.dimensions()).thenReturn(768);
        for (String model : new String[]{null, " ", "x".repeat(101)}) {
            when(embeddings.model()).thenReturn(model);
            configurationFailure();
        }
        verify(embeddings, never()).embedQuery(anyString());
    }

    private void configurationFailure() {
        assertEquals(EmbeddingFailure.Kind.CONFIGURATION, assertThrows(EmbeddingFailure.class,
                () -> service.retrieve(repositoryId, COMMIT, "query", 1)).kind());
        verifyNoInteractions(chunks);
    }

    @Test void rejectsUnsafeMaximumConfiguration() {
        for (int limit : new int[]{0, -1, 1001}) assertThrows(IllegalArgumentException.class,
                () -> new RepositoryRetrievalService(new RepositoryAccess(repositories), providers, chunks, limit));
    }
}
