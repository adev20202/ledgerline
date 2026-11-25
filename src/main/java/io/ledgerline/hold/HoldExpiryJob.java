package io.ledgerline.hold;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Expired holds already stop counting at their expiry; this records it. */
@Component
@ConditionalOnProperty(prefix = "ledger.holds", name = "expiry-enabled", havingValue = "true", matchIfMissing = true)
public class HoldExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(HoldExpiryJob.class);
    private static final int BATCH = 200;

    private final HoldService holdService;

    public HoldExpiryJob(HoldService holdService) {
        this.holdService = holdService;
    }

    @Scheduled(fixedDelayString = "${ledger.holds.expiry-interval-ms:1000}")
    public void run() {
        try {
            int n;
            while ((n = holdService.expireDue(BATCH)) > 0) {
                log.debug("Expired {} holds", n);
            }
        } catch (RuntimeException e) {
            log.error("Hold expiry failed, will retry", e);
        }
    }
}
