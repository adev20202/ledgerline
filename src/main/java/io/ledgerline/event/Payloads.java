package io.ledgerline.event;

import java.time.Instant;
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

    /** currency is null for an entry in several currencies. */
    public record EntryPosted(UUID entryId, UUID reversalOf, String currency, LocalDate effectiveDate,
            List<PostedLine> lines) {
    }

    /** currency is missing from events written before lines had their own currency. */
    public record PostedLine(UUID accountId, String currency, Direction direction, long amountMinor) {

        public String currencyOr(String entryCurrency) {
            return currency != null ? currency : entryCurrency;
        }
    }

    public record EntryReversed(UUID entryId, UUID reversalEntryId, String reason) {
    }

    public record HoldPlaced(UUID holdId, UUID accountId, long amountMinor, String currency, Instant expiresAt) {
    }

    public record HoldCaptured(UUID holdId, UUID entryId, long amountMinor) {
    }

    public record HoldReleased(UUID holdId) {
    }

    public record HoldExpired(UUID holdId) {
    }
}
