package com.devpilot.repository;

import com.devpilot.ai.EmbeddingProvider;
import com.devpilot.common.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RepositoryIndexingService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(RepositoryIndexingService.class);
    private final RepositorySourceAcquisitionService acquisition;
    private final RepositorySourceChunker chunker;
    private final ObjectProvider<EmbeddingProvider> embeddings;
    private final RepositoryChunkStore chunks;
    private final RepositoryIndexJobs jobs;
    private final TransactionTemplate transaction;

    public RepositoryIndexingService(RepositorySourceAcquisitionService acquisition,RepositorySourceChunker chunker,
            ObjectProvider<EmbeddingProvider> embeddings,RepositoryChunkStore chunks,RepositoryIndexJobs jobs,TransactionTemplate transaction) {
        this.acquisition=acquisition; this.chunker=chunker; this.embeddings=embeddings;
        this.chunks=chunks; this.jobs=jobs; this.transaction=transaction;
    }
    public record Result(UUID repositoryId,String commitSha,int filesProcessed,int chunksIndexed) {}

    /** Synchronous entry point for local diagnostics; HTTP Import/Sync only enqueue work. */
    @Transactional(propagation=Propagation.NEVER)
    public Result index(UUID repositoryId,String commitSha) {
        if (repositoryId==null || commitSha==null || !commitSha.matches("[a-fA-F0-9]{40}"))
            throw Problem.bad("Expected repository ID and full 40-character commit SHA");
        String commit=commitSha.toLowerCase(Locale.ROOT);
        jobs.enqueue(repositoryId,CurrentUser.id(),commit);
        var claimed=jobs.claim(repositoryId);
        if (claimed.isEmpty()) {
            long count=chunks.countByRepositoryIdAndCommitShaAndPublishedTrue(repositoryId,commit);
            if (jobs.ready(repositoryId,CurrentUser.id(),commit)) return new Result(repositoryId,commit,0,Math.toIntExact(count));
            throw new Problem(HttpStatus.CONFLICT,"Repository is being indexed. You can keep using DevPilot.");
        }
        try { return build(claimed.get()); }
        catch (RuntimeException failure) { jobs.fail(claimed.get(),failure); throw failure; }
    }
    /** Network work is outside transactions. Only one file's chunks and vectors are held in memory. */
    @Transactional(propagation=Propagation.NEVER)
    public Result build(RepositoryIndexJobs.Job job) {
        check(job);
        long published=chunks.countByRepositoryIdAndCommitShaAndPublishedTrue(job.repositoryId(),job.commitSha());
        if (published>0) { jobs.usePublished(job); return new Result(job.repositoryId(),job.commitSha(),0,Math.toIntExact(published)); }
        EmbeddingProvider provider=embeddings.getIfAvailable();
        String model=RepositoryEmbedding.model(provider);
        String chunkingKey=chunker.configurationKey();
        int[] counts={0,0,0,0};
        acquisition.visit(job.repositoryId(),job.commitSha(),new RepositorySourceAcquisitionService.FileVisitor() {
            public boolean reuse(String path,String blobSha) {
                check(job);
                var match=chunks.findFirstByRepositoryIdAndBlobShaAndEmbeddingModelAndChunkingKeyAndPublishedTrue(
                        job.repositoryId(),blobSha,model,chunkingKey);
                if (match.isEmpty()) return false;
                var origin=match.get();
                var rows=chunks.findByRepositoryIdAndCommitShaAndSourcePathOrderByChunkIndexAsc(
                        job.repositoryId(),origin.commitSha,origin.sourcePath);
                if (rows.isEmpty() || rows.stream().anyMatch(row -> !row.published || !model.equals(row.embeddingModel)
                        || !chunkingKey.equals(row.chunkingKey) || !blobSha.equals(row.blobSha) || !valid(row))) return false;
                counts[0]++;
                copyFile(job,path,rows);
                counts[1]+=rows.size(); counts[2]+=rows.size();
                return true;
            }
            public void accept(RepositorySources.File file,String blobSha) {
                check(job); counts[0]++;
                var source=new RepositorySources(job.repositoryId(),job.commitSha(),List.of(file));
                for (var part:chunker.chunk(source)) {
                    check(job);
                    var cached=chunks.findFirstByRepositoryIdAndContentHashAndEmbeddingModelOrderByPublishedDesc(
                            job.repositoryId(),part.contentHash(),model);
                    float[] vector;
                    if (cached.isPresent() && part.content().equals(cached.get().content) && valid(cached.get())) {
                        vector=cached.get().embedding; counts[2]++;
                    } else {
                        vector=RepositoryEmbedding.vector(provider.embedDocument(SecretRedactor.clean(file.path()),SecretRedactor.clean(part.content())),model);
                        counts[3]++;
                    }
                    stage(job,file.path(),part.chunkIndex(),part.content(),part.contentHash(),model,vector,blobSha,chunkingKey);
                    counts[1]++;
                }
            }
        });
        jobs.complete(job,counts[1]);
        log.info("repository_index_completed repositoryId={} commitSha={} files={} chunks={} reusedChunks={} embeddedChunks={}",
                job.repositoryId(),job.commitSha(),counts[0],counts[1],counts[2],counts[3]);
        return new Result(job.repositoryId(),job.commitSha(),counts[0],counts[1]);
    }
    private void check(RepositoryIndexJobs.Job job) {
        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Indexing interrupted");
        transaction.executeWithoutResult(status -> jobs.guard(job));
    }
    private static boolean valid(RepositoryChunk row) {
        try { row.validate(); }
        catch (IllegalArgumentException ignored) { return false; }
        double norm=0; for (float value:row.embedding) norm+=(double)value*value;
        return norm>0 && Double.isFinite(norm);
    }
    /** Copy a complete unchanged file in one bounded transaction instead of one round trip per vector. */
    private void copyFile(RepositoryIndexJobs.Job job,String path,List<RepositoryChunk> originals) {
        transaction.executeWithoutResult(status -> {
            jobs.guard(job);
            Map<Integer,RepositoryChunk> existing=new HashMap<>();
            chunks.findByRepositoryIdAndCommitShaAndSourcePathOrderByChunkIndexAsc(job.repositoryId(),job.commitSha(),path)
                    .forEach(row -> existing.put(row.chunkIndex,row));
            List<RepositoryChunk> copies=new ArrayList<>(originals.size());
            for (var original:originals) {
                var row=existing.getOrDefault(original.chunkIndex,new RepositoryChunk());
                if (row.published && row.buildToken!=null) throw new IllegalStateException("Cannot replace published chunks");
                row.repositoryId=job.repositoryId(); row.commitSha=job.commitSha(); row.sourcePath=path; row.chunkIndex=original.chunkIndex;
                row.content=original.content; row.contentHash=original.contentHash; row.embeddingModel=original.embeddingModel;
                row.embedding=original.embedding.clone(); row.blobSha=original.blobSha; row.chunkingKey=original.chunkingKey;
                row.published=false; row.buildToken=job.token(); row.validate(); copies.add(row);
            }
            chunks.saveAllAndFlush(copies);
        });
    }
    private void stage(RepositoryIndexJobs.Job job,String path,int index,String content,String hash,String model,
            float[] vector,String blobSha,String chunkingKey) {
        transaction.executeWithoutResult(status -> {
            jobs.guard(job);
            var row=chunks.findByRepositoryIdAndCommitShaAndSourcePathAndChunkIndex(job.repositoryId(),job.commitSha(),path,index).orElseGet(RepositoryChunk::new);
            if (row.published && row.buildToken!=null) throw new IllegalStateException("Cannot replace published chunks");
            row.repositoryId=job.repositoryId(); row.commitSha=job.commitSha(); row.sourcePath=path; row.chunkIndex=index;
            row.content=content; row.contentHash=hash; row.embeddingModel=model; row.embedding=vector.clone();
            row.blobSha=blobSha; row.chunkingKey=chunkingKey; row.published=false; row.buildToken=job.token();
            row.validate(); chunks.saveAndFlush(row);
        });
    }
}
