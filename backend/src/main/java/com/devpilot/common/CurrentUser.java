package com.devpilot.common;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
public final class CurrentUser {
    private CurrentUser() {
    }
    public static UUID id() {
        return UUID.fromString(SecurityContextHolder.getContext().getAuthentication().getName());
    }
}
