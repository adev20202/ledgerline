package io.ledgerline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.ledgerline.account.Account;
import io.ledgerline.account.AccountType;
import io.ledgerline.projection.BalanceQueryService;

class ProjectionOrderingIntegrationTest extends IntegrationTestBase {

    @Autowired
    PlatformTransactionManager transactionManager;
    @Autowired
    BalanceQueryService balances;

    UUID acme;
    Account acmeCash;
    Account acmeRevenue;
    UUID globex;
    Account globexCash;
    Account globexRevenue;

    @BeforeEach
    void setUp() {
        acme = newTenant("acme");
        acmeCash = open(acme, "1000", AccountType.ASSET, "USD");
        acmeRevenue = open(acme, "4000", AccountType.INCOME, "USD");
        globex = newTenant("globex");
        globexCash = open(globex, "1000", AccountType.ASSET, "USD");
        globexRevenue = open(globex, "4000", AccountType.INCOME, "USD");
    }

    @Test
    void eventCommittedLateIsStillProjected() throws Exception {
        CountDownLatch posted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        // acme's post takes a lower event id but commits last
        CompletableFuture<Void> slow = CompletableFuture.runAsync(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    transfer(acme, acmeCash, acmeRevenue, 700);
                    posted.countDown();
                    await(release);
                }));
        assertThat(posted.await(10, TimeUnit.SECONDS)).isTrue();

        transfer(globex, globexCash, globexRevenue, 300);
        projectAll();
        assertThat(balances.balanceOf(globex, globexCash.getId()).balanceMinor()).isEqualTo(300);
        assertThat(balances.balanceOf(acme, acmeCash.getId()).balanceMinor()).isZero();

        release.countDown();
        slow.get(10, TimeUnit.SECONDS);
        projectAll();

        assertThat(balances.balanceOf(acme, acmeCash.getId()).balanceMinor()).isEqualTo(700);
    }

    @Test
    void sameTenantPostWaitsForTheOpenOneAndBothAreProjected() throws Exception {
        CountDownLatch posted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    transfer(acme, acmeCash, acmeRevenue, 1);
                    posted.countDown();
                    await(release);
                }));
        assertThat(posted.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Void> second = CompletableFuture.runAsync(() -> transfer(acme, acmeCash, acmeRevenue, 10));

        Thread.sleep(300);
        assertThat(second).isNotDone();
        projectAll();
        assertThat(balances.balanceOf(acme, acmeCash.getId()).balanceMinor()).isZero();

        release.countDown();
        first.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS);
        projectAll();

        assertThat(balances.balanceOf(acme, acmeCash.getId()).balanceMinor()).isEqualTo(11);
    }

    @Test
    void parallelProjectorsApplyEachEventOnce() throws Exception {
        for (int i = 0; i < 60; i++) {
            transfer(i % 2 == 0 ? acme : globex, i % 2 == 0 ? acmeCash : globexCash,
                    i % 2 == 0 ? acmeRevenue : globexRevenue, 5);
        }

        List<Throwable> failures = ConcurrentPostingIntegrationTest.runInParallel(4, i -> projectAll());

        assertThat(failures).isEmpty();
        assertThat(balances.balanceOf(acme, acmeCash.getId()).balanceMinor()).isEqualTo(150);
        assertThat(balances.balanceOf(globex, globexCash.getId()).balanceMinor()).isEqualTo(150);
        assertThat(projector.checkpoint(acme)).isEqualTo(32);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
