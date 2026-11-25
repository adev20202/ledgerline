package io.ledgerline.hold;

import java.time.Instant;
import java.util.UUID;

import io.ledgerline.common.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "holds")
public class Hold extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private HoldStatus status;

    private String reference;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "captured_entry_id")
    private UUID capturedEntryId;

    @Column(name = "captured_amount_minor")
    private Long capturedAmountMinor;

    protected Hold() {
    }

    Hold(UUID id, UUID tenantId, UUID accountId, long amountMinor, String currency, String reference,
            Instant expiresAt, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.accountId = accountId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.status = HoldStatus.ACTIVE;
        this.reference = reference;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    /** Active and not yet past its expiry, whether or not the expiry job has run. */
    public boolean isLive(Instant now) {
        return status == HoldStatus.ACTIVE && expiresAt.isAfter(now);
    }

    void capture(UUID entryId, long amount, Instant at) {
        status = HoldStatus.CAPTURED;
        capturedEntryId = entryId;
        capturedAmountMinor = amount;
        resolvedAt = at;
    }

    void release(Instant at) {
        status = HoldStatus.RELEASED;
        resolvedAt = at;
    }

    void expire(Instant at) {
        status = HoldStatus.EXPIRED;
        resolvedAt = at;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public HoldStatus getStatus() {
        return status;
    }

    public String getReference() {
        return reference;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public UUID getCapturedEntryId() {
        return capturedEntryId;
    }

    public Long getCapturedAmountMinor() {
        return capturedAmountMinor;
    }
}
