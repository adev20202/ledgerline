package io.ledgerline.projection;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.ledgerline.config.LedgerProperties;
import io.ledgerline.event.LedgerEvent;
import io.ledgerline.event.LedgerEventRepository;
import io.ledgerline.event.LedgerEventType;
import io.ledgerline.event.Payloads;

/**
 * Builds account_balances from the event log. Each batch and its checkpoint
 * are written in one transaction.
 */
@Component
public class BalanceProjector {

    public static final String NAME = "account_balances";
    private static final Logger log = LoggerFactory.getLogger(BalanceProjector.class);

    private final LedgerEventRepository events;
    private final AccountBalanceRepository balances;
    private final ProjectionCheckpointRepository checkpoints;
    private final ObjectMapper objectMapper;
    private final LedgerProperties properties;
    private final Clock clock;

    public BalanceProjector(LedgerEventRepository events, AccountBalanceRepository balances,
            ProjectionCheckpointRepository checkpoints, ObjectMapper objectMapper, LedgerProperties properties,
            Clock clock) {
        this.events = events;
        this.balances = balances;
        this.checkpoints = checkpoints;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    /** Projects the next batch of events. Returns how many events it read. */
    @Transactional
    public int runOnce() {
        Instant now = clock.instant();
        ProjectionCheckpoint checkpoint = checkpoints.findById(NAME)
                .orElseGet(() -> checkpoints.save(new ProjectionCheckpoint(NAME, now)));
        List<LedgerEvent> batch = events.findByIdGreaterThanOrderByIdAsc(checkpoint.getLastEventId(),
                Limit.of(properties.projection().batchSize()));
        if (batch.isEmpty()) {
            return 0;
        }

        for (LedgerEvent event : batch) {
            if (event.getEventType() == LedgerEventType.ENTRY_POSTED) {
                applyPosted(event, read(event, Payloads.EntryPosted.class), now);
            }
        }
        long last = batch.get(batch.size() - 1).getId();
        checkpoint.advanceTo(last, now);
        log.debug("Projected {} events up to {}", batch.size(), last);
        return batch.size();
    }

    @Transactional(readOnly = true)
    public long checkpoint() {
        return checkpoints.findById(NAME).map(ProjectionCheckpoint::getLastEventId).orElse(0L);
    }

    private void applyPosted(LedgerEvent event, Payloads.EntryPosted posted, Instant now) {
        for (Payloads.PostedLine line : posted.lines()) {
            AccountBalance balance = balances.findById(line.accountId())
                    .orElseGet(() -> balances.save(
                            new AccountBalance(line.accountId(), event.getTenantId(), posted.currency(),
                                    event.getId(), now)));
            balance.apply(line.direction(), line.amountMinor(), event.getId(), now);
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
