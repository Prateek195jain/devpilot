package com.devpilot.user;
import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
import com.devpilot.common.BaseEntity;
@Entity @Table(name="users") public class UserAccount extends BaseEntity {
    @Column(name="email",length=255) public String email;
    @Column(name="name",length=100) public String name;
    @Column(name="password_hash",length=255) public String passwordHash;
    @Column(name="role",length=20) public String role;
}
