package io.ledgerline.event;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEventRepository extends JpaRepository<LedgerEvent, Long> {

    List<LedgerEvent> findByIdGreaterThanOrderByIdAsc(long id, Limit limit);

    List<LedgerEvent> findByTenantIdAndTenantSequenceGreaterThanOrderByTenantSequenceAsc(UUID tenantId,
            long tenantSequence, Limit limit);
}
