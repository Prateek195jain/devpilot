package com.devpilot.github;

import com.devpilot.common.Problem;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

class GitHubSourceAcquisitionTest {
    private static final String COMMIT = "a".repeat(40), TREE = "b".repeat(40), BLOB = "c".repeat(40);

    @Test void existingAdapterUsesImmutableObjectEndpointsAndExistingAuthentication() {
        List<String> paths = new ArrayList<>();
        Map<String, String> responses = Map.of(
            "/repos/o/r/git/commits/" + COMMIT, "{\"sha\":\"" + COMMIT + "\",\"tree\":{\"sha\":\"" + TREE + "\"}}",
            "/repos/o/r/git/trees/" + TREE, "{\"sha\":\"" + TREE + "\",\"truncated\":false,\"tree\":[{\"path\":\"A.java\",\"type\":\"blob\",\"mode\":\"100644\",\"sha\":\"" + BLOB + "\",\"size\":2}]}",
            "/repos/o/r/git/blobs/" + BLOB, "{\"sha\":\"" + BLOB + "\",\"encoding\":\"base64\",\"size\":2,\"content\":\"b2s=\\n\"}");
        GitHubSourceControlAdapter adapter = new GitHubSourceControlAdapter(WebClient.builder().baseUrl("https://api.github.com").exchangeFunction(request -> {
            assertEquals("Bearer token", request.headers().getFirst("Authorization"));
            assertNull(request.url().getQuery());
            paths.add(request.url().getPath());
            String body = responses.get(request.url().getPath());
            assertNotNull(body);
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json").body(body).build());
        }).build());
        String tree = adapter.commitTree("token", "o/r", COMMIT);
        var entries = adapter.tree("token", "o/r", tree);
        assertArrayEquals(new byte[] {'o', 'k'}, adapter.blob("token", "o/r", entries.getFirst().sha()));
        assertEquals(List.of("/repos/o/r/git/commits/" + COMMIT, "/repos/o/r/git/trees/" + TREE, "/repos/o/r/git/blobs/" + BLOB), paths);
    }

    @Test void failsClosedOnTruncatedTreesAndCommitMismatch() {
        GitHubSourceControlAdapter adapter = new GitHubSourceControlAdapter(WebClient.builder().exchangeFunction(request -> Mono.just(
            ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                .body("{\"sha\":\"" + TREE + "\",\"truncated\":true,\"tree\":[]}").build())).build());
        assertThrows(Problem.class, () -> adapter.tree("token", "o/r", TREE));
        assertThrows(Problem.class, () -> adapter.commitTree("token", "o/r", COMMIT));
        assertThrows(Problem.class, () -> adapter.commitTree("token", "o/r", "main"));
    }
}
