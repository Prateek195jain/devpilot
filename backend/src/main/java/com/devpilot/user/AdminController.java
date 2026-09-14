package com.devpilot.user;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import com.devpilot.repository.RepositoryStore;
import com.devpilot.analysis.AnalysisStore;
@RestController @RequestMapping("/api/v1/admin") public class AdminController {
    private final UserStore users;
    private final RepositoryStore repos;
    private final AnalysisStore analyses;
    public AdminController(UserStore u,RepositoryStore r,AnalysisStore a) {
        users=u;
        repos=r;
        analyses=a;
    }
    @GetMapping("/stats") Map<String,Long> stats() {
        return Map.of("users",users.count(),"repositories",repos.count(),"analyses",analyses.count());
    }
}
