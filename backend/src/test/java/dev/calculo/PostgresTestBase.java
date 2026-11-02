package dev.calculo;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@TestPropertySource(properties = {"calculo.prices.sync-on-startup=false", "calculo.prices.schedule-enabled=false",
        "calculo.prices.sync-on-import=false"})
@Testcontainers
public abstract class PostgresTestBase {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");
}
