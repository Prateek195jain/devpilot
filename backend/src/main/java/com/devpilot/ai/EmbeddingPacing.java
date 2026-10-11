package com.devpilot.ai;

import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Shared document request budget for this Google provider instance, including retry attempts. */
@Component
public class EmbeddingPacing {
    private final Semaphore permits;
    private final int attempts;
    private final long intervalMs,rateLimitBaseMs,transientBaseMs,maxBackoffMs;
    private long nextRequestNanos,cooldownNanos;
    public EmbeddingPacing(@Value("${app.embedding.concurrency:1}") int concurrency,
            @Value("${app.embedding.min-interval-ms:1000}") long intervalMs,
            @Value("${app.embedding.max-attempts:3}") int attempts,
            @Value("${app.embedding.rate-limit-backoff-ms:2000}") long rateLimitBaseMs,
            @Value("${app.embedding.transient-backoff-ms:250}") long transientBaseMs,
            @Value("${app.embedding.max-backoff-ms:60000}") long maxBackoffMs) {
        if (concurrency<1 || concurrency>2 || intervalMs<0 || intervalMs>60000 || attempts<1 || attempts>5
                || rateLimitBaseMs<1 || transientBaseMs<1 || maxBackoffMs<rateLimitBaseMs
                || maxBackoffMs<transientBaseMs || maxBackoffMs>120000)
            throw new IllegalArgumentException("Invalid embedding retry or pacing policy");
        permits=new Semaphore(concurrency,true); this.intervalMs=intervalMs; this.attempts=attempts;
        this.rateLimitBaseMs=rateLimitBaseMs; this.transientBaseMs=transientBaseMs; this.maxBackoffMs=maxBackoffMs;
    }
    public static EmbeddingPacing defaults() { return new EmbeddingPacing(1,1000,3,2000,250,60000); }
    public long backoffMillis(EmbeddingFailure failure,int attempt) {
        long base=failure.kind()==EmbeddingFailure.Kind.RATE_LIMIT?rateLimitBaseMs:transientBaseMs;
        long exponential=Math.min(maxBackoffMs,base << Math.min(attempt-1,4));
        long requested=failure.retryAfterSeconds()==null?0:Math.min(maxBackoffMs/1000,Math.max(0,failure.retryAfterSeconds()))*1000;
        long minimum=Math.max(exponential,requested);
        long jitter=ThreadLocalRandom.current().nextLong(Math.max(1,minimum/4+1));
        return Math.min(maxBackoffMs,minimum+jitter);
    }
    public <T> T execute(Supplier<T> request) {
        boolean acquired=false;
        int attempt=1;
        try {
            permits.acquire(); acquired=true;
            for (; ; attempt++) {
                pace();
                try { return request.get(); }
                catch (EmbeddingFailure failure) {
                    long delay=backoffMillis(failure,attempt);
                    if (failure.kind()==EmbeddingFailure.Kind.RATE_LIMIT) {
                        synchronized (this) { cooldownNanos=Math.max(cooldownNanos,System.nanoTime()+delay*1_000_000); }
                    }
                    if (!failure.retryable() || attempt>=attempts) throw failure.withAttempts(attempt);
                    Thread.sleep(delay);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new EmbeddingFailure(EmbeddingFailure.Kind.TEMPORARY,null,null,attempt,"NETWORK");
        } finally { if (acquired) permits.release(); }
    }
    private void pace() throws InterruptedException {
        for (;;) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            long wait;
            synchronized (this) {
                long now=System.nanoTime(); wait=Math.max(nextRequestNanos,cooldownNanos)-now;
                if (wait<=0) { nextRequestNanos=now+intervalMs*1_000_000; return; }
            }
            java.util.concurrent.TimeUnit.NANOSECONDS.sleep(wait);
        }
    }
}
