package io.ledgerline.event;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import io.ledgerline.common.Direction;

/** JSON bodies of ledger events. Fields are only ever added, never renamed. */
public final class Payloads {

    private Payloads() {
    }

    public record AccountOpened(UUID accountId, String code, String type, String currency) {
    }

    public record AccountClosed(UUID accountId) {
    }

    public record EntryPosted(UUID entryId, UUID reversalOf, String currency, LocalDate effectiveDate,
            List<PostedLine> lines) {
    }

    public record PostedLine(UUID accountId, Direction direction, long amountMinor) {
    }

    public record EntryReversed(UUID entryId, UUID reversalEntryId, String reason) {
    }
}
