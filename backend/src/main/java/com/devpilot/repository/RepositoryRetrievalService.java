package com.devpilot.repository;

import com.devpilot.ai.*;
import com.devpilot.common.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RepositoryRetrievalService {
    private final RepositoryAccess access;
    private final ObjectProvider<EmbeddingProvider> embeddings;
    private final RepositoryChunkStore chunks;
    private final int maxLimit;

    public RepositoryRetrievalService(RepositoryAccess access, ObjectProvider<EmbeddingProvider> embeddings,
            RepositoryChunkStore chunks, @Value("${app.retrieval.max-limit:50}") int maxLimit) {
        if (maxLimit < 1 || maxLimit > 1000)
            throw new IllegalArgumentException("Retrieval maximum limit must be between 1 and 1000");
        this.access = access;
        this.embeddings = embeddings;
        this.chunks = chunks;
        this.maxLimit = maxLimit;
    }

    @Transactional(propagation = Propagation.NEVER)
    public List<RepositoryChunkMatch> retrieve(UUID repositoryId, String commitSha, String query, int limit) {
        if (repositoryId == null) throw Problem.bad("Expected repository ID");
        UUID owner = CurrentUser.id();
        access.require(repositoryId, owner);
        if (commitSha == null || !commitSha.matches("[a-fA-F0-9]{40}"))
            throw Problem.bad("Expected full 40-character commit SHA");
        if (query == null || query.isBlank()) throw Problem.bad("Query must not be blank");
        if (limit < 1) throw Problem.bad("Retrieval limit must be positive");
        EmbeddingProvider provider = embeddings.getIfAvailable();
        String model = RepositoryEmbedding.model(provider);
        float[] vector = RepositoryEmbedding.vector(provider.embedQuery(SecretRedactor.clean(query)), model);
        double norm = 0;
        for (float value : vector) norm += (double) value * value;
        if (norm == 0) throw new EmbeddingFailure(EmbeddingFailure.Kind.INVALID_RESPONSE);
        return List.copyOf(chunks.search(repositoryId, owner, commitSha.toLowerCase(Locale.ROOT),
                model, vector, Math.min(limit, maxLimit)));
    }
}
