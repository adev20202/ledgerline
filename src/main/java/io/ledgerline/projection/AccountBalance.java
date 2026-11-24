package io.ledgerline.projection;

import java.time.Instant;
import java.util.UUID;

import io.ledgerline.common.AssignedIdEntity;
import io.ledgerline.common.Direction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "account_balances")
public class AccountBalance extends AssignedIdEntity {

    @Id
    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String currency;

    @Column(name = "debit_total_minor", nullable = false)
    private long debitTotalMinor;

    @Column(name = "credit_total_minor", nullable = false)
    private long creditTotalMinor;

    @Column(name = "last_event_id", nullable = false)
    private long lastEventId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AccountBalance() {
    }

    AccountBalance(UUID accountId, UUID tenantId, String currency, long eventId, Instant at) {
        this.accountId = accountId;
        this.tenantId = tenantId;
        this.currency = currency;
        this.lastEventId = eventId;
        this.updatedAt = at;
    }

    void apply(Direction direction, long amountMinor, long eventId, Instant at) {
        if (direction == Direction.DEBIT) {
            debitTotalMinor = Math.addExact(debitTotalMinor, amountMinor);
        } else {
            creditTotalMinor = Math.addExact(creditTotalMinor, amountMinor);
        }
        lastEventId = eventId;
        updatedAt = at;
    }

    @Override
    public UUID getId() {
        return accountId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getCurrency() {
        return currency;
    }

    public long getDebitTotalMinor() {
        return debitTotalMinor;
    }

    public long getCreditTotalMinor() {
        return creditTotalMinor;
    }

    public long getLastEventId() {
        return lastEventId;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
