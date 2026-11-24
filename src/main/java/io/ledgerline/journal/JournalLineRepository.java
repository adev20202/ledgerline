package io.ledgerline.journal;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JournalLineRepository extends JpaRepository<JournalLine, UUID> {

    @Query("""
            select l from JournalLine l join fetch l.entry e
            where e.tenantId = :tenantId and l.accountId = :accountId
              and e.effectiveDate between :from and :to
            order by e.effectiveDate, e.postedAt, l.lineNo
            """)
    List<JournalLine> statement(@Param("tenantId") UUID tenantId, @Param("accountId") UUID accountId,
            @Param("from") LocalDate from, @Param("to") LocalDate to, Pageable pageable);
}
