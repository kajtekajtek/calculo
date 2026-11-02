package dev.calculo.prices;

import java.time.Duration;

/** The provider answered 429: stop asking it for a while. {@code retryAfter} is null when it did not say how long. */
public class RateLimitedException extends PriceUnavailableException {
    private final transient Duration retryAfter;

    public RateLimitedException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
