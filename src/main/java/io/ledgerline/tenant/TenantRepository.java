package io.ledgerline.tenant;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    Optional<Tenant> findByApiKeyHash(String apiKeyHash);

    boolean existsBySlug(String slug);
}
