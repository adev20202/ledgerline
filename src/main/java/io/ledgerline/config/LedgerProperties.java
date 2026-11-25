package io.ledgerline.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "ledger")
public record LedgerProperties(String adminToken, @DefaultValue Projection projection,
        @DefaultValue Idempotency idempotency) {

    public record Projection(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("500") int batchSize,
            @DefaultValue("500") long pollIntervalMs) {
    }

    /** How long a stored response is replayed for the same Idempotency-Key. */
    public record Idempotency(@DefaultValue("PT24H") Duration ttl) {
    }
}
