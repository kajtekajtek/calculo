package dev.calculo.demo;

import static org.assertj.core.api.Assertions.assertThat;

import dev.calculo.PostgresTestBase;
import dev.calculo.TestAuth;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NoDemoByDefaultIT extends PostgresTestBase {
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationContext context;

    @Test
    void withoutTheDemoProfileTheDatabaseStaysEmpty() {
        assertThat(context.getBeansOfType(DemoDataSeeder.class)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from account", Integer.class)).isZero();
        TestAuth.login(rest);
        assertThat(rest.getForEntity("/api/portfolio?account=all", Map.class).getBody().get("empty")).isEqualTo(true);
    }
}
