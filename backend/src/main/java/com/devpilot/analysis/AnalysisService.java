package com.devpilot.analysis;
import com.devpilot.common.*;
import com.devpilot.repository.*;
import com.devpilot.memory.*;
import com.devpilot.ai.*;
import java.util.*;
import java.time.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
@Service public class AnalysisService {
    private final AnalysisStore store;
    private final RepositoryStore repos;
    private final FindingStore findings;
    private final MemoryStore memories;
    public AnalysisService(AnalysisStore s,RepositoryStore r,FindingStore f,MemoryStore m) {
        store=s;
        repos=r;
        findings=f;
        memories=m;
    }
    @Transactional public AnalysisView enqueue(UUID repo,String kind,String ref) {
        repos.lock(repo).orElseThrow(Problem::notFound);
        if(!Set.of("PR","COMMIT").contains(kind)||!(kind.equals("PR")?ref.matches("[1-9][0-9]{0,9}"):ref.matches("[a-fA-F0-9]{7,40}")))throw Problem.bad("Expected PR number or commit SHA");
        var existing=store.findFirstByRepositoryIdAndKindAndRefOrderByCreatedAtDesc(repo,kind,ref);
        if(existing.isPresent()&&Set.of("QUEUED","RUNNING").contains(existing.get().status))return AnalysisView.of(existing.get());
        if(store.countByRepositoryIdAndCreatedAtAfter(repo,Instant.now().minusSeconds(86400))>=100)throw new Problem(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,"Repository analysis limit is 100 runs per day");
        Analysis a=new Analysis();
        a.repositoryId=repo;
        a.kind=kind;
        a.ref=ref;
        a.status="QUEUED";
        a.source="PENDING";
        return AnalysisView.of(store.save(a));
    }
    public record Job(UUID id,UUID repo,String kind,String ref,int attempt) {
    }
    @Transactional public Optional<Job> claim() {
        Instant now=Instant.now();
        store.recover(now,now.minusSeconds(900));
        var list=store.claim(now,PageRequest.of(0,1));
        if(list.isEmpty())return Optional.empty();
        Analysis a=list.get(0);
        a.status="RUNNING";
        a.startedAt=now;
        a.attempts++;
        return Optional.of(new Job(a.id,a.repositoryId,a.kind,a.ref,a.attempts));
    }
    @Transactional public void complete(Job job,AIProvider.Review review,String diff,String source,boolean truncated) {
        Analysis a=store.lock(job.id()).orElseThrow();
        if(!a.status.equals("RUNNING")||a.attempts!=job.attempt())return;
        repos.lock(a.repositoryId).orElseThrow();
        Set<String> seen=new HashSet<>();
        for(var i:review.findings()) {
            String fp=Crypto.hash(i.type()+"|"+i.file()+"|"+i.title().strip().toLowerCase(Locale.ROOT));
            if(!seen.add(fp))continue;
            Finding f=new Finding();
            f.analysisId=a.id;
            f.repositoryId=a.repositoryId;
            f.type=i.type();
            f.severity=i.severity();
            f.title=i.title();
            f.description=i.description();
            f.file=i.file();
            f.line=i.line();
            f.recommendation=i.recommendation();
            f.confidence=i.confidence();
            f.source=source;
            f.fingerprint=fp;
            findings.save(f);
            EngineeringMemory m=memories.findByRepositoryIdAndFingerprint(a.repositoryId,fp).orElseGet(EngineeringMemory::new);
            m.repositoryId=a.repositoryId;
            m.fingerprint=fp;
            m.type=i.type();
            m.description=i.title()+" in "+i.file();
            m.occurrences++;
            m.lastSeen=Instant.now();
            if(m.status==null)m.status="ACTIVE";
            memories.save(m);
        }
        a.status="COMPLETED";
        a.summary=(truncated?"PARTIAL COVERAGE: diff omitted binary or oversized content. ":"")+review.summary();
        a.diff=diff;
        a.source=source;
        a.completedAt=Instant.now();
        a.error=null;
    }
    @Transactional public void fail(Job job) {
        Analysis a=store.lock(job.id()).orElseThrow();
        if(a.attempts!=job.attempt()||!a.status.equals("RUNNING"))return;
        a.status=a.attempts>=3?"FAILED":"QUEUED";
        a.nextAttemptAt=Instant.now().plusSeconds(30L*a.attempts);
        a.error="Analysis failed. Check GitHub access and AI configuration; provider details are withheld.";
        if(a.status.equals("FAILED"))a.completedAt=Instant.now();
    }
}
