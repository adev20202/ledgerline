package io.ledgerline.projection;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.ledgerline.config.LedgerProperties;
import io.ledgerline.event.LedgerEvent;
import io.ledgerline.event.LedgerEventRepository;
import io.ledgerline.event.LedgerEventType;
import io.ledgerline.event.Payloads;

/**
 * Builds account_balances from the event log, one tenant at a time.
 *
 * <p>Each tenant has its own checkpoint, a tenant_sequence. Events of one
 * tenant become visible in sequence order (the counter row in
 * tenant_event_sequences is locked until commit), so reading past the
 * checkpoint never skips an event that commits late. The checkpoint row is
 * locked while a batch is applied, so several projector instances can run
 * without applying an event twice.
 */
@Component
public class BalanceProjector {

    public static final String NAME = "account_balances";
    private static final Logger log = LoggerFactory.getLogger(BalanceProjector.class);

    private static final String TENANTS_BEHIND = """
            select s.tenant_id from tenant_event_sequences s
            left join projection_tenant_checkpoints c on c.tenant_id = s.tenant_id and c.name = ?
            where s.last_sequence > coalesce(c.last_sequence, 0)
            order by s.tenant_id
            """;

    private final LedgerEventRepository events;
    private final AccountBalanceRepository balances;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final LedgerProperties properties;
    private final Clock clock;

    public BalanceProjector(LedgerEventRepository events, AccountBalanceRepository balances, JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager, ObjectMapper objectMapper, LedgerProperties properties,
            Clock clock) {
        this.events = events;
        this.balances = balances;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Projects the next batch for every tenant that has unprojected events.
     * Returns how many events it applied in total.
     */
    public int runOnce() {
        int total = 0;
        for (UUID tenantId : jdbc.queryForList(TENANTS_BEHIND, UUID.class, NAME)) {
            Integer applied = transactions.execute(status -> projectBatch(tenantId));
            total += applied == null ? 0 : applied;
        }
        return total;
    }

    /** The last tenant sequence applied for the tenant, 0 if none. */
    public long checkpoint(UUID tenantId) {
        List<Long> found = jdbc.queryForList(
                "select last_sequence from projection_tenant_checkpoints where name = ? and tenant_id = ?",
                Long.class, NAME, tenantId);
        return found.isEmpty() ? 0 : found.get(0);
    }

    private int projectBatch(UUID tenantId) {
        Instant now = clock.instant();
        jdbc.update("""
                insert into projection_tenant_checkpoints (name, tenant_id, last_sequence, updated_at)
                values (?, ?, 0, ?) on conflict do nothing
                """, NAME, tenantId, Timestamp.from(now));
        long last = jdbc.queryForObject("""
                select last_sequence from projection_tenant_checkpoints
                where name = ? and tenant_id = ? for update
                """, Long.class, NAME, tenantId);

        List<LedgerEvent> batch = events.findByTenantIdAndTenantSequenceGreaterThanOrderByTenantSequenceAsc(
                tenantId, last, Limit.of(properties.projection().batchSize()));
        if (batch.isEmpty()) {
            return 0;
        }
        for (LedgerEvent event : batch) {
            if (event.getEventType() == LedgerEventType.ENTRY_POSTED) {
                applyPosted(event, read(event, Payloads.EntryPosted.class), now);
            }
        }

        long newLast = batch.get(batch.size() - 1).getTenantSequence();
        jdbc.update("""
                update projection_tenant_checkpoints set last_sequence = ?, updated_at = ?
                where name = ? and tenant_id = ?
                """, newLast, Timestamp.from(now), NAME, tenantId);
        log.debug("Projected {} events for tenant {} up to sequence {}", batch.size(), tenantId, newLast);
        return batch.size();
    }

    private void applyPosted(LedgerEvent event, Payloads.EntryPosted posted, Instant now) {
        for (Payloads.PostedLine line : posted.lines()) {
            AccountBalance balance = balances.findById(line.accountId())
                    .orElseGet(() -> balances.save(new AccountBalance(line.accountId(), event.getTenantId(),
                            posted.currency(), event.getTenantSequence(), now)));
            balance.apply(line.direction(), line.amountMinor(), event.getTenantSequence(), now);
        }
    }

    private <T> T read(LedgerEvent event, Class<T> type) {
        try {
            return objectMapper.treeToValue(event.getPayload(), type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable payload in event " + event.getId(), e);
        }
    }
}
