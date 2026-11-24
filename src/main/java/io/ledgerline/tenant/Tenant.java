package io.ledgerline.tenant;

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
@Table(name = "tenants")
public class Tenant extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(name = "api_key_hash", nullable = false, unique = true)
    private String apiKeyHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TenantStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Tenant() {
    }

    public Tenant(UUID id, String name, String slug, String apiKeyHash, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.slug = slug;
        this.apiKeyHash = apiKeyHash;
        this.status = TenantStatus.ACTIVE;
        this.createdAt = createdAt;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public TenantStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isActive() {
        return status == TenantStatus.ACTIVE;
    }

    void replaceApiKeyHash(String hash) {
        this.apiKeyHash = hash;
    }

    void suspend() {
        this.status = TenantStatus.SUSPENDED;
    }
}
