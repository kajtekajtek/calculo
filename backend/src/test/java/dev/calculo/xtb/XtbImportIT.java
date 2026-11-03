package dev.calculo.xtb;

import static org.assertj.core.api.Assertions.assertThat;

import dev.calculo.PostgresTestBase;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
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
class XtbImportIT extends PostgresTestBase {
    private static final double T1 = XtbFixture.serial(Instant.parse("2026-03-01T10:00:00Z"));
    private static final double T2 = XtbFixture.serial(Instant.parse("2026-04-02T10:00:00Z"));
    private static final double T3 = XtbFixture.serial(Instant.parse("2026-05-02T10:00:00Z"));

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        dev.calculo.TestAuth.login(rest);
        jdbc.update("delete from operation");
        jdbc.update("delete from position_snapshot");
        jdbc.update("delete from instrument");
        jdbc.update("delete from account");
    }

    private XtbFixture base() {
        return XtbFixture.account("1234567")
                .cash("Deposit", "", "", "", T1, 1000.0, 1L, "", "IKE", null)
                .cash("Stock purchase", "Alpha ETF", "AAA.DE", "ETF", T2, -100.0, 2L, "OPEN BUY 2 @ 50.00", "IKE", 9L)
                .openAggregate("IKE", "Alpha ETF", "AAA.DE", "ETF", 2.0, 110.0);
    }

    private ResponseEntity<Map> post(String path, String filename, byte[] bytes) {
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(new ByteArrayResource(bytes) {
            @Override public String getFilename() { return filename; }
        }, fileHeaders));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return rest.postForEntity(path, new HttpEntity<>(body, headers), Map.class);
    }

    private int ops() {
        return jdbc.queryForObject("select count(*) from operation", Integer.class);
    }

    @Test
    void previewWritesNothing() {
        ResponseEntity<Map> r = post("/api/imports/xtb/preview", "a.xlsx", base().build());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("newOperations", 2).containsEntry("duplicates", 0)
                .containsEntry("canSave", true);
        assertThat(ops()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from account", Integer.class)).isZero();
    }

    @Test
    void commitStoresAndSecondCommitIsAllDuplicates() {
        byte[] file = base().build();
        assertThat(post("/api/imports/xtb/commit", "a.xlsx", file).getBody()).containsEntry("newOperations", 2);
        assertThat(ops()).isEqualTo(2);
        BigDecimal sum = jdbc.queryForObject("select sum(amount_pln) from operation", BigDecimal.class);

        ResponseEntity<Map> again = post("/api/imports/xtb/commit", "a.xlsx", file);
        assertThat(again.getBody()).containsEntry("newOperations", 0).containsEntry("duplicates", 2);
        assertThat(ops()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select sum(amount_pln) from operation", BigDecimal.class))
                .isEqualByComparingTo(sum);
    }

    @Test
    void overlappingFileAddsOnlyNewOperations() {
        post("/api/imports/xtb/commit", "a.xlsx", base().build());
        byte[] later = base()
                .cash("Dividend", "Alpha ETF", "AAA.DE", "ETF", T3, 5.0, 3L, "AAA.DE dividend", "IKE", null).build();
        ResponseEntity<Map> r = post("/api/imports/xtb/commit", "b.xlsx", later);
        assertThat(r.getBody()).containsEntry("newOperations", 1).containsEntry("duplicates", 2);
        assertThat(ops()).isEqualTo(3);
    }

    @Test
    void problemsBlockSavingAndStoreNothing() {
        byte[] file = base().closedRow("Alpha ETF", "AAA.DE", "ETF", 1.0).build();
        ResponseEntity<Map> preview = post("/api/imports/xtb/preview", "a.xlsx", file);
        assertThat(preview.getBody()).containsEntry("canSave", false);
        assertThat((java.util.List<?>) preview.getBody().get("problems")).isNotEmpty();

        ResponseEntity<Map> commit = post("/api/imports/xtb/commit", "a.xlsx", file);
        assertThat(commit.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ops()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from account", Integer.class)).isZero();
    }

    @Test
    void nonXtbFileGivesPolishBadRequest() {
        ResponseEntity<Map> r = post("/api/imports/xtb/preview", "a.pdf", "%PDF-1.4 not a zip".getBytes());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) r.getBody().get("message")).isNotBlank().contains("Plik");
    }

    @Test
    void volumeMismatchWarnsButCanSave() {
        byte[] file = base().openAggregate("IKE", "Beta", "BBB.PL", "STC", 3.0, 30.0).build();
        ResponseEntity<Map> r = post("/api/imports/xtb/preview", "a.xlsx", file);
        assertThat(r.getBody()).containsEntry("canSave", true);
        assertThat((java.util.List<?>) r.getBody().get("warnings")).hasSize(1);
        assertThat(r.getBody().get("warnings").toString()).contains("BBB.PL").doesNotContain("AAA.DE");

        byte[] off = XtbFixture.account("1234567")
                .cash("Stock purchase", "Alpha ETF", "AAA.DE", "ETF", T2, -100.0, 2L, "OPEN BUY 2 @ 50.00", "IKE", 9L)
                .openAggregate("IKE", "Alpha ETF", "AAA.DE", "ETF", 2.5, 110.0).build();
        ResponseEntity<Map> r2 = post("/api/imports/xtb/preview", "a.xlsx", off);
        assertThat(r2.getBody().get("warnings").toString()).contains("AAA.DE");
        assertThat(r2.getBody()).containsEntry("canSave", true);
    }

    @Test
    void accountNamesAndInstruments() {
        post("/api/imports/xtb/commit", "a.xlsx", base().build());
        byte[] other = XtbFixture.account("7654321")
                .cash("Deposit", "", "", "", T1, 10.0, 1L, "", "Custom", null).build();
        post("/api/imports/xtb/commit", "b.xlsx", other);
        assertThat(jdbc.queryForObject("select name from account where id = 'xtb:IKE'", String.class))
                .isEqualTo("IKE XTB");
        assertThat(jdbc.queryForObject("select name from account where id = 'xtb:Custom'", String.class))
                .isEqualTo("Custom");
        assertThat(jdbc.queryForMap("select name, category from instrument where ticker = 'AAA.DE'"))
                .containsEntry("name", "Alpha ETF").containsEntry("category", "ETF");

        jdbc.update("update instrument set price_symbol = 'AAA.X' where ticker = 'AAA.DE'");
        byte[] later = base().cash("Dividend", "Alpha ETF", "AAA.DE", "ETF", T3, 5.0, 3L, "d", "IKE", null).build();
        post("/api/imports/xtb/commit", "c.xlsx", later);
        assertThat(jdbc.queryForObject("select price_symbol from instrument where ticker = 'AAA.DE'", String.class))
                .isEqualTo("AAA.X");
    }

    @Test
    void newerSnapshotReplacesOlder() {
        post("/api/imports/xtb/commit", "a.xlsx", base().reportedAt(Instant.parse("2026-11-01T10:00:00Z")).build());
        byte[] newer = XtbFixture.account("1234567")
                .cash("Stock purchase", "Alpha ETF", "AAA.DE", "ETF", T2, -100.0, 2L, "OPEN BUY 2 @ 50.00", "IKE", 9L)
                .openAggregate("IKE", "Alpha ETF", "AAA.DE", "ETF", 2.0, 150.0)
                .reportedAt(Instant.parse("2026-11-02T10:00:00Z")).build();
        post("/api/imports/xtb/commit", "b.xlsx", newer);
        assertThat(jdbc.queryForObject("select value_pln from position_snapshot where ticker = 'AAA.DE'",
                BigDecimal.class)).isEqualByComparingTo("150");

        byte[] older = XtbFixture.account("1234567")
                .cash("Stock purchase", "Alpha ETF", "AAA.DE", "ETF", T2, -100.0, 2L, "OPEN BUY 2 @ 50.00", "IKE", 9L)
                .openAggregate("IKE", "Alpha ETF", "AAA.DE", "ETF", 2.0, 99.0)
                .reportedAt(Instant.parse("2026-10-01T10:00:00Z")).build();
        post("/api/imports/xtb/commit", "c.xlsx", older);
        assertThat(jdbc.queryForObject("select value_pln from position_snapshot where ticker = 'AAA.DE'",
                BigDecimal.class)).isEqualByComparingTo("150");
    }
}
