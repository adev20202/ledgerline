package io.ledgerline.projection;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountBalanceRepository extends JpaRepository<AccountBalance, UUID> {

    List<AccountBalance> findAllByTenantIdAndCurrency(UUID tenantId, String currency);
}
