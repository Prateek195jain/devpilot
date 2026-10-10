package com.devpilot.ai;

/** Contains no remote body, request, credentials, or nested transport exception. */
public final class EmbeddingFailure extends RuntimeException {
    public enum Kind { CONFIGURATION, AUTHENTICATION, RATE_LIMIT, TEMPORARY, INVALID_REQUEST, INVALID_RESPONSE }
    private final Kind kind;
    private final Long retryAfterSeconds;
    private final Integer httpStatus;
    private final int attempts;
    private final String category;

    public EmbeddingFailure(Kind kind) { this(kind, null); }
    public EmbeddingFailure(Kind kind, Long retryAfterSeconds) {
        this(kind, retryAfterSeconds, null, 1, kind.name());
    }
    EmbeddingFailure(Kind kind, Long retryAfterSeconds, Integer httpStatus, int attempts, String category) {
        super("Embedding request failed: " + kind.name());
        this.kind = kind;
        this.retryAfterSeconds = retryAfterSeconds;
        this.httpStatus = httpStatus;
        this.attempts = attempts;
        this.category = category;
    }
    public int attempts() { return attempts; }
    public Integer httpStatus() { return httpStatus; }
    public String diagnostic() {
        return "provider=google category=" + category + (httpStatus == null ? "" : " httpStatus=" + httpStatus)
                + " attempts=" + attempts;
    }
    EmbeddingFailure withAttempts(int count) {
        return new EmbeddingFailure(kind, retryAfterSeconds, httpStatus, count, category);
    }
    public Kind kind() { return kind; }
    public boolean retryable() { return kind == Kind.RATE_LIMIT || kind == Kind.TEMPORARY; }
    public Long retryAfterSeconds() { return retryAfterSeconds; }
}
