package io.ledgerline.event;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerEventRepository extends JpaRepository<LedgerEvent, Long> {

    @Query("select coalesce(max(e.tenantSequence), 0) from LedgerEvent e where e.tenantId = :tenantId")
    long maxSequence(@Param("tenantId") UUID tenantId);

    List<LedgerEvent> findByIdGreaterThanOrderByIdAsc(long id, Limit limit);

    List<LedgerEvent> findByTenantIdAndTenantSequenceGreaterThanOrderByTenantSequenceAsc(UUID tenantId,
            long tenantSequence, Limit limit);
}
