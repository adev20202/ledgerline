package io.ledgerline.event;

import java.time.Clock;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Appends events in the caller's transaction, so an event is stored if and
 * only if the change it describes is stored.
 */
@Component
public class EventStore {

    private final LedgerEventRepository events;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public EventStore(LedgerEventRepository events, ObjectMapper objectMapper, Clock clock) {
        this.events = events;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerEvent append(UUID tenantId, LedgerEventType type, Object payload) {
        long next = events.maxSequence(tenantId) + 1;
        JsonNode body = objectMapper.valueToTree(payload);
        return events.save(new LedgerEvent(tenantId, next, type, body, clock.instant()));
    }
}
