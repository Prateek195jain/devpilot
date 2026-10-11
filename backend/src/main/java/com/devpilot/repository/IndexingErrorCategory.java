package com.devpilot.repository;
/** Only these local values may leave the indexing boundary. */
public enum IndexingErrorCategory {
    RATE_LIMIT, PROVIDER_UNAVAILABLE, TIMEOUT, NETWORK, AUTHENTICATION, INVALID_REQUEST,
    INVALID_RESPONSE, DIMENSION_MISMATCH, CONFIGURATION, SOURCE_UNAVAILABLE, INTERNAL
}
