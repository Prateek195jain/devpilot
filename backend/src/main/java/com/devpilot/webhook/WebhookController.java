package com.devpilot.webhook;
import com.devpilot.common.*;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.io.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
@RestController @RequestMapping("/api/v1/webhooks/github") public class WebhookController {
    private final WebhookService service;
    private final WebhookStore store;
    private final ObjectMapper mapper;
    private final String secret;
    public WebhookController(WebhookService s,WebhookStore st,ObjectMapper m,@Value("${app.webhook-secret}")String key) {
        if(key.length()<32)throw new IllegalStateException("WEBHOOK_SECRET must contain at least 32 characters");
        service=s;
        store=st;
        mapper=m;
        secret=key;
    }
    @PostMapping ResponseEntity<Map<String,Object>> receive(HttpServletRequest request,@RequestHeader(name="X-Hub-Signature-256",required=false)String signature,@RequestHeader(name="X-GitHub-Delivery",defaultValue="")String delivery,@RequestHeader(name="X-GitHub-Event",defaultValue="")String event)throws IOException {
        byte[] bytes=request.getInputStream().readNBytes(1_048_577);
        if(bytes.length>1_048_576)throw new Problem(HttpStatus.PAYLOAD_TOO_LARGE,"Webhook exceeds 1 MiB");
        if(!WebhookSignature.valid(bytes,signature,secret))throw new Problem(HttpStatus.UNAUTHORIZED,"Invalid webhook signature");
        if(!delivery.matches("[A-Za-z0-9-]{1,100}")||!event.matches("[a-z_]{1,50}"))throw Problem.bad("Invalid webhook headers");
        JsonNode body;
        try {
            body=mapper.readTree(bytes);
        }
        catch(com.fasterxml.jackson.core.JsonProcessingException e) {
            throw Problem.bad("Invalid JSON");
        }
        if(body==null||!body.isObject())throw Problem.bad("Expected JSON object");
        String hash=Crypto.hash(new String(bytes,StandardCharsets.UTF_8));
        boolean accepted;
        try {
            accepted=service.receive(delivery,event,hash,body);
        }
        catch(DataIntegrityViolationException race) {
            var existing=store.findByDeliveryId(delivery);
            if(existing.isEmpty()||!existing.get().payloadHash.equals(hash))throw race;
            accepted=false;
        }
        return ResponseEntity.accepted().body(Map.of("accepted",true,"duplicate",!accepted));
    }
}
