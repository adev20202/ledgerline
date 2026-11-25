package io.ledgerline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import io.ledgerline.account.Account;
import io.ledgerline.account.AccountService.OpenAccountCommand;
import io.ledgerline.account.AccountType;
import io.ledgerline.common.LedgerRuleException;
import io.ledgerline.hold.Hold;
import io.ledgerline.hold.HoldService;
import io.ledgerline.hold.HoldService.CaptureCommand;
import io.ledgerline.hold.HoldService.PlaceHoldCommand;
import io.ledgerline.journal.JournalEntry;
import io.ledgerline.projection.BalanceQueryService;

@AutoConfigureMockMvc
class OverdraftIntegrationTest extends IntegrationTestBase {

    @Autowired
    HoldService holds;
    @Autowired
    BalanceQueryService balances;
    @Autowired
    MockMvc mvc;

    UUID tenant;
    String apiKey;
    Account cash;
    Account wallet;
    Account merchant;

    @BeforeEach
    void setUp() {
        var issued = tenantService.create("Acme", "acme");
        tenant = issued.tenant().getId();
        apiKey = issued.apiKey();
        cash = open(tenant, "1000", AccountType.ASSET, "USD");
        merchant = open(tenant, "2200", AccountType.LIABILITY, "USD");
        wallet = accountService.open(tenant, new OpenAccountCommand("2100", "wallet", AccountType.LIABILITY, "USD",
                0L));
        transfer(tenant, cash, wallet, 100_00);
    }

    @Test
    void accountWithoutLimitCanGoNegative() {
        transfer(tenant, cash, merchant, 0_01); // merchant credited
        transfer(tenant, merchant, cash, 50_00);
        projectAll();

        assertThat(balances.balanceOf(tenant, merchant.getId()).balanceMinor()).isEqualTo(-49_99);
    }

    @Test
    void zeroLimitStopsAnOverdraw() {
        assertThatThrownBy(() -> pay(100_01)).isInstanceOf(LedgerRuleException.class)
                .extracting("code").isEqualTo("insufficient_funds");
        pay(100_00);
        assertThatThrownBy(() -> pay(1)).extracting("code").isEqualTo("insufficient_funds");
    }

    @Test
    void positiveLimitAllowsDownToMinusTheLimit() {
        accountService.setOverdraftLimit(tenant, wallet.getId(), 50_00L);

        pay(150_00);
        assertThatThrownBy(() -> pay(1)).extracting("code").isEqualTo("insufficient_funds");
    }

    @Test
    void checkUsesTheJournalNotTheProjection() {
        // nothing projected yet, the projected balance is still zero
        assertThat(balances.balanceOf(tenant, wallet.getId()).balanceMinor()).isZero();

        pay(60_00);
    }

    @Test
    void depositsAreAllowedBelowTheLimit() {
        pay(100_00);
        accountService.setOverdraftLimit(tenant, wallet.getId(), 0L);

        transfer(tenant, cash, wallet, 5_00);
    }

    @Test
    void parallelPaymentsCannotOverdraw() throws Exception {
        AtomicInteger succeeded = new AtomicInteger();

        List<Throwable> failures = ConcurrentPostingIntegrationTest.runInParallel(10, i -> {
            pay(30_00);
            succeeded.incrementAndGet();
        });
        projectAll();

        assertThat(succeeded).hasValue(3);
        assertThat(failures).hasSize(7).allSatisfy(f -> assertThat(f).extracting("code")
                .isEqualTo("insufficient_funds"));
        assertThat(balances.balanceOf(tenant, wallet.getId()).balanceMinor()).isEqualTo(10_00);
    }

    @Test
    void holdsCountAgainstTheLimit() {
        Hold hold = hold(80_00);

        assertThatThrownBy(() -> pay(30_00)).extracting("code").isEqualTo("insufficient_funds");
        holds.release(tenant, hold.getId());
        pay(30_00);
    }

    @Test
    void holdBeyondAvailableIsRejected() {
        hold(60_00);

        assertThatThrownBy(() -> hold(40_01)).extracting("code").isEqualTo("insufficient_funds");
        hold(40_00);
    }

    @Test
    void parallelHoldsCannotOverdraw() throws Exception {
        AtomicInteger succeeded = new AtomicInteger();

        ConcurrentPostingIntegrationTest.runInParallel(10, i -> {
            hold(30_00);
            succeeded.incrementAndGet();
        });

        assertThat(succeeded).hasValue(3);
        assertThat(balances.balanceOf(tenant, wallet.getId()).heldMinor()).isEqualTo(90_00);
    }

    @Test
    void captureOfAFullHoldWorksWithNothingElseAvailable() {
        Hold hold = hold(100_00);

        holds.capture(tenant, hold.getId(), new CaptureCommand(merchant.getId(), null, null));
        projectAll();

        assertThat(balances.balanceOf(tenant, wallet.getId()).availableMinor()).isZero();
    }

    @Test
    void expiredHoldNoLongerBlocks() {
        holds.place(tenant, new PlaceHoldCommand(wallet.getId(), 100_00, Duration.ofMinutes(5), null));
        clock.advance(Duration.ofMinutes(6));

        pay(100_00);
    }

    @Test
    void reversalIsCheckedToo() {
        JournalEntry deposit = transfer(tenant, cash, wallet, 20_00);
        pay(120_00);

        assertThatThrownBy(() -> postingService.reverse(tenant, deposit.getId(), "chargeback"))
                .extracting("code").isEqualTo("insufficient_funds");
    }

    @Test
    void limitIsSetOverTheApi() throws Exception {
        mvc.perform(put("/api/accounts/" + wallet.getId() + "/overdraft-limit").header("X-Api-Key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limitMinor\":2500}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overdraftLimitMinor").value(2500));
        mvc.perform(put("/api/accounts/" + wallet.getId() + "/overdraft-limit").header("X-Api-Key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limitMinor\":-1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/accounts/" + wallet.getId() + "/overdraft-limit").header("X-Api-Key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limitMinor\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overdraftLimitMinor").doesNotExist());

        pay(500_00);
    }

    private void pay(long amount) {
        transfer(tenant, wallet, merchant, amount);
    }

    private Hold hold(long amount) {
        return holds.place(tenant, new PlaceHoldCommand(wallet.getId(), amount, Duration.ofHours(1), null));
    }
}
