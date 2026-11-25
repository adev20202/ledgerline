package io.ledgerline.journal;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.ledgerline.account.Account;
import io.ledgerline.account.AccountRepository;
import io.ledgerline.common.ConflictException;
import io.ledgerline.common.Currencies;
import io.ledgerline.common.Direction;
import io.ledgerline.common.LedgerRuleException;
import io.ledgerline.common.NotFoundException;
import io.ledgerline.event.EventStore;
import io.ledgerline.event.LedgerEventType;
import io.ledgerline.event.Payloads;

/**
 * The only way money moves: journal entries that balance in every currency
 * they touch. An entry with a currency set must stay in that currency.
 */
@Service
public class PostingService {

    private final JournalEntryRepository entries;
    private final AccountRepository accounts;
    private final EventStore eventStore;
    private final Clock clock;

    public PostingService(JournalEntryRepository entries, AccountRepository accounts, EventStore eventStore,
            Clock clock) {
        this.entries = entries;
        this.accounts = accounts;
        this.eventStore = eventStore;
        this.clock = clock;
    }

    /** currency is optional; when null the lines may be in several currencies. */
    public record PostEntryCommand(String reference, String description, LocalDate effectiveDate, String currency,
            List<Line> lines) {
    }

    public record Line(UUID accountId, Direction direction, long amountMinor, String memo) {
    }

    @Transactional
    public JournalEntry post(UUID tenantId, PostEntryCommand command) {
        if (command.reference() != null && entries.existsByTenantIdAndReference(tenantId, command.reference())) {
            throw new ConflictException("duplicate_reference",
                    "An entry with reference " + command.reference() + " already exists");
        }
        return postValidated(tenantId, command, null);
    }

    /**
     * Posts a mirror entry that cancels the original and marks the original
     * reversed. Entries are never edited or deleted.
     */
    @Transactional
    public JournalEntry reverse(UUID tenantId, UUID entryId, String reason) {
        JournalEntry original = entries.findByIdAndTenantId(entryId, tenantId)
                .orElseThrow(() -> new NotFoundException("Entry", entryId));
        if (original.getStatus() == EntryStatus.REVERSED) {
            throw new LedgerRuleException("already_reversed", "Entry " + entryId + " is already reversed");
        }
        if (original.getReversalOf() != null) {
            throw new LedgerRuleException("cannot_reverse_reversal", "Entry " + entryId + " is itself a reversal");
        }

        List<Line> mirrored = original.getLines().stream()
                .map(l -> new Line(l.getAccountId(), l.getDirection().opposite(), l.getAmountMinor(), l.getMemo()))
                .toList();
        PostEntryCommand command = new PostEntryCommand(null, "Reversal: " + reason,
                LocalDate.now(clock.withZone(ZoneOffset.UTC)), original.getCurrency(), mirrored);
        JournalEntry reversal = postValidated(tenantId, command, original.getId());

        original.markReversed(reversal.getId());
        eventStore.append(tenantId, LedgerEventType.ENTRY_REVERSED,
                new Payloads.EntryReversed(original.getId(), reversal.getId(), reason));
        return reversal;
    }

    private JournalEntry postValidated(UUID tenantId, PostEntryCommand command, UUID reversalOf) {
        String entryCurrency = command.currency() == null
                ? null
                : Currencies.require(command.currency()).getCurrencyCode();
        List<Line> lines = command.lines();
        if (lines == null || lines.size() < 2) {
            throw new LedgerRuleException("too_few_lines", "An entry needs at least two lines");
        }
        for (Line line : lines) {
            if (line.direction() == null) {
                throw new LedgerRuleException("missing_direction", "Every line needs a direction");
            }
            if (line.amountMinor() <= 0) {
                throw new LedgerRuleException("non_positive_amount", "Line amounts must be greater than zero");
            }
        }

        Set<UUID> accountIds = new HashSet<>();
        lines.forEach(l -> accountIds.add(l.accountId()));
        Map<UUID, Account> found = accounts.findAllByTenantIdAndIdIn(tenantId, accountIds).stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));
        for (UUID id : accountIds) {
            Account account = found.get(id);
            if (account == null) {
                throw new LedgerRuleException("unknown_account", "Account " + id + " does not exist");
            }
            if (!account.isOpen()) {
                throw new LedgerRuleException("account_closed", "Account " + account.getCode() + " is closed");
            }
            if (entryCurrency != null && !account.getCurrency().equals(entryCurrency)) {
                throw new LedgerRuleException("currency_mismatch", "Account " + account.getCode() + " is in "
                        + account.getCurrency() + ", entry is in " + entryCurrency);
            }
        }

        // a line is in its account's currency; each currency must balance on its own
        Map<String, long[]> totals = new TreeMap<>();
        try {
            for (Line line : lines) {
                long[] t = totals.computeIfAbsent(found.get(line.accountId()).getCurrency(), c -> new long[2]);
                int side = line.direction() == Direction.DEBIT ? 0 : 1;
                t[side] = Math.addExact(t[side], line.amountMinor());
            }
        } catch (ArithmeticException e) {
            throw new LedgerRuleException("amount_overflow", "Entry total is too large");
        }
        totals.forEach((currency, t) -> {
            if (t[0] != t[1]) {
                throw new LedgerRuleException("unbalanced", "In " + currency + ", debits (" + t[0]
                        + ") and credits (" + t[1] + ") must be equal");
            }
        });
        String storedCurrency = entryCurrency != null
                ? entryCurrency
                : totals.size() == 1 ? totals.keySet().iterator().next() : null;

        LocalDate effectiveDate = command.effectiveDate() != null
                ? command.effectiveDate()
                : LocalDate.now(clock.withZone(ZoneOffset.UTC));
        JournalEntry entry = new JournalEntry(UUID.randomUUID(), tenantId, command.reference(),
                command.description(), effectiveDate, storedCurrency, reversalOf, clock.instant());
        lines.forEach(l -> entry.addLine(l.accountId(), found.get(l.accountId()).getCurrency(), l.direction(),
                l.amountMinor(), l.memo()));
        JournalEntry saved = entries.save(entry);

        eventStore.append(tenantId, LedgerEventType.ENTRY_POSTED, new Payloads.EntryPosted(saved.getId(),
                reversalOf, storedCurrency, effectiveDate, saved.getLines().stream()
                        .map(l -> new Payloads.PostedLine(l.getAccountId(), l.getCurrency(), l.getDirection(),
                                l.getAmountMinor()))
                        .toList()));
        return saved;
    }
}
