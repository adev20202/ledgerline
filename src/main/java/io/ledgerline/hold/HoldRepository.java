package io.ledgerline.hold;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface HoldRepository extends JpaRepository<Hold, UUID> {

    Optional<Hold> findByIdAndTenantId(UUID id, UUID tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from Hold h where h.id = :id and h.tenantId = :tenantId")
    Optional<Hold> lockByIdAndTenantId(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    List<Hold> findAllByTenantIdAndAccountIdOrderByCreatedAt(UUID tenantId, UUID accountId);

    List<Hold> findAllByTenantIdAndAccountIdAndStatusOrderByCreatedAt(UUID tenantId, UUID accountId,
            HoldStatus status);

    @Query("""
            select coalesce(sum(h.amountMinor), 0) from Hold h
            where h.accountId = :accountId and h.status = :status and h.expiresAt > :now
            """)
    long sumByStatusNotExpired(@Param("accountId") UUID accountId, @Param("status") HoldStatus status,
            @Param("now") Instant now);

    default long sumLive(UUID accountId, Instant now) {
        return sumByStatusNotExpired(accountId, HoldStatus.ACTIVE, now);
    }

    /** Active holds past expiry, locked; rows another worker holds are skipped. */
    @Query(value = """
            select * from holds where status = 'ACTIVE' and expires_at <= :now
            order by expires_at limit :limit for update skip locked
            """, nativeQuery = true)
    List<Hold> lockDue(@Param("now") Instant now, @Param("limit") int limit);
}
