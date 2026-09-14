package com.devpilot.user;
import java.util.UUID;
import java.time.Instant;
public record UserAccountView(UUID id,Instant createdAt,String email,String name,String role) {
    public static UserAccountView of(UserAccount e) {
        return new UserAccountView(e.id,e.createdAt,e.email,e.name,e.role);
    }
}
