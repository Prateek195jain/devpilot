package com.devpilot.ai;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

class GoogleEmbeddingProviderTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private ExecutorService executor;
    private String url;
    private volatile String response;
    private volatile int status = 200;
    private final java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
    private volatile int recoverAfter = Integer.MAX_VALUE;
    private volatile long delay;
    private volatile String retryAfter;
    private final AtomicReference<Captured> captured = new AtomicReference<>();
    record Captured(String method, String uri, String key, JsonNode body) {}

    @BeforeEach void start() throws Exception {
        response = mapper.writeValueAsString(Map.of("embedding", Map.of("values", Collections.nCopies(768, 0.01))));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            int requestNumber = requests.incrementAndGet();
            captured.set(new Captured(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                    exchange.getRequestHeaders().getFirst("x-goog-api-key"), mapper.readTree(exchange.getRequestBody())));
            try {
                if (delay > 0) Thread.sleep(delay);
                byte[] bytes = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                if (retryAfter != null) exchange.getResponseHeaders().set("Retry-After", retryAfter);
                exchange.sendResponseHeaders(requestNumber > recoverAfter ? 200 : status, bytes.length);
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta";
    }
    @AfterEach void stop() { server.stop(0); executor.shutdownNow(); }
    private GoogleEmbeddingProvider provider() { return new GoogleEmbeddingProvider("test-only-key", url, "gemini-embedding-2", 768, 2); }


    @ParameterizedTest @ValueSource(ints={429,503})
    void transientDocumentFailureRetriesAndSucceeds(int code) {
        status = code; recoverAfter = 2;
        assertEquals(768, provider().embedDocument("file", "private source").dimensions());
        assertEquals(3, requests.get());
    }
    @ParameterizedTest @ValueSource(ints={429,503})
    void exhaustedDocumentRetriesHaveSafeDiagnostics(int code) {
        status = code; response = "test-only-key private source";
        var failure = assertThrows(EmbeddingFailure.class, () -> provider().embedDocument("file", "private source"));
        assertEquals(3, requests.get()); assertEquals(3, failure.attempts()); assertEquals(code, failure.httpStatus());
        assertTrue(failure.diagnostic().contains(code == 429 ? "category=RATE_LIMIT" : "category=PROVIDER_UNAVAILABLE"));
        assertFalse(failure.diagnostic().contains("test-only-key")); assertFalse(failure.diagnostic().contains("private source"));
        assertNull(failure.getCause());
    }
    @ParameterizedTest @ValueSource(ints={400,401,403})
    void permanentDocumentFailureIsNotRetried(int code) {
        status = code;
        assertEquals(1, assertThrows(EmbeddingFailure.class, () -> provider().embedDocument("file", "source")).attempts());
        assertEquals(1, requests.get());
    }
    @Test void malformedDocumentIsNotRetried() {
        response = "not JSON";
        assertThrows(EmbeddingFailure.class, () -> provider().embedDocument("file", "source"));
        assertEquals(1, requests.get());
    }
    @Test void incorrectDimensionDocumentIsNotRetried() {
        response = "{\"embedding\":{\"values\":[1]}}";
        assertThrows(EmbeddingFailure.class, () -> provider().embedDocument("file", "source"));
        assertEquals(1, requests.get());
    }
    @Test void documentTimeoutRetriesThreeTimes() {
        delay = 2000;
        var p = new GoogleEmbeddingProvider("test-only-key", url, "gemini-embedding-2", 768, 1);
        var failure = assertThrows(EmbeddingFailure.class, () -> p.embedDocument("file", "source"));
        assertEquals(3, failure.attempts()); assertEquals(3, requests.get());
        assertTrue(failure.diagnostic().contains("category=TIMEOUT"));
    }
    @Test void documentConnectionFailureRetriesThreeTimes() {
        server.stop(0);
        var failure = assertThrows(EmbeddingFailure.class, () -> provider().embedDocument("file", "source"));
        assertEquals(3, failure.attempts()); assertTrue(failure.diagnostic().contains("category=NETWORK"));
    }
    @Test void documentAndCodeQueryFollowGoogleContractAndKeepKeyOutOfPayload() {
        var provider = provider();
        var embedding = provider.embedDocument("src/Auth.java", "class Auth {} ghp_abcdefghijklmnopqrstuvwxyz");
        var request = captured.get();
        assertEquals("POST", request.method());
        assertEquals("/v1beta/models/gemini-embedding-2:embedContent", request.uri());
        assertEquals("test-only-key", request.key());
        assertEquals("models/gemini-embedding-2", request.body().path("model").asText());
        assertEquals(768, request.body().path("outputDimensionality").asInt());
        assertFalse(request.body().has("taskType"));
        assertFalse(request.body().has("title"));
        assertFalse(request.body().toString().contains("test-only-key"));
        assertEquals("title: src/Auth.java | text: class Auth {} [REDACTED_TOKEN]", text(request));
        assertEquals("gemini-embedding-2", embedding.model());
        assertEquals(768, embedding.dimensions());
        assertEquals(768, embedding.values().size());
        assertThrows(UnsupportedOperationException.class, () -> embedding.values().add(1.0));
        provider.embedDocument(null, "source");
        assertEquals("title: none | text: source", text(captured.get()));
        provider.embedQuery("Where is authentication implemented?");
        assertEquals("task: code retrieval | query: Where is authentication implemented?", text(captured.get()));
    }
    private String text(Captured request) { return request.body().path("content").path("parts").get(0).path("text").asText(); }

    @ParameterizedTest @ValueSource(strings={"{}", "null", "not JSON", "{\"embeddings\":[{\"values\":[1]}]}",
            "{\"embedding\":{\"values\":[1]}}", "{\"embedding\":{\"values\":null}}"})
    void rejectsMalformedResponse(String body) {
        response = body;
        assertEquals(EmbeddingFailure.Kind.INVALID_RESPONSE, assertThrows(EmbeddingFailure.class, () -> provider().embedQuery("query")).kind());
    }
    @ParameterizedTest @ValueSource(strings={"null", "\"0.1\"", "1e999", "\"NaN\"", "\"Infinity\""})
    void rejectsInvalidVectorValues(String value) {
        response = "{\"embedding\":{\"values\":[" + value + "," + String.join(",", Collections.nCopies(767, "0.01")) + "]}}";
        assertEquals(EmbeddingFailure.Kind.INVALID_RESPONSE, assertThrows(EmbeddingFailure.class, () -> provider().embedQuery("query")).kind());
    }
    @Test void rejectsZeroVectorAndErrorEnvelope() throws Exception {
        response = mapper.writeValueAsString(Map.of("embedding", Map.of("values", Collections.nCopies(768, 0))));
        assertEquals(EmbeddingFailure.Kind.INVALID_RESPONSE, assertThrows(EmbeddingFailure.class, () -> provider().embedQuery("query")).kind());
        response = "{\"error\":{\"message\":\"test-only-key\"}}";
        assertEquals(EmbeddingFailure.Kind.INVALID_RESPONSE, assertThrows(EmbeddingFailure.class, () -> provider().embedQuery("query")).kind());
    }
    @ParameterizedTest @ValueSource(ints={401,403,429,500,502,503,408,400,404,302})
    void classifiesErrorsWithoutRetainingProviderDetails(int code) {
        status = code; response = "test-only-key private source provider details"; retryAfter = "17";
        var failure = assertThrows(EmbeddingFailure.class, () -> provider().embedQuery("query"));
        var expected = code == 401 || code == 403 ? EmbeddingFailure.Kind.AUTHENTICATION
                : code == 429 ? EmbeddingFailure.Kind.RATE_LIMIT
                : code >= 500 || code == 408 ? EmbeddingFailure.Kind.TEMPORARY : EmbeddingFailure.Kind.INVALID_REQUEST;
        assertEquals(expected, failure.kind());
        assertEquals(code == 429 || code >= 500 || code == 408, failure.retryable());
        assertEquals(17L, failure.retryAfterSeconds());
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains("test-only-key"));
        assertFalse(failure.toString().contains("private source"));
    }
    @Test void timeoutIsBoundedAndRetryable() {
        delay = 3000;
        var provider = new GoogleEmbeddingProvider("test-only-key", url, "gemini-embedding-2", 768, 1);
        long start = System.nanoTime();
        var failure = assertThrows(EmbeddingFailure.class, () -> provider.embedQuery("query"));
        assertEquals(EmbeddingFailure.Kind.TEMPORARY, failure.kind());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2500);
        assertNull(failure.getCause());
    }
    @Test void retryAfterSupportsDatesAndBoundsUntrustedValues() {
        status = 429;
        retryAfter = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(120)
                .format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
        var dated = assertThrows(EmbeddingFailure.class, () -> provider().embedQuery("query"));
        assertTrue(dated.retryAfterSeconds() >= 110 && dated.retryAfterSeconds() <= 120);
        retryAfter = "999999";
        assertEquals(86400L, assertThrows(EmbeddingFailure.class, () -> provider().embedQuery("query")).retryAfterSeconds());
        retryAfter = "invalid private detail";
        assertNull(assertThrows(EmbeddingFailure.class, () -> provider().embedQuery("query")).retryAfterSeconds());
    }
    @Test void oversizedResponseIsRejectedSafely() {
        response = "{\"padding\":\"" + "a".repeat(140000) + "\"}";
        assertEquals(EmbeddingFailure.Kind.INVALID_RESPONSE, assertThrows(EmbeddingFailure.class, () -> provider().embedQuery("query")).kind());
    }
    @Test void validatesConfigurationAndInputWithoutNetwork() {
        assertThrows(IllegalArgumentException.class, () -> new GoogleEmbeddingProvider("key", url + "?key=secret", "gemini-embedding-2", 768, 2));
        assertThrows(IllegalArgumentException.class, () -> new GoogleEmbeddingProvider("key", "http://example.com", "gemini-embedding-2", 768, 2));
        assertThrows(IllegalArgumentException.class, () -> new GoogleEmbeddingProvider("key", url, "generation-model", 768, 2));
        assertThrows(IllegalArgumentException.class, () -> new GoogleEmbeddingProvider("key", url, "gemini-embedding-2", 0, 2));
        assertThrows(IllegalArgumentException.class, () -> new GoogleEmbeddingProvider("key", url, "gemini-embedding-2", 768, 61));
        var missingKey = new GoogleEmbeddingProvider("", url, "gemini-embedding-2", 768, 2);
        assertEquals(EmbeddingFailure.Kind.CONFIGURATION, assertThrows(EmbeddingFailure.class, () -> missingKey.embedQuery("query")).kind());
        for (String input : List.of("", " ", "a".repeat(24001)))
            assertEquals(EmbeddingFailure.Kind.INVALID_REQUEST, assertThrows(EmbeddingFailure.class, () -> provider().embedQuery(input)).kind());
        assertNull(captured.get());
    }
    @Test void selectionIsIndependentAndStartupMakesNoProviderCall() {
        var runner = new ApplicationContextRunner().withUserConfiguration(GoogleEmbeddingProvider.class, LocalAIProvider.class);
        runner.run(ctx -> assertTrue(ctx.getBeansOfType(EmbeddingProvider.class).isEmpty()));
        runner.withPropertyValues("app.embedding.mode=google", "app.embedding.base-url=" + url,
                "app.ai.model=unchanged-generation-model").run(ctx -> {
            assertNull(ctx.getStartupFailure());
            assertEquals("gemini-embedding-2", ctx.getBean(EmbeddingProvider.class).model());
            assertInstanceOf(LocalAIProvider.class, ctx.getBean(AIProvider.class));
            assertNull(captured.get());
        });
    }
}
