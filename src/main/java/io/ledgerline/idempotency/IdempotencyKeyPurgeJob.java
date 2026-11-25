package io.ledgerline.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class IdempotencyKeyPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyKeyPurgeJob.class);

    private final IdempotencyService idempotencyService;

    public IdempotencyKeyPurgeJob(IdempotencyService idempotencyService) {
        this.idempotencyService = idempotencyService;
    }

    @Scheduled(fixedDelayString = "${ledger.idempotency.purge-interval-ms:3600000}")
    public void purge() {
        int removed = idempotencyService.purgeExpired();
        if (removed > 0) {
            log.info("Removed {} expired idempotency keys", removed);
        }
    }
}
