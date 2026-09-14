package com.devpilot.technicaldebt;
import com.devpilot.common.*;
import com.devpilot.repository.*;
import java.util.*;
import jakarta.validation.*;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Page;
@RestController @RequestMapping("/api/v1/repositories/{repo}") public class DebtController {
    private final RepositoryAccess access;
    private final DebtStore store;
    private final DebtService service;
    public DebtController(RepositoryAccess a,DebtStore s,DebtService v) {
        access=a;
        store=s;
        service=v;
    }
    public record Create(@NotBlank @Size(max=200)String title,@NotNull @Size(max=10000)String description,@NotBlank @Size(max=50)String category,@NotNull @Pattern(regexp="CRITICAL|HIGH|MEDIUM|LOW")String severity,@NotNull @Size(max=100)String estimatedEffort,UUID sourceFindingId) {
    }
    public record Update(@NotNull @Pattern(regexp="OPEN|IN_PROGRESS|RESOLVED|IGNORED")String status,@NotNull @Size(max=100)String estimatedEffort) {
    }
    @GetMapping("/technical-debt") Page<TechnicalDebtView> list(@PathVariable UUID repo,@RequestParam(required=false)String status,@RequestParam(required=false)String severity,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="30")int size) {
        access.require(repo);
        return store.search(repo,status,severity,Pages.of(page,size)).map(TechnicalDebtView::of);
    }
    @PostMapping("/technical-debt") @ResponseStatus(HttpStatus.CREATED) TechnicalDebtView create(@PathVariable UUID repo,@Valid @RequestBody Create r) {
        access.require(repo);
        return service.create(repo,r.title(),r.description(),r.category(),r.severity(),r.estimatedEffort(),r.sourceFindingId());
    }
    @PatchMapping("/technical-debt/{id}") TechnicalDebtView update(@PathVariable UUID repo,@PathVariable UUID id,@Valid @RequestBody Update r) {
        access.require(repo);
        return service.update(repo,id,r.status(),r.estimatedEffort());
    }
    @GetMapping("/stats") DebtService.Stats stats(@PathVariable UUID repo) {
        access.require(repo);
        return service.stats(repo);
    }
}
