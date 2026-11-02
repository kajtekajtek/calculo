package dev.calculo.bonds;

import static org.assertj.core.api.Assertions.assertThat;

import dev.calculo.PostgresTestBase;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BondApiIT extends PostgresTestBase {

    static final Map<String, EdoSeries> SOURCE = new ConcurrentHashMap<>();

    @TestConfiguration
    static class Stubs {
        @Bean @Primary
        EdoSeriesSource stubSource() {
            return code -> Optional.ofNullable(SOURCE.get(code));
        }

        @Bean @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-11-02T10:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        dev.calculo.TestAuth.login(rest);
        SOURCE.clear();
        jdbc.update("delete from bond_purchase");
        jdbc.update("delete from edo_series");
        jdbc.update("delete from cpi_announcement");
        jdbc.update("delete from account");
    }

    private ResponseEntity<Map> post(Map<String, Object> body) {
        return rest.postForEntity("/api/bonds", body, Map.class);
    }

    private ResponseEntity<Map> put(String path, Map<String, Object> body) {
        return rest.exchange(path, HttpMethod.PUT, new HttpEntity<>(body), Map.class);
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    @Test
    void futureDateAndNonPositiveQuantityAreRejected() {
        assertThat(post(Map.of("purchaseDate", "2026-11-03", "quantity", 1)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(Map.of("purchaseDate", "2026-03-10", "quantity", 0)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(Map.of("quantity", 1)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(count("bond_purchase")).isZero();
        assertThat(count("account")).isZero();
    }

    @Test
    void knownSourceSeriesGivesCreatedWithComputedCodeAndBondAccount() {
        SOURCE.put("EDO0336", new EdoSeries("EDO0336", new BigDecimal("5.35"), new BigDecimal("2.00")));
        ResponseEntity<Map> r = post(Map.of("purchaseDate", "2026-03-10", "quantity", 3));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody()).containsEntry("seriesCode", "EDO0336").containsEntry("quantity", 3)
                .containsEntry("purchaseDate", "2026-03-10");
        assertThat(jdbc.queryForObject("select name from account where id = 'bonds'", String.class))
                .isEqualTo("Obligacje");
        assertThat(jdbc.queryForObject("select first_year_rate from edo_series where code = 'EDO0336'",
                BigDecimal.class)).isEqualByComparingTo("5.35");
        List<?> list = rest.getForObject("/api/bonds", List.class);
        assertThat(list).hasSize(1);
    }

    @Test
    void unknownSeriesWithoutRatesGivesConflictAndStoresNothing() {
        ResponseEntity<Map> r = post(Map.of("purchaseDate", "2026-03-10", "quantity", 1));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody()).containsEntry("seriesCode", "EDO0336").containsEntry("needsRates", true)
                .containsKey("message");
        assertThat(count("bond_purchase")).isZero();
        assertThat(count("edo_series")).isZero();
        assertThat(count("account")).isZero();
    }

    @Test
    void repostWithRatesStoresSeriesAndPurchase() {
        assertThat(post(Map.of("purchaseDate", "2026-03-10", "quantity", 1)).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        ResponseEntity<Map> r = post(Map.of("purchaseDate", "2026-03-10", "quantity", 1,
                "firstYearRate", 5.6, "margin", 2.0));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(count("bond_purchase")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select margin from edo_series where code = 'EDO0336'", BigDecimal.class))
                .isEqualByComparingTo("2.00");
    }

    @Test
    void putChangingMonthRecomputesSeriesAndDeleteRemoves() {
        SOURCE.put("EDO0336", new EdoSeries("EDO0336", new BigDecimal("5.35"), new BigDecimal("2.00")));
        SOURCE.put("EDO0436", new EdoSeries("EDO0436", new BigDecimal("5.50"), new BigDecimal("2.10")));
        Number id = (Number) post(Map.of("purchaseDate", "2026-03-10", "quantity", 1)).getBody().get("id");

        ResponseEntity<Map> r = put("/api/bonds/" + id, Map.of("purchaseDate", "2026-04-05", "quantity", 2));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("seriesCode", "EDO0436").containsEntry("quantity", 2);
        assertThat(jdbc.queryForObject("select series_code from bond_purchase", String.class)).isEqualTo("EDO0436");

        assertThat(rest.exchange("/api/bonds/" + id, HttpMethod.DELETE, null, Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(count("bond_purchase")).isZero();
        assertThat(rest.exchange("/api/bonds/" + id, HttpMethod.DELETE, null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void putToMonthWithoutRatesGivesConflictAndChangesNothing() {
        SOURCE.put("EDO0336", new EdoSeries("EDO0336", new BigDecimal("5.35"), new BigDecimal("2.00")));
        Number id = (Number) post(Map.of("purchaseDate", "2026-03-10", "quantity", 1)).getBody().get("id");
        ResponseEntity<Map> r = put("/api/bonds/" + id, Map.of("purchaseDate", "2026-05-05", "quantity", 7));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody()).containsEntry("seriesCode", "EDO0536").containsEntry("needsRates", true);
        assertThat(jdbc.queryForObject("select series_code || quantity || purchase_date from bond_purchase",
                String.class)).isEqualTo("EDO0336" + 1 + "2026-03-10");
        assertThat(count("edo_series")).isEqualTo(1);
    }

    @Test
    void cpiAcceptsNegativeValueAndCanBeDeleted() {
        assertThat(put("/api/rates/cpi/2026-02", Map.of("pct", -0.3)).getStatusCode()).isEqualTo(HttpStatus.OK);
        Map rates = rest.getForObject("/api/rates", Map.class);
        List<Map> cpi = (List<Map>) rates.get("cpi");
        assertThat(cpi).hasSize(1);
        assertThat(cpi.get(0)).containsEntry("month", "2026-02");
        assertThat(((Number) cpi.get(0).get("pct")).doubleValue()).isEqualTo(-0.3);
        assertThat(rest.exchange("/api/rates/cpi/2026-02", HttpMethod.DELETE, null, Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(count("cpi_announcement")).isZero();
        assertThat(put("/api/rates/cpi/2026-13", Map.of("pct", 1)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void seriesCanBeSetAndRatesListMissingCpiSorted() {
        assertThat(put("/api/rates/series/EDO0134", Map.of("firstYearRate", 4.5, "margin", 1.25)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(put("/api/rates/series/FOO", Map.of("firstYearRate", 4.5, "margin", 1.25)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        // purchase 2024-01-15: periods start 2025-01-15 (needs 2024-12), 2026-01-15 (needs 2025-12)
        assertThat(post(Map.of("purchaseDate", "2024-01-15", "quantity", 1)).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        Map rates = rest.getForObject("/api/rates", Map.class);
        assertThat((List<Map>) rates.get("series")).hasSize(1);
        assertThat((List<String>) rates.get("missingCpi")).containsExactly("2024-12", "2025-12");

        put("/api/rates/cpi/2024-12", Map.of("pct", 3.1));
        rates = rest.getForObject("/api/rates", Map.class);
        assertThat((List<String>) rates.get("missingCpi")).containsExactly("2025-12");
    }
}
