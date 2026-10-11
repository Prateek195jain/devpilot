package com.devpilot.ai;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EmbeddingPacingTest {
    @Test void rateLimitUsesSecondsExponentialBackoffJitterAndBoundedRetryAfter() {
        var policy=EmbeddingPacing.defaults();
        var failure=new EmbeddingFailure(EmbeddingFailure.Kind.RATE_LIMIT);
        long first=policy.backoffMillis(failure,1),second=policy.backoffMillis(failure,2);
        assertTrue(first>=2000 && first<=2500); assertTrue(second>=4000 && second<=5000);
        long header=policy.backoffMillis(new EmbeddingFailure(EmbeddingFailure.Kind.RATE_LIMIT,17L),1);
        assertTrue(header>=17000 && header<=21250);
        assertEquals(60000,policy.backoffMillis(new EmbeddingFailure(EmbeddingFailure.Kind.RATE_LIMIT,86400L),1));
        long temporary=policy.backoffMillis(new EmbeddingFailure(EmbeddingFailure.Kind.TEMPORARY),1);
        assertTrue(temporary>=250 && temporary<=312);
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={1,2})
    void configuredConcurrencyBoundsRequestsAcrossJobs(int concurrency) throws Exception {
        var policy=new EmbeddingPacing(concurrency,0,3,1,1,5);
        var active=new AtomicInteger(); var maximum=new AtomicInteger(); var total=new AtomicInteger();
        var entered=new CountDownLatch(concurrency); var release=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(3);
        try {
            var tasks=new java.util.ArrayList<Future<Integer>>();
            for(int i=0;i<3;i++) tasks.add(pool.submit(() -> policy.execute(() -> {
                int now=active.incrementAndGet(); maximum.accumulateAndGet(now,Math::max); entered.countDown();
                try { assertTrue(release.await(5,TimeUnit.SECONDS)); return total.incrementAndGet(); }
                catch(InterruptedException e) { throw new AssertionError(e); } finally { active.decrementAndGet(); }
            })));
            assertTrue(entered.await(5,TimeUnit.SECONDS)); assertEquals(concurrency,active.get()); release.countDown();
            for(var task:tasks) task.get(5,TimeUnit.SECONDS);
            assertEquals(concurrency,maximum.get()); assertEquals(3,total.get());
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void configuredMinimumIntervalAppliesToEveryAttempt() {
        var policy=new EmbeddingPacing(1,50,3,1,1,5); var starts=new java.util.ArrayList<Long>();
        policy.execute(() -> { starts.add(System.nanoTime()); return 1; });
        policy.execute(() -> { starts.add(System.nanoTime()); return 1; });
        assertTrue(TimeUnit.NANOSECONDS.toMillis(starts.get(1)-starts.get(0))>=45);
    }
    @Test void permanentFailureNeverRetriesAndPermitIsReleased() {
        var policy=new EmbeddingPacing(1,0,3,1,1,5); var count=new AtomicInteger();
        assertThrows(EmbeddingFailure.class,() -> policy.execute(() -> { count.incrementAndGet(); throw new EmbeddingFailure(EmbeddingFailure.Kind.AUTHENTICATION); }));
        assertEquals(1,count.get()); assertEquals(42,policy.execute(() -> 42));
    }
    @Test void exhaustedRateLimitStillSetsCooldownForOtherJobs() {
        var policy=new EmbeddingPacing(1,0,1,50,1,100);
        assertThrows(EmbeddingFailure.class,() -> policy.execute(() -> { throw new EmbeddingFailure(EmbeddingFailure.Kind.RATE_LIMIT); }));
        long start=System.nanoTime(); assertEquals(42,policy.execute(() -> 42));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)>=45);
    }
    @Test void invalidSettingsAreRejected() {
        assertThrows(IllegalArgumentException.class,() -> new EmbeddingPacing(3,0,3,2000,250,60000));
        assertThrows(IllegalArgumentException.class,() -> new EmbeddingPacing(1,0,6,2000,250,60000));
        assertThrows(IllegalArgumentException.class,() -> new EmbeddingPacing(1,0,3,2000,250,100));
    }
}
