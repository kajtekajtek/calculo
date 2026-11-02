package dev.calculo.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class LoginRateLimiterTest {
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-02T10:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MutableClock clock = new MutableClock();
    private final LoginRateLimiter limiter = new LoginRateLimiter(clock);

    @Test
    void blocksAfterFiveFailuresUntilTheWindowPasses() {
        for (int i = 0; i < 4; i++) limiter.recordFailure();
        assertThat(limiter.isBlocked()).isFalse();
        limiter.recordFailure();
        assertThat(limiter.isBlocked()).isTrue();
        clock.now = clock.now.plus(Duration.ofSeconds(59));
        assertThat(limiter.isBlocked()).isTrue();
        clock.now = clock.now.plusSeconds(1);
        assertThat(limiter.isBlocked()).isFalse();
    }

    @Test
    void failuresOlderThanAMinuteDoNotAccumulate() {
        for (int i = 0; i < 4; i++) limiter.recordFailure();
        clock.now = clock.now.plus(Duration.ofSeconds(61));
        limiter.recordFailure();
        assertThat(limiter.isBlocked()).isFalse();
    }

    @Test
    void resetClearsTheCounter() {
        for (int i = 0; i < 5; i++) limiter.recordFailure();
        limiter.reset();
        assertThat(limiter.isBlocked()).isFalse();
    }
}
