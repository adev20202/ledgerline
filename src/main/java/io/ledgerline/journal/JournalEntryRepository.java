package io.ledgerline.journal;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {

    Optional<JournalEntry> findByIdAndTenantId(UUID id, UUID tenantId);

    boolean existsByTenantIdAndReference(UUID tenantId, String reference);

    List<JournalEntry> findByTenantIdAndEffectiveDateBetweenOrderByEffectiveDateAscPostedAtAsc(UUID tenantId,
            LocalDate from, LocalDate to, Pageable pageable);
}
