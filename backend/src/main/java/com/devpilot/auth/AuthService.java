package com.devpilot.auth;
import com.devpilot.common.*;
import com.devpilot.user.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
@Service public class AuthService {
    private final UserStore users;
    private final RefreshStore refresh;
    private final PasswordEncoder passwords;
    private final JwtEncoder jwt;
    private final String dummy;
    public AuthService(UserStore u,RefreshStore r,PasswordEncoder p,JwtEncoder j) {
        users=u;
        refresh=r;
        passwords=p;
        jwt=j;
        dummy=p.encode(Crypto.randomToken());
    }
    public record Session(String accessToken,UserAccountView user,String refreshToken) {
    }
    @Transactional public Session register(String name,String email,String password) {
        checkPassword(password);
        String normalized=email.strip().toLowerCase(Locale.ROOT);
        if(users.existsByEmail(normalized))throw new Problem(org.springframework.http.HttpStatus.CONFLICT,"Email is already registered");
        UserAccount u=new UserAccount();
        u.name=name.strip();
        u.email=normalized;
        u.passwordHash=passwords.encode(password);
        u.role="USER";
        users.save(u);
        return issue(u);
    }
    @Transactional public Session login(String email,String password) {
        checkPassword(password);
        UserAccount u=users.findByEmail(email.strip().toLowerCase(Locale.ROOT)).orElse(null);
        boolean matches=passwords.matches(password,u==null?dummy:u.passwordHash);
        if(u==null||!matches)throw Problem.unauthorized();
        return issue(u);
    }
    @Transactional public Session rotate(String token) {
        RefreshToken old=refresh.lockByHash(Crypto.hash(token)).orElseThrow(Problem::unauthorized);
        if(old.revokedAt!=null||old.expiresAt.isBefore(Instant.now()))throw Problem.unauthorized();
        old.revokedAt=Instant.now();
        return issue(users.findById(old.userId).orElseThrow(Problem::unauthorized));
    }
    @Transactional public void logout(String token) {
        refresh.lockByHash(Crypto.hash(token)).ifPresent(t->t.revokedAt=Instant.now());
    }
    private void checkPassword(String p) {
        if(p.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)throw Problem.bad("Password must be at most 72 UTF-8 bytes");
    }
    private Session issue(UserAccount u) {
        Instant now=Instant.now();
        JwtClaimsSet claims=JwtClaimsSet.builder().issuer("devpilot").subject(u.id.toString()).issuedAt(now).expiresAt(now.plusSeconds(600)).claim("role",u.role).id(UUID.randomUUID().toString()).build();
        String access=jwt.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
        String raw=Crypto.randomToken();
        RefreshToken t=new RefreshToken();
        t.userId=u.id;
        t.tokenHash=Crypto.hash(raw);
        t.expiresAt=now.plus(Duration.ofDays(7));
        refresh.save(t);
        return new Session(access,UserAccountView.of(u),raw);
    }
}
