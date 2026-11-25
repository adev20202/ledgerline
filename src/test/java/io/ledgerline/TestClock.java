package io.ledgerline;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** System time plus an offset that tests can move forward. */
public class TestClock extends Clock {

    private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

    public void advance(Duration by) {
        offset.updateAndGet(o -> o.plus(by));
    }

    public void reset() {
        offset.set(Duration.ZERO);
    }

    @Override
    public Instant instant() {
        return Instant.now().plus(offset.get());
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.offset(Clock.system(zone), offset.get());
    }
}
