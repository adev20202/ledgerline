package io.ledgerline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.ledgerline.account.Account;
import io.ledgerline.account.AccountType;
import io.ledgerline.common.LedgerRuleException;
import io.ledgerline.common.NotFoundException;
import io.ledgerline.hold.Hold;
import io.ledgerline.hold.HoldService;
import io.ledgerline.hold.HoldService.CaptureCommand;
import io.ledgerline.hold.HoldService.PlaceHoldCommand;
import io.ledgerline.hold.HoldStatus;
import io.ledgerline.projection.BalanceQueryService;
import io.ledgerline.projection.BalanceQueryService.AccountBalanceView;

class HoldIntegrationTest extends IntegrationTestBase {

    @Autowired
    HoldService holds;
    @Autowired
    BalanceQueryService balances;

    UUID tenant;
    Account wallet;
    Account merchant;
    Account cash;

    @BeforeEach
    void setUp() {
        tenant = newTenant("acme");
        cash = open(tenant, "1000", AccountType.ASSET, "USD");
        wallet = open(tenant, "2100", AccountType.LIABILITY, "USD");
        merchant = open(tenant, "2200", AccountType.LIABILITY, "USD");
        transfer(tenant, cash, wallet, 100_00); // customer tops up 100.00
        projectAll();
    }

    @Test
    void holdLowersAvailableButNotBalance() {
        place(30_00, Duration.ofHours(1));

        AccountBalanceView view = balances.balanceOf(tenant, wallet.getId());
        assertThat(view.balanceMinor()).isEqualTo(100_00);
        assertThat(view.heldMinor()).isEqualTo(30_00);
        assertThat(view.availableMinor()).isEqualTo(70_00);
    }

    @Test
    void fullCaptureMovesFundsAndFreesTheHold() {
        Hold hold = place(30_00, Duration.ofHours(1));

        HoldService.Capture capture = holds.capture(tenant, hold.getId(),
                new CaptureCommand(merchant.getId(), null, "order 1"));
        projectAll();

        assertThat(capture.hold().getStatus()).isEqualTo(HoldStatus.CAPTURED);
        assertThat(capture.hold().getCapturedEntryId()).isEqualTo(capture.entry().getId());
        AccountBalanceView view = balances.balanceOf(tenant, wallet.getId());
        assertThat(view.balanceMinor()).isEqualTo(70_00);
        assertThat(view.heldMinor()).isZero();
        assertThat(balances.balanceOf(tenant, merchant.getId()).balanceMinor()).isEqualTo(30_00);
    }

    @Test
    void partialCaptureReleasesTheRest() {
        Hold hold = place(30_00, Duration.ofHours(1));

        holds.capture(tenant, hold.getId(), new CaptureCommand(merchant.getId(), 12_00L, null));
        projectAll();

        AccountBalanceView view = balances.balanceOf(tenant, wallet.getId());
        assertThat(view.balanceMinor()).isEqualTo(88_00);
        assertThat(view.availableMinor()).isEqualTo(88_00);
        assertThat(holds.get(tenant, hold.getId()).getCapturedAmountMinor()).isEqualTo(12_00L);
    }

    @Test
    void cannotCaptureMoreThanHeld() {
        Hold hold = place(30_00, Duration.ofHours(1));

        assertThatThrownBy(() -> holds.capture(tenant, hold.getId(), new CaptureCommand(merchant.getId(), 30_01L,
                null))).extracting("code").isEqualTo("capture_exceeds_hold");
        assertThat(holds.get(tenant, hold.getId()).getStatus()).isEqualTo(HoldStatus.ACTIVE);
    }

    @Test
    void releaseFreesFundsAndEndsTheHold() {
        Hold hold = place(30_00, Duration.ofHours(1));

        holds.release(tenant, hold.getId());

        assertThat(balances.balanceOf(tenant, wallet.getId()).availableMinor()).isEqualTo(100_00);
        assertThatThrownBy(() -> holds.release(tenant, hold.getId())).extracting("code")
                .isEqualTo("hold_not_active");
        assertThatThrownBy(() -> holds.capture(tenant, hold.getId(), new CaptureCommand(merchant.getId(), null,
                null))).extracting("code").isEqualTo("hold_not_active");
    }

