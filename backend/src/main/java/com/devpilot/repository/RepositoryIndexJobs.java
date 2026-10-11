package com.devpilot.repository;

import com.devpilot.ai.EmbeddingFailure;
import com.devpilot.common.Problem;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable queue on the repository row. Every candidate write and publication is fenced by its lease token. */
@Service
public class RepositoryIndexJobs {
    public record Job(UUID repositoryId, UUID ownerId, String commitSha, UUID token) {}
    private final RepositoryStore repositories;
    private final RepositoryChunkStore chunks;
    private final TransactionTemplate transaction;
    private final int leaseSeconds;

    public RepositoryIndexJobs(RepositoryStore repositories, RepositoryChunkStore chunks, TransactionTemplate transaction,
            @Value("${app.indexing.lease-seconds:300}") int leaseSeconds) {
        if (leaseSeconds < 120 || leaseSeconds > 3600) throw new IllegalArgumentException("Invalid indexing lease duration");
        this.repositories= repositories; this.chunks=chunks; this.transaction=transaction; this.leaseSeconds=leaseSeconds;
    }
    int leaseSeconds() { return leaseSeconds; }
    public void enqueue(UUID id, UUID owner, String commit) {
        if (commit == null || !commit.matches("[0-9a-f]{40}")) throw Problem.bad("Expected immutable commit SHA");
        transaction.executeWithoutResult(status -> {
            var r=owned(id,owner);
            boolean active=r.indexingJobToken!=null && r.indexingLeaseUntil!=null && r.indexingLeaseUntil.isAfter(Instant.now());
            if (!active && (commit.equals(r.latestIndexedCommitSha)
                    || (r.indexingStatus==IndexingStatus.READY && commit.equals(r.indexingTargetCommitSha)))) {
                r.indexingStatus=IndexingStatus.READY; r.indexingTargetCommitSha=commit; r.lastIndexingErrorCategory=null;
                r.indexingJobToken=null; r.indexingLeaseUntil=null; return;
            }
            r.indexingTargetCommitSha=commit; r.indexingStatus=IndexingStatus.INDEXING; r.lastIndexingErrorCategory=null;
        });
    }
    public boolean ready(UUID id,UUID owner,String commit) {
        var r=repositories.findByIdAndOwnerId(id,owner).orElseThrow(Problem::notFound);
        return r.indexingStatus==IndexingStatus.READY && commit.equals(r.indexingTargetCommitSha);
    }
    public List<UUID> pending(int limit) { return repositories.pendingIndexJobs(Instant.now(),PageRequest.of(0,limit)); }
    public Optional<Job> claim(UUID id) {
        return transaction.execute(status -> {
            var r=repositories.lock(id).orElse(null);
            if (r==null || r.indexingStatus!=IndexingStatus.INDEXING || r.indexingTargetCommitSha==null) return Optional.empty();
            if (r.indexingJobToken!=null && r.indexingLeaseUntil!=null && r.indexingLeaseUntil.isAfter(Instant.now())) return Optional.empty();
            r.indexingJobToken=UUID.randomUUID(); r.indexingLeaseUntil=Instant.now().plusSeconds(leaseSeconds);
            return Optional.of(new Job(id,r.ownerId,r.indexingTargetCommitSha,r.indexingJobToken));
        });
    }
    public void heartbeat(Job job) {
        transaction.executeWithoutResult(status -> guard(job).indexingLeaseUntil=Instant.now().plusSeconds(leaseSeconds));
    }
    // Must be called inside the same short transaction as each candidate write.
    public TrackedRepository guard(Job job) {
        var r=owned(job.repositoryId(),job.ownerId());
        if (!job.token().equals(r.indexingJobToken)) throw new Problem(HttpStatus.CONFLICT,"Indexing job superseded");
        return r;
    }
    private TrackedRepository owned(UUID id,UUID owner) {
        var r=repositories.lock(id).orElseThrow(Problem::notFound);
        if (!owner.equals(r.ownerId)) throw Problem.notFound();
        return r;
    }
    public void complete(Job job,int expected) {
        transaction.executeWithoutResult(status -> {
            var r=guard(job);
            if (chunks.countByRepositoryIdAndCommitShaAndBuildToken(job.repositoryId(),job.commitSha(),job.token())!=expected)
                throw new IllegalStateException("Incomplete candidate index");
            chunks.deleteUnvisited(job.repositoryId(),job.commitSha(),job.token());
            chunks.publish(job.repositoryId(),job.commitSha(),job.token());
            r.latestIndexedCommitSha=expected==0?null:job.commitSha();
            finish(r,job,null);
        });
    }
    public void usePublished(Job job) {
        transaction.executeWithoutResult(status -> { var r=guard(job); r.latestIndexedCommitSha=job.commitSha(); finish(r,job,null); });
    }
    public void fail(Job job,RuntimeException failure) {
        transaction.executeWithoutResult(status -> {
            var r=repositories.lock(job.repositoryId()).orElse(null);
            if (r==null || !job.ownerId().equals(r.ownerId) || !job.token().equals(r.indexingJobToken)) return;
            IndexingErrorCategory category=IndexingErrorCategory.INTERNAL;
            if (failure instanceof EmbeddingFailure embedding) {
                try { category=IndexingErrorCategory.valueOf(embedding.category()); }
                catch (IllegalArgumentException ignored) { category=IndexingErrorCategory.PROVIDER_UNAVAILABLE; }
            } else if (failure instanceof Problem) category=IndexingErrorCategory.SOURCE_UNAVAILABLE;
            finish(r,job,category);
        });
    }
    private void finish(TrackedRepository r,Job job,IndexingErrorCategory error) {
        r.indexingJobToken=null; r.indexingLeaseUntil=null;
        if (!job.commitSha().equals(r.indexingTargetCommitSha)) {
            r.indexingStatus=IndexingStatus.INDEXING; r.lastIndexingErrorCategory=null;
        } else { r.indexingStatus=error==null?IndexingStatus.READY:IndexingStatus.FAILED; r.lastIndexingErrorCategory=error; }
    }
}
