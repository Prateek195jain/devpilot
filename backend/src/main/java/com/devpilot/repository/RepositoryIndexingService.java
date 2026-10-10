package com.devpilot.repository;

import com.devpilot.ai.EmbeddingProvider;
import com.devpilot.common.CurrentUser;
import com.devpilot.common.Problem;
import com.devpilot.common.SecretRedactor;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RepositoryIndexingService {
    private final RepositorySourceAcquisitionService acquisition;
    private final RepositorySourceChunker chunker;
    private final ObjectProvider<EmbeddingProvider> embeddings;
    private final RepositoryStore repositories;
    private final RepositoryChunkStore chunks;
    private final TransactionTemplate transaction;

    public RepositoryIndexingService(RepositorySourceAcquisitionService acquisition, RepositorySourceChunker chunker,
            ObjectProvider<EmbeddingProvider> embeddings, RepositoryStore repositories,
            RepositoryChunkStore chunks, TransactionTemplate transaction) {
        this.acquisition = acquisition;
        this.chunker = chunker;
        this.embeddings = embeddings;
        this.repositories = repositories;
        this.chunks = chunks;
        this.transaction = transaction;
    }

    public record Result(UUID repositoryId, String commitSha, int filesProcessed, int chunksIndexed) {}

    /**
     * Build in memory, then atomically replace one revision. No network call runs in a
     * transaction. Existing indexes survive any failed attempt; successful empty results
     * remove old rows. Concurrent writers serialize on the repository row, last success wins.
     */
    @Transactional(propagation = Propagation.NEVER)
    public Result index(UUID repositoryId, String commitSha) {
        if (repositoryId == null || commitSha == null || !commitSha.matches("[a-fA-F0-9]{40}"))
            throw Problem.bad("Expected repository ID and full 40-character commit SHA");
        String commit = commitSha.toLowerCase(Locale.ROOT);
        UUID owner = CurrentUser.id();
        RepositorySources sources = acquisition.acquire(repositoryId, commit);
        List<SourceChunk> sourceChunks = chunker.chunk(sources);
        EmbeddingProvider provider = embeddings.getIfAvailable();
        String model = RepositoryEmbedding.model(provider);

        List<RepositoryChunk> prepared = new ArrayList<>(sourceChunks.size());
        for (SourceChunk source : sourceChunks) {
            RepositoryChunk row = new RepositoryChunk();
            row.repositoryId = repositoryId;
            row.commitSha = commit;
            row.sourcePath = source.sourcePath();
            row.chunkIndex = source.chunkIndex();
            row.content = source.content();
            row.contentHash = source.contentHash();
            row.embeddingModel = model;
            row.embedding = RepositoryEmbedding.vector(provider.embedDocument(SecretRedactor.clean(source.sourcePath()),
                    SecretRedactor.clean(source.content())), model);
            row.validate();
            prepared.add(row);
        }

        return transaction.execute(status -> {
            TrackedRepository repository = repositories.lock(repositoryId).orElseThrow(Problem::notFound);
            if (!owner.equals(repository.ownerId)) throw Problem.notFound();
            chunks.deleteCommit(repositoryId, commit);
            chunks.saveAllAndFlush(prepared);
            // Publish the usable revision only when the entire index transaction succeeds.
            repository.latestIndexedCommitSha = prepared.isEmpty() ? null : commit;
            return new Result(repositoryId, commit, sources.files().size(), prepared.size());
        });
    }

}
