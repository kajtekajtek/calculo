package dev.calculo.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import dev.calculo.PostgresTestBase;
import dev.calculo.xtb.XtbFixture;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PortfolioApiIT extends PostgresTestBase {
    private static final double T1 = XtbFixture.serial(Instant.parse("2026-03-02T10:00:00Z"));
    private static final double T2 = XtbFixture.serial(Instant.parse("2026-04-02T10:00:00Z"));

    @TestConfiguration
    static class Stubs {
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
        jdbc.update("delete from bond_purchase");
        jdbc.update("delete from edo_series");
        jdbc.update("delete from cpi_announcement");
        jdbc.update("delete from operation");
        jdbc.update("delete from position_snapshot");
        jdbc.update("delete from price_history");
        jdbc.update("delete from fx_rate");
        jdbc.update("delete from instrument");
        jdbc.update("delete from account");
    }

    private void importFixture() {
        byte[] file = XtbFixture.account("1234567")
                .cash("Deposit", "", "", "", T1, 1000.0, 1L, "", "IKE", null)
                .cash("Stock purchase", "Alpha ETF", "AAA.DE", "ETF", T2, -100.0, 2L, "OPEN BUY 2 @ 50.00", "IKE", 9L)
                .openAggregate("IKE", "Alpha ETF", "AAA.DE", "ETF", 2.0, 110.0)
                .build();
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(new ByteArrayResource(file) {
            @Override public String getFilename() { return "a.xlsx"; }
        }, fileHeaders));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<Map> r = rest.postForEntity("/api/imports/xtb/commit", new HttpEntity<>(body, headers), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        jdbc.update("insert into price_history (ticker, day, close, currency) values ('AAA.DE', '2026-10-30', 60, 'PLN')");
    }

    private static BigDecimal num(Object o) {
        return new BigDecimal(o.toString());
    }

    @Test
    void accountsAreListed() {
        importFixture();
        ResponseEntity<List> r = rest.getForEntity("/api/accounts", List.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).hasSize(1);
        assertThat(((Map<?, ?>) r.getBody().get(0)).get("id")).isEqualTo("xtb:IKE");
    }

    @Test
    @SuppressWarnings("unchecked")
    void portfolioJsonForImportedFixture() {
        importFixture();
        ResponseEntity<Map> r = rest.getForEntity("/api/portfolio?account=all", Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = r.getBody();
        assertThat(body.get("empty")).isEqualTo(false);
        Map<String, Object> summary = (Map<String, Object>) body.get("summary");
        assertThat(num(summary.get("value"))).isEqualByComparingTo("1020"); // 2*60 + (1000-100)
        assertThat(num(summary.get("contributions"))).isEqualByComparingTo("1000");
        assertThat(num(summary.get("gain"))).isEqualByComparingTo("20");
        assertThat(summary.get("pricesAsOf")).isEqualTo("2026-10-30");
        List<Map<String, Object>> positions = (List<Map<String, Object>>) body.get("positions");
        assertThat(positions).extracting(p -> p.get("key")).containsExactly("cash", "AAA.DE"); // 900 cash, 120 shares
        assertThat(positions.get(1).get("kind")).isEqualTo("INSTRUMENT");
        assertThat(positions.get(0).get("gainPct")).isNull();
        List<Map<String, Object>> series = (List<Map<String, Object>>) body.get("series");
        assertThat(series.get(0).get("day")).isEqualTo("2026-03-02");
        assertThat(series.get(series.size() - 1).get("day")).isEqualTo("2026-11-02");
        assertThat((List<String>) body.get("warnings")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void bondsAppearAsEdoAndFilterWorks() {
        importFixture();
        ResponseEntity<Map> created = rest.postForEntity("/api/bonds", Map.of("purchaseDate", "2026-10-02",
                "quantity", 10, "firstYearRate", 5.6, "margin", 2.0), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map<String, Object> all = rest.getForEntity("/api/portfolio?account=all", Map.class).getBody();
        assertThat(num(((Map<String, Object>) all.get("summary")).get("contributions"))).isEqualByComparingTo("2000");
        Map<String, Object> bonds = rest.getForEntity("/api/portfolio?account=bonds", Map.class).getBody();
        List<Map<String, Object>> positions = (List<Map<String, Object>>) bonds.get("positions");
        assertThat(positions).extracting(p -> p.get("key")).containsExactly("edo");
        assertThat(num(positions.get(0).get("value"))).isEqualByComparingTo("1004.80");

        Map<String, Object> detail = rest.getForEntity("/api/portfolio/positions/edo", Map.class).getBody();
        List<Map<String, Object>> purchases = (List<Map<String, Object>>) detail.get("purchases");
        assertThat(purchases.get(0).get("label")).isEqualTo("EDO1036");
    }

    @Test
    @SuppressWarnings("unchecked")
    void positionDetailAndNotFoundCases() {
        importFixture();
        ResponseEntity<Map> d = rest.getForEntity("/api/portfolio/positions/AAA.DE", Map.class);
        assertThat(d.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((List<?>) d.getBody().get("purchases"))).hasSize(1);
        assertThat(rest.getForEntity("/api/portfolio/positions/cash", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.getForEntity("/api/portfolio/positions/ZZZ", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.getForEntity("/api/portfolio?account=nieznane", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.getForEntity("/api/portfolio/positions/AAA.DE?account=nieznane", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyDatabaseGivesEmptyView() {
        ResponseEntity<Map> r = rest.getForEntity("/api/portfolio", Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("empty")).isEqualTo(true);
        assertThat((List<?>) r.getBody().get("positions")).isEmpty();
    }
}
