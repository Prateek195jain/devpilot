package com.devpilot.technicaldebt;
import com.devpilot.common.*;
import com.devpilot.repository.*;
import com.devpilot.analysis.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service public class DebtService {
    private final DebtStore debts;
    private final FindingStore findings;
    private final RepositoryStore repos;
    public DebtService(DebtStore d,FindingStore f,RepositoryStore r) {
        debts=d;
        findings=f;
        repos=r;
    }
    @Transactional public TechnicalDebtView create(UUID repo,String title,String description,String category,String severity,String effort,UUID source) {
        repos.lock(repo).orElseThrow(Problem::notFound);
        TechnicalDebt d=new TechnicalDebt();
        d.repositoryId=repo;
        d.title=title;
        d.description=description;
        d.category=category;
        d.severity=severity;
        d.estimatedEffort=effort;
        d.status="OPEN";
        d.updatedAt=Instant.now();
        if(source!=null) {
            var old=debts.findByRepositoryIdAndSourceFindingId(repo,source);
            if(old.isPresent())return TechnicalDebtView.of(old.get());
            Finding f=findings.findByIdAndRepositoryId(source,repo).orElseThrow(Problem::notFound);
            d.title=f.title;
            d.description=f.description+"\nRecommendation: "+f.recommendation;
            d.category=f.type;
            d.severity=f.severity;
            d.sourceFindingId=f.id;
        }
        return TechnicalDebtView.of(debts.save(d));
    }
    @Transactional public TechnicalDebtView update(UUID repo,UUID id,String status,String effort) {
        TechnicalDebt d=debts.findByIdAndRepositoryId(id,repo).orElseThrow(Problem::notFound);
        d.status=status;
        d.estimatedEffort=effort;
        d.updatedAt=Instant.now();
        d.resolvedAt=status.equals("RESOLVED")?(d.resolvedAt==null?Instant.now():d.resolvedAt):null;
        return TechnicalDebtView.of(d);
    }
    public record Stats(long total,long open,long inProgress,long resolved,long criticalFindings,Map<String,Long> bySeverity,Map<String,Long> byCategory) {
    }
    public Stats stats(UUID repo) {
        return new Stats(debts.countByRepositoryId(repo),debts.countByRepositoryIdAndStatus(repo,"OPEN"),debts.countByRepositoryIdAndStatus(repo,"IN_PROGRESS"),debts.countByRepositoryIdAndStatus(repo,"RESOLVED"),findings.countByRepositoryIdAndSeverity(repo,"CRITICAL"),map(debts.severities(repo)),map(debts.categories(repo)));
    }
    private Map<String,Long> map(List<Object[]> rows) {
        Map<String,Long> out=new LinkedHashMap<>();
        rows.forEach(r->out.put((String)r[0],((Number)r[1]).longValue()));
        return out;
    }
}
