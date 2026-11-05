package dev.calculo.prices;

import static org.assertj.core.api.Assertions.assertThat;

import dev.calculo.PostgresTestBase;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcCallBudgetIT extends PostgresTestBase {
    @Autowired JdbcClient jdbcClient;
    @Autowired JdbcTemplate jdbc;

    private volatile Instant now = Instant.parse("2026-11-02T23:59:00Z");
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    };

    @BeforeEach
    void clear() {
        jdbc.update("delete from provider_call");
    }

    @Test
    void allowsTheLimitPerUtcDayThenRefuses() {
        JdbcCallBudget budget = new JdbcCallBudget(jdbcClient, 2, clock);

        assertThat(budget.tryUse()).isTrue();
        assertThat(budget.tryUse()).isTrue();
        assertThat(budget.tryUse()).isFalse();
        assertThat(budget.tryUse()).isFalse();
        assertThat(jdbc.queryForObject("select calls from provider_call where day = '2026-11-02'", Integer.class))
                .isEqualTo(2);

        now = Instant.parse("2026-11-03T00:00:00Z"); // 01:00 in Warsaw, but EODHD counts in UTC
        assertThat(budget.tryUse()).isTrue();
    }

    @Test
    void theCountSurvivesANewBudgetObjectLikeARestart() {
        new JdbcCallBudget(jdbcClient, 1, clock).tryUse();

        assertThat(new JdbcCallBudget(jdbcClient, 1, clock).tryUse()).isFalse();
    }
}
