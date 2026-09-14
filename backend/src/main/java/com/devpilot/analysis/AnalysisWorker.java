package com.devpilot.analysis;
import com.devpilot.github.*;
import com.devpilot.repository.*;
import com.devpilot.ai.*;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.*;
@Component public class AnalysisWorker {
    private final AnalysisService service;
    private final RepositoryStore repos;
    private final IntegrationService integration;
    private final SourceControlProvider github;
    private final AIProvider ai;
    private final ContextService context;
    private static final Logger log=LoggerFactory.getLogger(AnalysisWorker.class);
    public AnalysisWorker(AnalysisService s,RepositoryStore r,IntegrationService i,SourceControlProvider g,AIProvider a,ContextService c) {
        service=s;
        repos=r;
        integration=i;
        github=g;
        ai=a;
        context=c;
    }
    @Scheduled(fixedDelayString="${app.worker-delay:2000}") public void tick() {
        service.claim().ifPresent(job-> {
            try {
                var r=repos.findById(job.repo()).orElseThrow();var snapshot=github.snapshot(integration.token(r.ownerId),r.fullName,job.kind(),job.ref());var review=ai.analyze(snapshot.diff(),context.build(r.id));service.complete(job,review,snapshot.diff(),ai.source(),snapshot.truncated());log.info("analysis_completed analysisId={} source={}",job.id(),ai.source());
            }
            catch(RuntimeException e) {
                service.fail(job);log.warn("analysis_failed analysisId={} attempt={} type={}",job.id(),job.attempt(),e.getClass().getSimpleName());
            }
        }
        );
    }
}
