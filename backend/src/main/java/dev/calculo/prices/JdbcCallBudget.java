package dev.calculo.prices;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;

/** A daily allowance counted per UTC day in the database, so restarts do not reset it. */
public class JdbcCallBudget implements CallBudget {
    private final JdbcClient jdbc;
    private final int limit;
    private final Clock clock;

    public JdbcCallBudget(JdbcClient jdbc, int limit, Clock clock) {
        if (limit < 1) throw new IllegalArgumentException("calculo.prices.daily-call-limit must be at least 1, got " + limit);
        this.jdbc = jdbc;
        this.limit = limit;
        this.clock = clock;
    }

    @Override
    public boolean tryUse() {
        LocalDate day = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        // One statement, so two concurrent callers cannot both take the last request.
        return jdbc.sql("""
                insert into provider_call (day, calls) values (:day, 1)
                on conflict (day) do update set calls = provider_call.calls + 1 where provider_call.calls < :limit
                returning calls""")
                .param("day", Date.valueOf(day)).param("limit", limit)
                .query(Integer.class).optional().isPresent();
    }
}
