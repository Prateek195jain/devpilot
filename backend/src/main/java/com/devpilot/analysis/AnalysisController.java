package com.devpilot.analysis;
import com.devpilot.common.*;
import com.devpilot.repository.*;
import jakarta.validation.*;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
@RestController @RequestMapping("/api/v1/repositories/{repo}") public class AnalysisController {
    private final RepositoryAccess access;
    private final AnalysisStore store;
    private final AnalysisService service;
    private final FindingStore findings;
    public AnalysisController(RepositoryAccess a,AnalysisStore s,AnalysisService v,FindingStore f) {
        access=a;
        store=s;
        service=v;
        findings=f;
    }
    public record Request(@NotBlank @Pattern(regexp="PR|COMMIT")String kind,@NotBlank @Size(max=200)String ref) {
    }
    @PostMapping("/analyses") @ResponseStatus(HttpStatus.ACCEPTED) AnalysisView create(@PathVariable UUID repo,@Valid @RequestBody Request r) {
        access.require(repo);
        return service.enqueue(repo,r.kind(),r.ref());
    }
    @GetMapping("/analyses") Page<AnalysisView> list(@PathVariable UUID repo,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="30")int size) {
        access.require(repo);
        return store.findByRepositoryId(repo,Pages.of(page,size)).map(AnalysisView::of);
    }
    @GetMapping("/analyses/{id}") AnalysisView get(@PathVariable UUID repo,@PathVariable UUID id) {
        access.require(repo);
        return AnalysisView.of(store.findByIdAndRepositoryId(id,repo).orElseThrow(Problem::notFound));
    }
    @GetMapping("/findings") Page<FindingView> findings(@PathVariable UUID repo,@RequestParam(required=false)UUID analysisId,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="30")int size) {
        access.require(repo);
        if(analysisId!=null)store.findByIdAndRepositoryId(analysisId,repo).orElseThrow(Problem::notFound);
        return (analysisId==null?findings.findByRepositoryId(repo,Pages.of(page,size)):findings.findByRepositoryIdAndAnalysisId(repo,analysisId,Pages.of(page,size))).map(FindingView::of);
    }
}
