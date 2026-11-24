package io.ledgerline.account;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByIdAndTenantId(UUID id, UUID tenantId);

    List<Account> findAllByTenantIdAndIdIn(UUID tenantId, Collection<UUID> ids);

    List<Account> findAllByTenantIdOrderByCode(UUID tenantId);

    List<Account> findAllByTenantIdAndTypeOrderByCode(UUID tenantId, AccountType type);

    List<Account> findAllByTenantIdAndCurrencyOrderByCode(UUID tenantId, String currency);

    boolean existsByTenantIdAndCode(UUID tenantId, String code);
}
