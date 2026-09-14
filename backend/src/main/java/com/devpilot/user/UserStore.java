package com.devpilot.user;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface UserStore extends JpaRepository<UserAccount,UUID> {
    Optional<UserAccount> findByEmail(String email);
    boolean existsByEmail(String email);
}
