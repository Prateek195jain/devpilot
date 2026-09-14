package com.devpilot.webhook;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface WebhookStore extends JpaRepository<WebhookEvent,UUID> {
    Optional<WebhookEvent> findByDeliveryId(String deliveryId);
}
