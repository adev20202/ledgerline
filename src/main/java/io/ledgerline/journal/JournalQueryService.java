package io.ledgerline.journal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.ledgerline.common.Direction;
import io.ledgerline.common.NotFoundException;

@Service
@Transactional(readOnly = true)
public class JournalQueryService {

    private final JournalEntryRepository entries;
    private final JournalLineRepository lines;

    public JournalQueryService(JournalEntryRepository entries, JournalLineRepository lines) {
        this.entries = entries;
        this.lines = lines;
    }

    public record EntryView(UUID id, String reference, String description, LocalDate effectiveDate, String currency,
            EntryStatus status, UUID reversalOf, UUID reversedBy, Instant postedAt, List<LineView> lines) {
    }

    public record LineView(int lineNo, UUID accountId, String currency, Direction direction, long amountMinor,
            String memo) {
    }

    public record StatementLineView(UUID entryId, LocalDate effectiveDate, Instant postedAt, String description,
            String currency, Direction direction, long amountMinor, String memo) {
    }

    public EntryView get(UUID tenantId, UUID entryId) {
        return view(entries.findByIdAndTenantId(entryId, tenantId)
                .orElseThrow(() -> new NotFoundException("Entry", entryId)));
    }

    public List<EntryView> list(UUID tenantId, LocalDate from, LocalDate to, int page, int size) {
        return entries.findByTenantIdAndEffectiveDateBetweenOrderByEffectiveDateAscPostedAtAsc(tenantId, from, to,
                PageRequest.of(page, size)).stream().map(JournalQueryService::view).toList();
    }

    public List<StatementLineView> statement(UUID tenantId, UUID accountId, LocalDate from, LocalDate to, int page,
            int size) {
        return lines.statement(tenantId, accountId, from, to, PageRequest.of(page, size)).stream()
                .map(l -> new StatementLineView(l.getEntry().getId(), l.getEntry().getEffectiveDate(),
                        l.getEntry().getPostedAt(), l.getEntry().getDescription(), l.getCurrency(), l.getDirection(),
                        l.getAmountMinor(), l.getMemo()))
                .toList();
    }

    /** Must run inside a transaction, the lines are loaded lazily. */
    public static EntryView view(JournalEntry e) {
        return new EntryView(e.getId(), e.getReference(), e.getDescription(), e.getEffectiveDate(), e.getCurrency(),
                e.getStatus(), e.getReversalOf(), e.getReversedBy(), e.getPostedAt(),
                e.getLines().stream()
                        .map(l -> new LineView(l.getLineNo(), l.getAccountId(), l.getCurrency(), l.getDirection(),
                                l.getAmountMinor(), l.getMemo()))
                        .toList());
    }
}
