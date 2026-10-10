package com.devpilot.ai;

import com.devpilot.common.SecretRedactor;
import com.fasterxml.jackson.databind.JsonNode;
import io.netty.channel.ChannelOption;
import java.net.URI;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

@Component
@ConditionalOnProperty(name="app.embedding.mode", havingValue="google")
public class GoogleEmbeddingProvider implements EmbeddingProvider {
    private final WebClient client;
    private final URI endpoint;
    private final String key;
    private final String model;
    private final int dimensions;
    private final Duration timeout;

    public GoogleEmbeddingProvider(@Value("${app.embedding.api-key:}") String key,
            @Value("${app.embedding.base-url:https://generativelanguage.googleapis.com/v1beta}") String url,
            @Value("${app.embedding.model:gemini-embedding-2}") String model,
            @Value("${app.embedding.dimensions:768}") int dimensions,
            @Value("${app.embedding.timeout-seconds:20}") int timeoutSeconds) {
        // Configuration checks are local; credentials are checked at use, never during readiness.
        URI base;
        try { base = URI.create(url); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Invalid embedding base URL"); }
        boolean loopback = Set.of("localhost", "127.0.0.1", "[::1]").contains(Objects.toString(base.getHost(), ""));
        if (!("https".equals(base.getScheme()) || ("http".equals(base.getScheme()) && loopback))
                || base.getHost() == null || base.getUserInfo() != null || base.getRawQuery() != null
                || base.getRawFragment() != null) throw new IllegalArgumentException("Invalid embedding base URL");
        if (!"gemini-embedding-2".equals(model)) throw new IllegalArgumentException("Unsupported embedding model retrieval format");
        if (dimensions < 1 || dimensions > 3072 || timeoutSeconds < 1 || timeoutSeconds > 60)
            throw new IllegalArgumentException("Invalid embedding dimensions or timeout");
        this.key = key;
        this.model = model;
        this.dimensions = dimensions;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.endpoint = URI.create(url.replaceAll("/+$", "") + "/models/" + model + ":embedContent");
        HttpClient http = HttpClient.create().followRedirect(false)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.min(5000, timeoutSeconds * 1000))
                .responseTimeout(timeout);
        client = WebClient.builder().clientConnector(new ReactorClientHttpConnector(http))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(131072)).build();
    }

    public String model() { return model; }
    public int dimensions() { return dimensions; }
    public Embedding embedDocument(String title, String content) {
        return embed("title: " + (title == null || title.isBlank() ? "none" : title) + " | text: " + input(content));
    }
    public Embedding embedQuery(String query) {
        return embed("task: code retrieval | query: " + input(query));
    }
    private String input(String text) {
        if (text == null || text.isBlank()) throw new EmbeddingFailure(EmbeddingFailure.Kind.INVALID_REQUEST);
        return text;
    }
    private Embedding embed(String text) {
        if (key == null || key.isBlank()) throw new EmbeddingFailure(EmbeddingFailure.Kind.CONFIGURATION);
        // Conservative payload bound; tokenizer-aware chunking belongs to the source indexing phase.
        if (text.length() > 24000) throw new EmbeddingFailure(EmbeddingFailure.Kind.INVALID_REQUEST);
        Map<String, Object> body = Map.of("model", "models/" + model,
                "content", Map.of("parts", List.of(Map.of("text", SecretRedactor.clean(text)))),
                "outputDimensionality", dimensions);
        try {
            JsonNode response = client.post().uri(endpoint).header("x-goog-api-key", key)
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchangeToMono(r -> {
                        if (r.statusCode().value() == 200) return r.bodyToMono(JsonNode.class)
                                .onErrorMap(e -> new EmbeddingFailure(
                                        e instanceof org.springframework.core.codec.DecodingException
                                                || e instanceof org.springframework.core.io.buffer.DataBufferLimitException
                                                ? EmbeddingFailure.Kind.INVALID_RESPONSE : EmbeddingFailure.Kind.TEMPORARY));
                        int status = r.statusCode().value();
                        var kind = status == 401 || status == 403 ? EmbeddingFailure.Kind.AUTHENTICATION
                                : status == 429 ? EmbeddingFailure.Kind.RATE_LIMIT
                                : status >= 500 || status == 408 ? EmbeddingFailure.Kind.TEMPORARY
                                : EmbeddingFailure.Kind.INVALID_REQUEST;
                        Long delay = retryAfter(r.headers().asHttpHeaders().getFirst("Retry-After"));
                        return r.releaseBody().then(Mono.<JsonNode>error(new EmbeddingFailure(kind, delay)));
                    }).timeout(timeout).block();
            return validate(response);
        } catch (EmbeddingFailure e) { throw e; }
        catch (RuntimeException e) { throw new EmbeddingFailure(EmbeddingFailure.Kind.TEMPORARY); }
    }
    private Embedding validate(JsonNode response) {
        JsonNode values = response == null ? null : response.path("embedding").path("values");
        if (response == null || response.has("error") || values == null || !values.isArray() || values.size() != dimensions)
            throw new EmbeddingFailure(EmbeddingFailure.Kind.INVALID_RESPONSE);
        List<Double> vector = new ArrayList<>(dimensions);
        double norm = 0;
        for (JsonNode value : values) {
            if (!value.isNumber() || !Double.isFinite(value.doubleValue()))
                throw new EmbeddingFailure(EmbeddingFailure.Kind.INVALID_RESPONSE);
            double number = value.doubleValue();
            norm += number * number;
            vector.add(number);
        }
        if (!Double.isFinite(norm) || norm == 0) throw new EmbeddingFailure(EmbeddingFailure.Kind.INVALID_RESPONSE);
        return new Embedding(model, dimensions, vector);
    }
    private static Long retryAfter(String value) {
        if (value == null) return null;
        try {
            long seconds;
            try { seconds = Long.parseLong(value.strip()); }
            catch (NumberFormatException e) {
                seconds = Duration.between(Instant.now(), ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).getSeconds();
            }
            return Math.max(0, Math.min(86400, seconds));
        } catch (RuntimeException e) { return null; }
    }
}
