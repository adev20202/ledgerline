package io.ledgerline.event;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;

import io.ledgerline.web.TenantId;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Lets integrations follow a tenant's ledger by polling with the last sequence they saw. */
@RestController
@RequestMapping("/api/events")
@Validated
public class EventFeedController {

    private final LedgerEventRepository events;

    public EventFeedController(LedgerEventRepository events) {
        this.events = events;
    }

    public record EventView(long sequence, LedgerEventType type, Instant occurredAt, JsonNode payload) {
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<EventView> feed(TenantId tenant,
            @RequestParam(defaultValue = "0") @Min(0) long afterSequence,
            @RequestParam(defaultValue = "100") @Min(1) @Max(1000) int limit) {
        return events.findByTenantIdAndTenantSequenceGreaterThanOrderByTenantSequenceAsc(
                        tenant.value(), afterSequence, Limit.of(limit))
                .stream()
                .map(e -> new EventView(e.getTenantSequence(), e.getEventType(), e.getOccurredAt(), e.getPayload()))
                .toList();
    }
}
