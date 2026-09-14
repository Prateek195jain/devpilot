package com.devpilot.webhook;
import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
import com.devpilot.common.BaseEntity;
@Entity @Table(name="webhook_events") public class WebhookEvent extends BaseEntity {
    @Column(name="delivery_id",length=100) public String deliveryId;
    @Column(name="event_type",length=50) public String eventType;
    @Column(name="payload_hash",length=64) public String payloadHash;
}
