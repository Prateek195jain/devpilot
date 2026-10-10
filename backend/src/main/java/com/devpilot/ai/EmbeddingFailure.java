package com.devpilot.ai;

/** Contains no remote body, request, credentials, or nested transport exception. */
public final class EmbeddingFailure extends RuntimeException {
    public enum Kind { CONFIGURATION, AUTHENTICATION, RATE_LIMIT, TEMPORARY, INVALID_REQUEST, INVALID_RESPONSE }
    private final Kind kind;
    private final Long retryAfterSeconds;

    public EmbeddingFailure(Kind kind) { this(kind, null); }
    public EmbeddingFailure(Kind kind, Long retryAfterSeconds) {
        super("Embedding request failed: " + kind.name());
        this.kind = kind;
        this.retryAfterSeconds = retryAfterSeconds;
    }
    public Kind kind() { return kind; }
    public boolean retryable() { return kind == Kind.RATE_LIMIT || kind == Kind.TEMPORARY; }
    public Long retryAfterSeconds() { return retryAfterSeconds; }
}
