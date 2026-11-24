package io.ledgerline.projection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "ledger.projection", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BalanceProjectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(BalanceProjectionScheduler.class);

    private final BalanceProjector projector;

    public BalanceProjectionScheduler(BalanceProjector projector) {
        this.projector = projector;
    }

    @Scheduled(fixedDelayString = "${ledger.projection.poll-interval-ms:500}")
    public void poll() {
        try {
            while (projector.runOnce() > 0) {
                // keep going until caught up
            }
        } catch (RuntimeException e) {
            log.error("Balance projection failed, will retry on next poll", e);
        }
    }
}
