package com.devpilot.webhook;
import com.devpilot.common.*;
import com.devpilot.analysis.*;
import com.devpilot.repository.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service public class WebhookService {
    private final WebhookStore events;
    private final RepositoryStore repos;
    private final AnalysisService analyses;
    public WebhookService(WebhookStore e,RepositoryStore r,AnalysisService a) {
        events=e;
        repos=r;
        analyses=a;
    }
    @Transactional public boolean receive(String delivery,String event,String hash,JsonNode body) {
        var previous=events.findByDeliveryId(delivery);
        if(previous.isPresent()) {
            if(!previous.get().payloadHash.equals(hash))throw Problem.bad("Delivery ID reused with different content");
            return false;
        }
        WebhookEvent e=new WebhookEvent();
        e.deliveryId=delivery;
        e.eventType=event;
        e.payloadHash=hash;
        events.saveAndFlush(e);
        String kind=null,ref=null;
        if(event.equals("push")&&!body.path("deleted").asBoolean()) {
            kind="COMMIT";
            ref=body.path("after").asText();
        }
        else if(event.equals("pull_request")&&Set.of("opened","reopened","synchronize","ready_for_review").contains(body.path("action").asText())) {
            kind="PR";
            ref=body.path("number").asText();
        }
        if(kind!=null) {
            for(var repo:repos.findByGithubId(body.path("repository").path("id").asLong())) {
                if(repo.fullName.equalsIgnoreCase(body.path("repository").path("full_name").asText()))analyses.enqueue(repo.id,kind,ref);
            }
        }
        return true;
    }
}
