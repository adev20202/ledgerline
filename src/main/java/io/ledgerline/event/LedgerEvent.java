package io.ledgerline.event;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "ledger_events")
public class LedgerEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "tenant_sequence", nullable = false)
    private long tenantSequence;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private LedgerEventType eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode payload;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected LedgerEvent() {
    }

    public LedgerEvent(UUID tenantId, long tenantSequence, LedgerEventType eventType, JsonNode payload,
            Instant occurredAt) {
        this.tenantId = tenantId;
        this.tenantSequence = tenantSequence;
        this.eventType = eventType;
        this.payload = payload;
        this.occurredAt = occurredAt;
    }

    public Long getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public long getTenantSequence() {
        return tenantSequence;
    }

    public LedgerEventType getEventType() {
        return eventType;
    }

    public JsonNode getPayload() {
        return payload;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
