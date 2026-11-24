package io.ledgerline.tenant;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.ledgerline.common.ConflictException;
import io.ledgerline.common.NotFoundException;

@Service
public class TenantService {

    private final TenantRepository tenants;
    private final Clock clock;

    public TenantService(TenantRepository tenants, Clock clock) {
        this.tenants = tenants;
        this.clock = clock;
    }

    /** A tenant together with the plain API key, which is never stored. */
    public record IssuedKey(Tenant tenant, String apiKey) {
    }

    @Transactional
    public IssuedKey create(String name, String slug) {
        if (tenants.existsBySlug(slug)) {
            throw new ConflictException("slug_taken", "Tenant slug " + slug + " is already in use");
        }
        String key = ApiKeys.generate();
        Tenant tenant = tenants.save(new Tenant(UUID.randomUUID(), name, slug, ApiKeys.hash(key), clock.instant()));
        return new IssuedKey(tenant, key);
    }

    @Transactional
    public IssuedKey rotateKey(UUID tenantId) {
        Tenant tenant = get(tenantId);
        String key = ApiKeys.generate();
        tenant.replaceApiKeyHash(ApiKeys.hash(key));
        return new IssuedKey(tenant, key);
    }

    @Transactional
    public Tenant suspend(UUID tenantId) {
        Tenant tenant = get(tenantId);
        tenant.suspend();
        return tenant;
    }

    @Transactional(readOnly = true)
    public Tenant get(UUID tenantId) {
        return tenants.findById(tenantId).orElseThrow(() -> new NotFoundException("Tenant", tenantId));
    }

    @Transactional(readOnly = true)
    public Optional<Tenant> findActiveByApiKey(String apiKey) {
        return tenants.findByApiKeyHash(ApiKeys.hash(apiKey)).filter(Tenant::isActive);
    }
}
