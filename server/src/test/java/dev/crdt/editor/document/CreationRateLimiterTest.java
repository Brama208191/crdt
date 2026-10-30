package dev.crdt.editor.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class CreationRateLimiterTest {

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void blocksAfterLimitAndRecoversAfterWindow() {
        MutableClock clock = new MutableClock();
        CreationRateLimiter limiter = new CreationRateLimiter(clock);

        for (int i = 0; i < CreationRateLimiter.MAX_PER_WINDOW; i++) {
            assertThat(limiter.tryAcquire("1.2.3.4")).isTrue();
        }
        assertThat(limiter.tryAcquire("1.2.3.4")).isFalse();
        assertThat(limiter.tryAcquire("5.6.7.8")).isTrue();

        clock.now = clock.now.plus(CreationRateLimiter.WINDOW).plusSeconds(1);
        assertThat(limiter.tryAcquire("1.2.3.4")).isTrue();
    }
}
