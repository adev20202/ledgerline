package io.ledgerline.event;

import java.time.Clock;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Appends events in the caller's transaction, so an event is stored if and
 * only if the change it describes is stored.
 *
 * <p>The tenant's next sequence number comes from its row in
 * tenant_event_sequences. The update locks that row until the caller commits
 * or rolls back, so appends for one tenant are serialized and a rolled-back
 * append leaves no gap.
 */
@Component
public class EventStore {

    private static final String NEXT_SEQUENCE = """
            insert into tenant_event_sequences as s (tenant_id, last_sequence) values (?, 1)
            on conflict (tenant_id) do update set last_sequence = s.last_sequence + 1
            returning s.last_sequence
            """;

    private final LedgerEventRepository events;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public EventStore(LedgerEventRepository events, JdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock) {
        this.events = events;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerEvent append(UUID tenantId, LedgerEventType type, Object payload) {
        Long next = jdbc.queryForObject(NEXT_SEQUENCE, Long.class, tenantId);
        JsonNode body = objectMapper.valueToTree(payload);
        return events.save(new LedgerEvent(tenantId, next, type, body, clock.instant()));
    }
}
