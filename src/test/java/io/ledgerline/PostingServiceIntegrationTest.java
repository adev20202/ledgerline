package io.ledgerline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.ledgerline.account.Account;
import io.ledgerline.account.AccountType;
import io.ledgerline.common.ConflictException;
import io.ledgerline.common.Direction;
import io.ledgerline.common.LedgerRuleException;
import io.ledgerline.journal.EntryStatus;
import io.ledgerline.journal.JournalEntry;
import io.ledgerline.journal.JournalQueryService;
import io.ledgerline.journal.PostingService.Line;
import io.ledgerline.journal.PostingService.PostEntryCommand;

import org.springframework.beans.factory.annotation.Autowired;

class PostingServiceIntegrationTest extends IntegrationTestBase {

    @Autowired
    JournalQueryService journalQueryService;

    UUID tenant;
    Account cash;
    Account revenue;

    @BeforeEach
    void setUp() {
        tenant = newTenant("acme");
        cash = open(tenant, "1000", AccountType.ASSET, "USD");
        revenue = open(tenant, "4000", AccountType.INCOME, "USD");
    }

    @Test
    void postsBalancedEntryAndAppendsEvent() {
        JournalEntry entry = transfer(tenant, cash, revenue, 12_50);

        assertThat(entry.getStatus()).isEqualTo(EntryStatus.POSTED);
        assertThat(entry.getLines()).hasSize(2);
        Integer posted = jdbc.queryForObject(
                "select count(*) from ledger_events where tenant_id = ? and event_type = 'ENTRY_POSTED'",
                Integer.class, tenant);
        assertThat(posted).isEqualTo(1);
    }

    @Test
    void tenantSequenceCountsPerTenant() {
        UUID other = newTenant("other");
        open(other, "1000", AccountType.ASSET, "USD");
        transfer(tenant, cash, revenue, 100);

        List<Long> sequences = jdbc.queryForList(
                "select tenant_sequence from ledger_events where tenant_id = ? order by id", Long.class, tenant);
        assertThat(sequences).containsExactly(1L, 2L, 3L);
        assertThat(jdbc.queryForObject("select max(tenant_sequence) from ledger_events where tenant_id = ?",
                Long.class, other)).isEqualTo(1L);
    }

    @Test
    void rejectsUnbalancedEntry() {
        PostEntryCommand command = new PostEntryCommand(null, "bad", null, "USD", List.of(
                new Line(cash.getId(), Direction.DEBIT, 100, null),
                new Line(revenue.getId(), Direction.CREDIT, 99, null)));

        assertThatThrownBy(() -> postingService.post(tenant, command))
                .isInstanceOf(LedgerRuleException.class)
                .extracting("code").isEqualTo("unbalanced");
        assertThat(jdbc.queryForObject("select count(*) from journal_entries", Integer.class)).isZero();
    }

    @Test
    void rejectsSingleLine() {
        PostEntryCommand command = new PostEntryCommand(null, "bad", null, "USD",
                List.of(new Line(cash.getId(), Direction.DEBIT, 100, null)));

        assertThatThrownBy(() -> postingService.post(tenant, command))
                .extracting("code").isEqualTo("too_few_lines");
    }

    @Test
    void rejectsCurrencyMismatch() {
        Account euros = open(tenant, "1010", AccountType.ASSET, "EUR");

        assertThatThrownBy(() -> transfer(tenant, euros, revenue, 100))
                .extracting("code").isEqualTo("currency_mismatch");
    }

    @Test
    void rejectsAccountOfAnotherTenant() {
        UUID other = newTenant("other");
        Account foreign = open(other, "1000", AccountType.ASSET, "USD");

        assertThatThrownBy(() -> transfer(tenant, foreign, revenue, 100))
                .extracting("code").isEqualTo("unknown_account");
    }

    @Test
    void rejectsClosedAccount() {
        Account spare = open(tenant, "1999", AccountType.ASSET, "USD");
        accountService.close(tenant, spare.getId());

        assertThatThrownBy(() -> transfer(tenant, spare, revenue, 100))
                .extracting("code").isEqualTo("account_closed");
    }

    @Test
    void rejectsDuplicateReference() {
        PostEntryCommand command = new PostEntryCommand("INV-1", "invoice", null, "USD", List.of(
                new Line(cash.getId(), Direction.DEBIT, 100, null),
                new Line(revenue.getId(), Direction.CREDIT, 100, null)));
        postingService.post(tenant, command);

        assertThatThrownBy(() -> postingService.post(tenant, command)).isInstanceOf(ConflictException.class);
    }

    @Test
    void reversalMirrorsOriginalAndMarksIt() {
        JournalEntry original = transfer(tenant, cash, revenue, 500);

        JournalEntry reversal = postingService.reverse(tenant, original.getId(), "wrong customer");

        assertThat(reversal.getReversalOf()).isEqualTo(original.getId());
        assertThat(reversal.getLines()).extracting(l -> l.getDirection())
                .containsExactly(Direction.CREDIT, Direction.DEBIT);
        JournalQueryService.EntryView reloaded = journalQueryService.get(tenant, original.getId());
        assertThat(reloaded.status()).isEqualTo(EntryStatus.REVERSED);
        assertThat(reloaded.reversedBy()).isEqualTo(reversal.getId());
    }

    @Test
    void cannotReverseTwiceOrReverseAReversal() {
        JournalEntry original = transfer(tenant, cash, revenue, 500);
        JournalEntry reversal = postingService.reverse(tenant, original.getId(), "oops");

        assertThatThrownBy(() -> postingService.reverse(tenant, original.getId(), "again"))
                .extracting("code").isEqualTo("already_reversed");
        assertThatThrownBy(() -> postingService.reverse(tenant, reversal.getId(), "undo"))
                .extracting("code").isEqualTo("cannot_reverse_reversal");
    }
}
