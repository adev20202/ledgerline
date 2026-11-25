package io.ledgerline.account;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.ledgerline.common.LedgerRuleException;
import jakarta.persistence.EntityManager;

/**
 * Enforces overdraft limits when a change would lower an account's balance.
 *
 * <p>The projection can lag, so this reads the balance from journal_lines
 * and the live holds directly. The account rows are locked first, in id
 * order, so two changes to the same limited account run one after the
 * other and the second sees the first.
 */
@Component
public class OverdraftGuard {

    private static final String JOURNAL_NET_DEBIT = """
            select coalesce(sum(case when direction = 'DEBIT' then amount_minor else -amount_minor end), 0)
            from journal_lines where account_id = ?
            """;
    private static final String LIVE_HOLDS = """
            select coalesce(sum(amount_minor), 0) from holds
            where account_id = ? and status = 'ACTIVE' and expires_at > ?
            """;

    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final Clock clock;

    public OverdraftGuard(JdbcTemplate jdbc, EntityManager entityManager, Clock clock) {
        this.jdbc = jdbc;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /**
     * @param changes per account, the change to its balance on its normal
     *                side that is about to be made
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void check(Map<Account, Long> changes) {
        List<Map.Entry<Account, Long>> guarded = changes.entrySet().stream()
                .filter(e -> e.getKey().getOverdraftLimitMinor() != null && e.getValue() < 0)
                .sorted(Comparator.comparing(e -> e.getKey().getId()))
                .toList();
        if (guarded.isEmpty()) {
            return;
        }
        // pending hold changes in this transaction must be visible to the queries below
        entityManager.flush();

        for (Map.Entry<Account, Long> e : guarded) {
            jdbc.queryForObject("select 1 from accounts where id = ? for update", Integer.class, e.getKey().getId());
        }
        Timestamp now = Timestamp.from(clock.instant());
        for (Map.Entry<Account, Long> e : guarded) {
            Account account = e.getKey();
            long netDebit = jdbc.queryForObject(JOURNAL_NET_DEBIT, Long.class, account.getId());
            long balance = account.getType().signedBalance(Math.max(netDebit, 0), Math.max(-netDebit, 0));
            long held = jdbc.queryForObject(LIVE_HOLDS, Long.class, account.getId(), now);
            long after = Math.subtractExact(Math.addExact(balance, e.getValue()), held);
            if (after < -account.getOverdraftLimitMinor()) {
                throw new LedgerRuleException("insufficient_funds", "Account " + account.getCode()
                        + " would have " + after + " available, below its limit of -"
                        + account.getOverdraftLimitMinor());
            }
        }
    }
}
