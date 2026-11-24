package io.ledgerline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.ledgerline.account.Account;
import io.ledgerline.account.AccountType;
import io.ledgerline.journal.JournalEntry;
import io.ledgerline.projection.BalanceQueryService;
import io.ledgerline.projection.BalanceQueryService.TrialBalanceView;

class BalanceProjectionIntegrationTest extends IntegrationTestBase {

    @Autowired
    BalanceQueryService balances;

    UUID tenant;
    Account cash;
    Account payable;
    Account revenue;
    Account rent;

    @BeforeEach
    void setUp() {
        tenant = newTenant("acme");
        cash = open(tenant, "1000", AccountType.ASSET, "USD");
        payable = open(tenant, "2000", AccountType.LIABILITY, "USD");
        revenue = open(tenant, "4000", AccountType.INCOME, "USD");
        rent = open(tenant, "6100", AccountType.EXPENSE, "USD");
    }

    @Test
    void balancesFollowNormalSide() {
        transfer(tenant, cash, revenue, 10_000);
        transfer(tenant, rent, payable, 3_000);
        transfer(tenant, payable, cash, 1_000);
        projectAll();

        assertThat(balances.balanceOf(tenant, cash.getId()).balanceMinor()).isEqualTo(9_000);
        assertThat(balances.balanceOf(tenant, revenue.getId()).balanceMinor()).isEqualTo(10_000);
        assertThat(balances.balanceOf(tenant, rent.getId()).balanceMinor()).isEqualTo(3_000);
        assertThat(balances.balanceOf(tenant, payable.getId()).balanceMinor()).isEqualTo(2_000);
    }

    @Test
    void balanceIsZeroBeforeProjection() {
        transfer(tenant, cash, revenue, 10_000);

        assertThat(balances.balanceOf(tenant, cash.getId()).balanceMinor()).isZero();
        projectAll();
        assertThat(balances.balanceOf(tenant, cash.getId()).balanceMinor()).isEqualTo(10_000);
    }

    @Test
    void rerunAfterCatchingUpChangesNothing() {
        transfer(tenant, cash, revenue, 10_000);
        projectAll();
        long checkpoint = projector.checkpoint();

        assertThat(projector.runOnce()).isZero();
        assertThat(projector.checkpoint()).isEqualTo(checkpoint);
        assertThat(balances.balanceOf(tenant, cash.getId()).balanceMinor()).isEqualTo(10_000);
    }

    @Test
    void reversalBringsBalancesBackToZero() {
        JournalEntry entry = transfer(tenant, cash, revenue, 10_000);
        postingService.reverse(tenant, entry.getId(), "duplicate");
        projectAll();

        assertThat(balances.balanceOf(tenant, cash.getId()).balanceMinor()).isZero();
        assertThat(balances.balanceOf(tenant, revenue.getId()).balanceMinor()).isZero();
    }

    @Test
    void trialBalanceTotalsAgree() {
        transfer(tenant, cash, revenue, 10_000);
        transfer(tenant, rent, cash, 2_500);
        projectAll();

        TrialBalanceView view = balances.trialBalance(tenant, "USD");

        assertThat(view.balanced()).isTrue();
        assertThat(view.totalDebitMinor()).isEqualTo(10_000);
        assertThat(view.rows()).hasSize(4);
    }

    @Test
    void closeRequiresZeroBalance() {
        transfer(tenant, cash, revenue, 100);
        projectAll();

        assertThatThrownBy(() -> accountService.close(tenant, cash.getId()))
                .extracting("code").isEqualTo("account_not_zero");
    }

    @Test
    void projectionKeepsTenantsApart() {
        UUID other = newTenant("other");
        Account otherCash = open(other, "1000", AccountType.ASSET, "USD");
        Account otherRevenue = open(other, "4000", AccountType.INCOME, "USD");
        transfer(tenant, cash, revenue, 100);
        transfer(other, otherCash, otherRevenue, 7);
        projectAll();

        assertThat(balances.trialBalance(tenant, "USD").totalDebitMinor()).isEqualTo(100);
        assertThat(balances.trialBalance(other, "USD").totalDebitMinor()).isEqualTo(7);
    }
}
