package io.ledgerline.account;

import java.time.Instant;
import java.util.UUID;

import io.ledgerline.common.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "accounts")
public class Account extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountType type;

    @Column(nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "overdraft_limit_minor")
    private Long overdraftLimitMinor;

    @Version
    private long version;

    protected Account() {
    }

    public Account(UUID id, UUID tenantId, String code, String name, AccountType type, String currency,
            Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.code = code;
        this.name = name;
        this.type = type;
        this.currency = currency;
        this.status = AccountStatus.OPEN;
        this.createdAt = createdAt;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public AccountType getType() {
        return type;
    }

    public String getCurrency() {
        return currency;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    /** How far below zero the available balance may go; null for no limit. */
    public Long getOverdraftLimitMinor() {
        return overdraftLimitMinor;
    }

    void setOverdraftLimitMinor(Long limit) {
        this.overdraftLimitMinor = limit;
    }

    public boolean isOpen() {
        return status == AccountStatus.OPEN;
    }

    void close(Instant at) {
        this.status = AccountStatus.CLOSED;
        this.closedAt = at;
    }
}
