package com.devpilot.auth;
import java.util.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
public interface RefreshStore extends JpaRepository<RefreshToken,UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select t from RefreshToken t where t.tokenHash = :hash") Optional<RefreshToken> lockByHash(String hash);
}
