package io.ledgerline.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "ledger")
public record LedgerProperties(String adminToken, @DefaultValue Projection projection) {

    public record Projection(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("500") int batchSize,
            @DefaultValue("500") long pollIntervalMs) {
    }
}
