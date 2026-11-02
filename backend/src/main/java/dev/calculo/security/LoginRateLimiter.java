package dev.calculo.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import org.springframework.stereotype.Component;

/** In-memory limiter: at most 5 failed logins per minute, globally (there is a single user). */
@Component
public class LoginRateLimiter {
    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(1);

    private final Clock clock;
    private final Deque<Instant> failures = new ArrayDeque<>();

    public LoginRateLimiter(Clock clock) {
        this.clock = clock;
    }

    public synchronized boolean isBlocked() {
        prune();
        return failures.size() >= MAX_FAILURES;
    }

    public synchronized void recordFailure() {
        prune();
        failures.addLast(clock.instant());
    }

    public synchronized void reset() {
        failures.clear();
    }

    private void prune() {
        Instant cutoff = clock.instant().minus(WINDOW);
        while (!failures.isEmpty() && !failures.peekFirst().isAfter(cutoff)) failures.removeFirst();
    }
}
