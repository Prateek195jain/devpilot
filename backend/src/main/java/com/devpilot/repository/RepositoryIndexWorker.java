package com.devpilot.repository;

import com.devpilot.ai.EmbeddingFailure;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class RepositoryIndexWorker {
    private static final Logger log=LoggerFactory.getLogger(RepositoryIndexWorker.class);
    private final RepositoryIndexJobs jobs;
    private final RepositoryIndexingService indexing;
    private final ExecutorService executor;
    private final int concurrency;
    private final ConcurrentMap<java.util.UUID,RepositoryIndexJobs.Job> active=new ConcurrentHashMap<>();
    public RepositoryIndexWorker(RepositoryIndexJobs jobs,RepositoryIndexingService indexing,int concurrency) {
        this(jobs,indexing,concurrency,2000);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public RepositoryIndexWorker(RepositoryIndexJobs jobs,RepositoryIndexingService indexing,
            @Value("${app.indexing.workers:1}") int concurrency,@Value("${app.indexing.poll-ms:2000}") long pollMs) {
        if (pollMs<100 || pollMs>Math.min(30000,jobs.leaseSeconds()*1000L/3))
            throw new IllegalArgumentException("Invalid indexing polling/heartbeat interval");
        if (concurrency<1 || concurrency>4) throw new IllegalArgumentException("Indexing workers must be between 1 and 4");
        this.jobs=jobs; this.indexing=indexing; this.concurrency=concurrency;
        executor=Executors.newFixedThreadPool(concurrency,Thread.ofPlatform().name("repository-index-",0).factory());
    }
    @Scheduled(fixedDelayString="${app.indexing.poll-ms:2000}",scheduler="repositoryIndexScheduler")
    public synchronized void tick() {
        for (var job:active.values()) {
            try { jobs.heartbeat(job); }
            catch (RuntimeException failure) { log.warn("Indexing heartbeat failed repositoryId={} type={}",job.repositoryId(),failure.getClass().getSimpleName()); }
        }
        int available=concurrency-active.size();
        if (available<=0 || executor.isShutdown()) return;
        for (var id:jobs.pending(available)) {
            var claimed=jobs.claim(id);
            if (claimed.isEmpty()) continue;
            var job=claimed.get(); active.put(id,job);
            try { executor.execute(() -> run(job)); }
            catch (RejectedExecutionException failure) { active.remove(id,job); jobs.fail(job,failure); }
        }
    }
    private void run(RepositoryIndexJobs.Job job) {
        // A fresh internal context contains only the persisted owner identity, never a request bearer or credentials.
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(job.ownerId().toString(),null,List.of()));
        SecurityContextHolder.setContext(context);
        try { indexing.build(job); }
        catch (RuntimeException failure) {
            try { jobs.fail(job,failure); }
            catch (RuntimeException recordingFailure) { log.warn("Indexing failure recording failed repositoryId={} type={}",job.repositoryId(),recordingFailure.getClass().getSimpleName()); }
            if (failure instanceof EmbeddingFailure embedding)
                log.warn("Repository indexing attempt ended repositoryId={} {}",job.repositoryId(),embedding.diagnostic());
            else log.warn("Repository indexing failed repositoryId={} type={}",job.repositoryId(),failure.getClass().getSimpleName());
        } finally { SecurityContextHolder.clearContext(); active.remove(job.repositoryId(),job); }
    }
    @PreDestroy public void stop() { executor.shutdownNow(); }
    @Configuration
    static class SchedulerConfiguration {
        @Bean(name="taskScheduler") ThreadPoolTaskScheduler applicationTaskScheduler() {
            var scheduler=new ThreadPoolTaskScheduler(); scheduler.setPoolSize(2); scheduler.setThreadNamePrefix("application-schedule-"); return scheduler;
        }
        @Bean(name="repositoryIndexScheduler") ThreadPoolTaskScheduler repositoryIndexScheduler() {
            var scheduler=new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("index-dispatch-"); return scheduler;
        }
    }
}
