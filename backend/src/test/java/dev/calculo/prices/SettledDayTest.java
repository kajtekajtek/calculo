package dev.calculo.prices;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class SettledDayTest {
    private static LocalDate settled(String instant) {
        return PriceSyncService.lastSettledDay(Instant.parse(instant));
    }

    @Test
    void beforeTheEveningTheLastSettledDayIsThePreviousSession() {
        assertThat(settled("2026-11-03T10:00:00Z")).isEqualTo(LocalDate.of(2026, 11, 2)); // Tuesday 11:00
        assertThat(settled("2026-11-02T10:00:00Z")).isEqualTo(LocalDate.of(2026, 10, 30)); // Monday: Friday
    }

    @Test
    void fromTenInTheEveningInWarsawTodayIsSettled() {
        assertThat(settled("2026-11-02T20:59:00Z")).isEqualTo(LocalDate.of(2026, 10, 30)); // 21:59 CET
        assertThat(settled("2026-11-02T21:00:00Z")).isEqualTo(LocalDate.of(2026, 11, 2)); // 22:00 CET
        assertThat(settled("2026-07-01T20:00:00Z")).isEqualTo(LocalDate.of(2026, 7, 1)); // 22:00 CEST
    }

    @Test
    void weekendsFallBackToFriday() {
        assertThat(settled("2026-10-31T21:30:00Z")).isEqualTo(LocalDate.of(2026, 10, 30)); // Saturday evening
        assertThat(settled("2026-11-01T22:30:00Z")).isEqualTo(LocalDate.of(2026, 10, 30)); // Sunday evening
    }
}
