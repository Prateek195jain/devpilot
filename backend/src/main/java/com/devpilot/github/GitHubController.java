package com.devpilot.github;
import com.devpilot.common.*;
import jakarta.validation.*;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
@RestController @RequestMapping("/api/v1/github") public class GitHubController {
    private final IntegrationService integration;
    private final SourceControlProvider provider;
    public GitHubController(IntegrationService i,SourceControlProvider p) {
        integration=i;
        provider=p;
    }
    public record Connect(@NotBlank @Size(max=500)String token) {
    }
    @GetMapping IntegrationService.Status status() {
        return integration.status(CurrentUser.id());
    }
    @PostMapping IntegrationService.Status connect(@Valid @RequestBody Connect r) {
        return integration.connect(CurrentUser.id(),r.token());
    }
    @DeleteMapping @ResponseStatus(HttpStatus.NO_CONTENT) void disconnect() {
        integration.disconnect(CurrentUser.id());
    }
    @GetMapping("/repositories") List<SourceControlProvider.Repo> repositories(@RequestParam(defaultValue="1")int page) {
        if(page<1||page>1000)throw Problem.bad("Invalid page");
        return provider.repositories(integration.token(CurrentUser.id()),page);
    }
}
