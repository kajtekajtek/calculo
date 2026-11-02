package dev.calculo.prices;

import java.time.Instant;

/** {@code running}: a sync is queued or in progress. {@code notBefore}: end of a rate-limit cooldown, null when none. */
public record PriceSyncState(boolean running, Instant notBefore) {}
