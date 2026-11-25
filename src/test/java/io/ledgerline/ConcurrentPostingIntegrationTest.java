package io.ledgerline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;

import io.ledgerline.account.Account;
import io.ledgerline.account.AccountType;

class ConcurrentPostingIntegrationTest extends IntegrationTestBase {

    private static final int THREADS = 20;

    @Test
    void parallelPostsToOneTenantAllSucceedWithContiguousSequences() throws Exception {
        UUID tenant = newTenant("acme");
        Account cash = open(tenant, "1000", AccountType.ASSET, "USD");
        Account revenue = open(tenant, "4000", AccountType.INCOME, "USD");

        List<Throwable> failures = runInParallel(THREADS, i -> transfer(tenant, cash, revenue, 100 + i));

        assertThat(failures).isEmpty();
        List<Long> sequences = jdbc.queryForList(
                "select tenant_sequence from ledger_events where tenant_id = ? order by tenant_sequence",
                Long.class, tenant);
        // two account openings, then one event per post
        assertThat(sequences).containsExactlyElementsOf(LongStream.rangeClosed(1, THREADS + 2).boxed().toList());
        assertThat(jdbc.queryForObject("select last_sequence from tenant_event_sequences where tenant_id = ?",
                Long.class, tenant)).isEqualTo(THREADS + 2L);

        projectAll();
        long expected = LongStream.range(0, THREADS).map(i -> 100 + i).sum();
        assertThat(jdbc.queryForObject("select debit_total_minor from account_balances where account_id = ?",
                Long.class, cash.getId())).isEqualTo(expected);
    }

    @Test
    void parallelPostsAcrossTenantsKeepTheirOwnNumbering() throws Exception {
        UUID acme = newTenant("acme");
        UUID globex = newTenant("globex");
        Account[] acmeAccounts = {open(acme, "1000", AccountType.ASSET, "USD"),
                open(acme, "4000", AccountType.INCOME, "USD")};
        Account[] globexAccounts = {open(globex, "1000", AccountType.ASSET, "USD"),
                open(globex, "4000", AccountType.INCOME, "USD")};

        List<Throwable> failures = runInParallel(THREADS, i -> {
            if (i % 2 == 0) {
                transfer(acme, acmeAccounts[0], acmeAccounts[1], 10);
            } else {
                transfer(globex, globexAccounts[0], globexAccounts[1], 10);
            }
        });

        assertThat(failures).isEmpty();
        for (UUID tenant : List.of(acme, globex)) {
            List<Long> sequences = jdbc.queryForList(
                    "select tenant_sequence from ledger_events where tenant_id = ? order by tenant_sequence",
                    Long.class, tenant);
            assertThat(sequences).containsExactlyElementsOf(
                    LongStream.rangeClosed(1, THREADS / 2 + 2).boxed().toList());
        }
    }

    @Test
    void rolledBackPostLeavesNoGap() {
        UUID tenant = newTenant("acme");
        Account cash = open(tenant, "1000", AccountType.ASSET, "USD");
        Account revenue = open(tenant, "4000", AccountType.INCOME, "USD");
        Account closed = open(tenant, "1999", AccountType.ASSET, "USD");
        accountService.close(tenant, closed.getId());

        try {
            transfer(tenant, closed, revenue, 100);
        } catch (RuntimeException expected) {
            // rejected: account closed
        }
        transfer(tenant, cash, revenue, 100);

        List<Long> sequences = jdbc.queryForList(
                "select tenant_sequence from ledger_events where tenant_id = ? order by tenant_sequence",
                Long.class, tenant);
        assertThat(sequences).containsExactly(1L, 2L, 3L, 4L, 5L);
    }

    interface Task {
        void run(int index) throws Exception;
    }

    static List<Throwable> runInParallel(int count, Task task) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int index = i;
            futures.add(pool.submit(() -> {
                start.await();
                task.run(index);
                return null;
            }));
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        List<Throwable> failures = new ArrayList<>();
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception e) {
                failures.add(e.getCause() != null ? e.getCause() : e);
            }
        }
        return failures;
    }
}
