package com.devpilot.github;
import com.devpilot.common.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service public class IntegrationService {
    private final IntegrationStore store;
    private final Crypto crypto;
    private final SourceControlProvider github;
    public IntegrationService(IntegrationStore s,Crypto c,SourceControlProvider g) {
        store=s;
        crypto=c;
        github=g;
    }
    public record Status(boolean connected,String login) {
    }
    public Status status(UUID user) {
        return store.findByUserId(user).map(i->new Status(true,i.login)).orElse(new Status(false,""));
    }
    @Transactional public Status connect(UUID user,String token) {
        String login=github.login(token);
        if(login.isBlank())throw Problem.upstream("GitHub");
        GitHubIntegration i=store.findByUserId(user).orElseGet(GitHubIntegration::new);
        i.userId=user;
        i.login=login;
        i.encryptedToken=crypto.encrypt(token);
        store.save(i);
        return new Status(true,login);
    }
    public String token(UUID user) {
        return crypto.decrypt(store.findByUserId(user).orElseThrow(()->Problem.bad("Connect GitHub in Settings first")).encryptedToken);
    }
    @Transactional public void disconnect(UUID user) {
        store.findByUserId(user).ifPresent(store::delete);
    }
}
