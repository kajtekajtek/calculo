package dev.calculo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class SchemaIT extends PostgresTestBase {
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void flywayMigratesAndOperationTableIsEmpty() {
        Integer count = jdbc.queryForObject("select count(*) from operation", Integer.class);
        assertThat(count).isZero();
    }
}
