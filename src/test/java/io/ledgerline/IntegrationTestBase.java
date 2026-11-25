package io.ledgerline;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import io.ledgerline.account.Account;
import io.ledgerline.account.AccountService;
import io.ledgerline.account.AccountType;
import io.ledgerline.common.Direction;
import io.ledgerline.journal.JournalEntry;
import io.ledgerline.journal.PostingService;
import io.ledgerline.projection.BalanceProjector;
import io.ledgerline.tenant.TenantService;

/** Runs against a real PostgreSQL database, see README for setup. */
@SpringBootTest
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected TenantService tenantService;
    @Autowired
    protected AccountService accountService;
    @Autowired
    protected PostingService postingService;
    @Autowired
    protected BalanceProjector projector;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("""
                truncate table idempotency_keys, projection_tenant_checkpoints, account_balances, ledger_events, tenant_event_sequences,
                    journal_lines, journal_entries, accounts, tenants restart identity cascade
                """);
    }

    protected UUID newTenant(String slug) {
        return tenantService.create("Tenant " + slug, slug).tenant().getId();
    }

    protected Account open(UUID tenantId, String code, AccountType type, String currency) {
        return accountService.open(tenantId, new AccountService.OpenAccountCommand(code, code, type, currency));
    }

    protected JournalEntry transfer(UUID tenantId, Account debit, Account credit, long amount) {
        return postingService.post(tenantId, new PostingService.PostEntryCommand(null, "transfer", null,
                debit.getCurrency(), List.of(
                        new PostingService.Line(debit.getId(), Direction.DEBIT, amount, null),
                        new PostingService.Line(credit.getId(), Direction.CREDIT, amount, null))));
    }

    protected void projectAll() {
        while (projector.runOnce() > 0) {
            // drain
        }
    }
}
