package dev.calculo;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@TestPropertySource(properties = {"calculo.prices.sync-on-startup=false", "calculo.prices.schedule-enabled=false",
        "calculo.prices.sync-on-import=false", "calculo.auth.password=" + TestAuth.PASSWORD})
@Testcontainers
// A fresh context per class: the container is per class, so a cached context would point at a stopped database.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class PostgresTestBase {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");
}
