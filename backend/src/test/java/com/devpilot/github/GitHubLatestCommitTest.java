package com.devpilot.github;

import com.devpilot.common.Problem;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

class GitHubLatestCommitTest {
    @Test void resolvesExplicitDefaultBranchWithEncodedSlashAndNormalizesFullSha() {
        var adapter = new GitHubSourceControlAdapter(WebClient.builder().baseUrl("https://api.github.com")
                .exchangeFunction(request -> {
                    assertEquals("https://api.github.com/repos/o/r/commits/release%2Fcurrent", request.url().toString());
                    assertEquals("Bearer token", request.headers().getFirst("Authorization"));
                    return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                            .body("{\"sha\":\"" + "A".repeat(40) + "\"}").build());
                }).build());
        assertEquals("a".repeat(40), adapter.latestCommitSha("token", "o/r", "release/current"));
    }

    @Test void rejectsMissingBranchAndInvalidUpstreamSha() {
        AtomicInteger calls = new AtomicInteger();
        var adapter = new GitHubSourceControlAdapter(WebClient.builder().baseUrl("https://api.github.com")
                .exchangeFunction(request -> {
                    calls.incrementAndGet();
                    return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                            .body("{\"sha\":\"main\"}").build());
                }).build());
        assertThrows(Problem.class, () -> adapter.latestCommitSha("token", "o/r", null));
        assertThrows(Problem.class, () -> adapter.latestCommitSha("token", "o/r", " "));
        assertEquals(0, calls.get());
        assertEquals(HttpStatus.BAD_GATEWAY,
                assertThrows(Problem.class, () -> adapter.latestCommitSha("token", "o/r", "main")).status);
    }
}
