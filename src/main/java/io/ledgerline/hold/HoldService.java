package io.ledgerline.hold;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.ledgerline.account.Account;
import io.ledgerline.account.AccountRepository;
import io.ledgerline.account.OverdraftGuard;
import io.ledgerline.common.Direction;
import io.ledgerline.common.LedgerRuleException;
import io.ledgerline.common.NotFoundException;
import io.ledgerline.event.EventStore;
import io.ledgerline.event.LedgerEventType;
import io.ledgerline.event.Payloads;
import io.ledgerline.journal.JournalEntry;
import io.ledgerline.journal.PostingService;

/**
 * Places, captures, releases and expires holds. Every change to a hold locks
 * its row first, so a capture, a release and the expiry job can't all act on
 * the same hold.
 */
@Service
public class HoldService {

    static final Duration MAX_HOLD = Duration.ofDays(30);

    private final HoldRepository holds;
    private final AccountRepository accounts;
    private final PostingService postingService;
    private final EventStore eventStore;
    private final OverdraftGuard overdraftGuard;
    private final Clock clock;

    public HoldService(HoldRepository holds, AccountRepository accounts, PostingService postingService,
            EventStore eventStore, OverdraftGuard overdraftGuard, Clock clock) {
        this.holds = holds;
        this.accounts = accounts;
        this.postingService = postingService;
        this.eventStore = eventStore;
        this.overdraftGuard = overdraftGuard;
        this.clock = clock;
    }

    public record PlaceHoldCommand(UUID accountId, long amountMinor, Duration expiresIn, String reference) {
    }

    /** counterAccountId takes the other side; amountMinor null captures the full hold. */
    public record CaptureCommand(UUID counterAccountId, Long amountMinor, String description) {
    }

    public record Capture(Hold hold, JournalEntry entry) {
    }

    @Transactional
    public Hold place(UUID tenantId, PlaceHoldCommand command) {
        Account account = accounts.findByIdAndTenantId(command.accountId(), tenantId)
                .orElseThrow(() -> new LedgerRuleException("unknown_account",
                        "Account " + command.accountId() + " does not exist"));
        if (!account.isOpen()) {
            throw new LedgerRuleException("account_closed", "Account " + account.getCode() + " is closed");
        }
        if (command.amountMinor() <= 0) {
            throw new LedgerRuleException("non_positive_amount", "Hold amount must be greater than zero");
        }
        Duration expiresIn = command.expiresIn();
        if (expiresIn == null || expiresIn.isNegative() || expiresIn.isZero() || expiresIn.compareTo(MAX_HOLD) > 0) {
            throw new LedgerRuleException("invalid_expiry", "A hold must expire within " + MAX_HOLD.toDays() + " days");
        }

        overdraftGuard.check(Map.of(account, -command.amountMinor()));

        Instant now = clock.instant();
        Hold hold = holds.save(new Hold(UUID.randomUUID(), tenantId, account.getId(), command.amountMinor(),
                account.getCurrency(), command.reference(), now.plus(expiresIn), now));
        eventStore.append(tenantId, LedgerEventType.HOLD_PLACED, new Payloads.HoldPlaced(hold.getId(),
                account.getId(), hold.getAmountMinor(), hold.getCurrency(), hold.getExpiresAt()));
        return hold;
    }

    /**
     * Posts an entry that moves the captured amount out of the held account
     * into the counter account. Capturing less than the hold releases the
     * rest.
     */
    @Transactional
    public Capture capture(UUID tenantId, UUID holdId, CaptureCommand command) {
        Hold hold = lockLive(tenantId, holdId);
        long amount = command.amountMinor() == null ? hold.getAmountMinor() : command.amountMinor();
        if (amount <= 0) {
            throw new LedgerRuleException("non_positive_amount", "Capture amount must be greater than zero");
        }
        if (amount > hold.getAmountMinor()) {
            throw new LedgerRuleException("capture_exceeds_hold",
                    "Cannot capture " + amount + " from a hold of " + hold.getAmountMinor());
        }
        Account held = accounts.findByIdAndTenantId(hold.getAccountId(), tenantId).orElseThrow();
        Direction out = held.getType().normalSide().opposite();
        String description = command.description() != null ? command.description() : "Capture of hold " + holdId;

        // captured before posting, so the overdraft check doesn't count this hold as well as the capture
        hold.capture(amount, clock.instant());

        JournalEntry entry = postingService.post(tenantId, new PostingService.PostEntryCommand(null, description,
                null, hold.getCurrency(), List.of(
                        new PostingService.Line(held.getId(), out, amount, "hold " + holdId),
                        new PostingService.Line(command.counterAccountId(), out.opposite(), amount,
                                "hold " + holdId))));
        hold.linkCapturedEntry(entry.getId());
        eventStore.append(tenantId, LedgerEventType.HOLD_CAPTURED,
                new Payloads.HoldCaptured(holdId, entry.getId(), amount));
        return new Capture(hold, entry);
    }

    @Transactional
    public Hold release(UUID tenantId, UUID holdId) {
        Hold hold = lockLive(tenantId, holdId);
        hold.release(clock.instant());
        eventStore.append(tenantId, LedgerEventType.HOLD_RELEASED, new Payloads.HoldReleased(holdId));
        return hold;
    }

    /** Marks up to {@code limit} holds past their expiry as expired. Returns how many. */
    @Transactional
    public int expireDue(int limit) {
        Instant now = clock.instant();
        List<Hold> due = holds.lockDue(now, limit);
        for (Hold hold : due) {
            hold.expire(now);
            eventStore.append(hold.getTenantId(), LedgerEventType.HOLD_EXPIRED,
                    new Payloads.HoldExpired(hold.getId()));
        }
        return due.size();
    }

    @Transactional(readOnly = true)
    public Hold get(UUID tenantId, UUID holdId) {
        return holds.findByIdAndTenantId(holdId, tenantId).orElseThrow(() -> new NotFoundException("Hold", holdId));
    }

    @Transactional(readOnly = true)
    public List<Hold> list(UUID tenantId, UUID accountId, HoldStatus status) {
        return status == null
                ? holds.findAllByTenantIdAndAccountIdOrderByCreatedAt(tenantId, accountId)
                : holds.findAllByTenantIdAndAccountIdAndStatusOrderByCreatedAt(tenantId, accountId, status);
    }

    private Hold lockLive(UUID tenantId, UUID holdId) {
        Hold hold = holds.lockByIdAndTenantId(holdId, tenantId)
                .orElseThrow(() -> new NotFoundException("Hold", holdId));
        if (hold.getStatus() != HoldStatus.ACTIVE) {
            throw new LedgerRuleException("hold_not_active", "Hold " + holdId + " is " + hold.getStatus());
        }
        if (!hold.isLive(clock.instant())) {
            throw new LedgerRuleException("hold_expired", "Hold " + holdId + " expired at " + hold.getExpiresAt());
        }
        return hold;
    }
}