    @Test
    void holdStopsCountingAtExpiryBeforeTheJobRuns() {
        Hold hold = place(30_00, Duration.ofMinutes(10));

        clock.advance(Duration.ofMinutes(11));

        assertThat(balances.balanceOf(tenant, wallet.getId()).availableMinor()).isEqualTo(100_00);
        assertThat(holds.get(tenant, hold.getId()).getStatus()).isEqualTo(HoldStatus.ACTIVE);
        assertThatThrownBy(() -> holds.capture(tenant, hold.getId(), new CaptureCommand(merchant.getId(), null,
                null))).extracting("code").isEqualTo("hold_expired");
    }

    @Test
    void expiryJobMarksDueHoldsOnce() {
        Hold soon = place(10_00, Duration.ofMinutes(10));
        Hold later = place(20_00, Duration.ofHours(5));

        clock.advance(Duration.ofMinutes(30));

        assertThat(holds.expireDue(100)).isEqualTo(1);
        assertThat(holds.expireDue(100)).isZero();
        assertThat(holds.get(tenant, soon.getId()).getStatus()).isEqualTo(HoldStatus.EXPIRED);
        assertThat(holds.get(tenant, later.getId()).getStatus()).isEqualTo(HoldStatus.ACTIVE);
        assertThat(jdbc.queryForObject("select count(*) from ledger_events where event_type = 'HOLD_EXPIRED'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void racingCapturesOnlyOneWins() throws Exception {
        Hold hold = place(30_00, Duration.ofHours(1));
        AtomicInteger succeeded = new AtomicInteger();

        List<Throwable> failures = ConcurrentPostingIntegrationTest.runInParallel(6, i -> {
            holds.capture(tenant, hold.getId(), new CaptureCommand(merchant.getId(), null, null));
            succeeded.incrementAndGet();
        });
        projectAll();

        assertThat(succeeded).hasValue(1);
        assertThat(failures).hasSize(5).allSatisfy(f -> assertThat(f).isInstanceOf(LedgerRuleException.class)
                .extracting("code").isEqualTo("hold_not_active"));
        assertThat(balances.balanceOf(tenant, merchant.getId()).balanceMinor()).isEqualTo(30_00);
    }

    @Test
    void captureAndReleaseRaceHasOneOutcome() throws Exception {
        Hold hold = place(30_00, Duration.ofHours(1));

        List<Throwable> failures = ConcurrentPostingIntegrationTest.runInParallel(2, i -> {
            if (i == 0) {
                holds.capture(tenant, hold.getId(), new CaptureCommand(merchant.getId(), null, null));
            } else {
                holds.release(tenant, hold.getId());
            }
        });

        assertThat(failures).hasSize(1);
        HoldStatus status = holds.get(tenant, hold.getId()).getStatus();
        assertThat(status).isIn(HoldStatus.CAPTURED, HoldStatus.RELEASED);
        int captured = jdbc.queryForObject("select count(*) from journal_entries where description like 'Capture%'",
                Integer.class);
        assertThat(captured).isEqualTo(status == HoldStatus.CAPTURED ? 1 : 0);
    }

    @Test
    void placeRejectsBadInput() {
        Account closed = open(tenant, "2999", AccountType.LIABILITY, "USD");
        accountService.close(tenant, closed.getId());

        assertThatThrownBy(() -> holds.place(tenant, new PlaceHoldCommand(closed.getId(), 100, Duration.ofHours(1),
                null))).extracting("code").isEqualTo("account_closed");
        assertThatThrownBy(() -> place(100, Duration.ofDays(31))).extracting("code").isEqualTo("invalid_expiry");
        assertThatThrownBy(() -> place(0, Duration.ofHours(1))).extracting("code").isEqualTo("non_positive_amount");
    }

    @Test
    void holdsAreScopedToTheTenant() {
        Hold hold = place(100, Duration.ofHours(1));
        UUID other = newTenant("other");

        assertThatThrownBy(() -> holds.release(other, hold.getId())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void accountWithLiveHoldCannotBeClosed() {
        Account spare = open(tenant, "2300", AccountType.LIABILITY, "USD");
        holds.place(tenant, new PlaceHoldCommand(spare.getId(), 100, Duration.ofHours(1), null));

        assertThatThrownBy(() -> accountService.close(tenant, spare.getId())).extracting("code")
                .isEqualTo("account_has_holds");
    }

    private Hold place(long amount, Duration expiresIn) {
        return holds.place(tenant, new PlaceHoldCommand(wallet.getId(), amount, expiresIn, null));
    }
}
