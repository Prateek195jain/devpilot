package com.devpilot.common;
import java.util.regex.Pattern;
/** Reduces accidental disclosure; not a comprehensive secret scanner. */
public final class SecretRedactor {
    private SecretRedactor() {
    }
    private static final Pattern TOKEN=Pattern.compile("(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|sk-[A-Za-z0-9_-]{20,}|eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+)");
    public static String clean(String input) {
        return TOKEN.matcher(input).replaceAll("[REDACTED_TOKEN]");
    }
}
