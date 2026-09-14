package com.devpilot.ai;
import com.devpilot.repository.*;
import com.devpilot.analysis.*;
import com.devpilot.memory.*;
import java.util.*;
import org.springframework.stereotype.Service;
@Service public class ContextService {
    private final RepositoryStore repos;
    private final FindingStore findings;
    private final MemoryStore memories;
    private final AnalysisStore analyses;
    public ContextService(RepositoryStore r,FindingStore f,MemoryStore m,AnalysisStore a) {
        repos=r;
        findings=f;
        memories=m;
        analyses=a;
    }
    public String build(UUID repo) {
        TrackedRepository r=repos.findById(repo).orElseThrow();
        StringBuilder s=new StringBuilder("## Repository\n"+r.fullName+"\nLanguage: "+r.language+"\nDefault branch: "+r.defaultBranch+"\n"+r.description+"\n\n## README evidence\n"+Objects.toString(r.readme,"Not synced")+"\n\n## Recent findings\n");
        findings.findTop15ByRepositoryIdOrderByCreatedAtDesc(repo).forEach(f->s.append("- ").append(f.severity).append(" ").append(f.title).append(" [").append(f.file).append("] ").append(f.recommendation).append('\n'));
        s.append("\n## Recurring patterns\n");
        memories.findTop10ByRepositoryIdAndStatusOrderByOccurrencesDesc(repo,"ACTIVE").forEach(m->s.append("- ").append(m.description).append(" (seen ").append(m.occurrences).append(" analyses)\n"));
        analyses.findFirstByRepositoryIdAndStatusOrderByCreatedAtDesc(repo,"COMPLETED").ifPresent(a->s.append("\n## Latest analyzed change\n").append(a.kind).append(' ').append(a.ref).append('\n').append(a.summary).append("\n").append(limit(Objects.toString(a.diff,""),16000)));
        return limit(s.toString(),35000);
    }
    public static String limit(String s,int n) {
        return s.length()<=n?s:s.substring(0,n)+"\n[Context truncated]";
    }
}
