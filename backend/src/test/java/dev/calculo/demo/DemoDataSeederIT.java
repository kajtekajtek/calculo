package dev.calculo.demo;

import static org.assertj.core.api.Assertions.assertThat;

import dev.calculo.PostgresTestBase;
import dev.calculo.TestAuth;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
class DemoDataSeederIT extends PostgresTestBase {
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired DemoDataSeeder seeder;

    @Test
    @SuppressWarnings("unchecked")
    void demoProfileSeedsAPortfolioWithHistory() {
        TestAuth.login(rest);
        Map<String, Object> body = rest.getForEntity("/api/portfolio?account=all", Map.class).getBody();
        assertThat(body.get("empty")).isEqualTo(false);
        List<Map<String, Object>> positions = (List<Map<String, Object>>) body.get("positions");
        assertThat(positions).extracting(p -> p.get("key")).contains("AAA.DE", "BBB.PL", "CCC.PL", "edo", "cash");
        assertThat((List<?>) body.get("series")).hasSizeGreaterThan(30);
        assertThat((List<String>) body.get("warnings")).isEmpty();
    }

    @Test
    void runningAgainDoesNotDuplicateData() throws Exception {
        int operations = count("operation");
        int purchases = count("bond_purchase");
        int accounts = count("account");
        assertThat(operations).isPositive();
        ApplicationArguments none = new DefaultApplicationArguments();
        seeder.run(none);
        assertThat(count("operation")).isEqualTo(operations);
        assertThat(count("bond_purchase")).isEqualTo(purchases);
        assertThat(count("account")).isEqualTo(accounts);
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }
}
