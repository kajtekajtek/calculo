package dev.calculo.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/** Starts a bare application, so this also covers the registration in spring.factories and the real application.yaml. */
class DatabasePasswordCheckTest {
    @Configuration
    static class Empty {}

    private static ConfigurableApplicationContext start(String... args) {
        return new SpringApplicationBuilder(Empty.class).web(WebApplicationType.NONE).logStartupInfo(false).run(args);
    }

    @Test
    void placeholderDatabasePasswordFailsStartupNamingTheVariable() {
        assertThatThrownBy(() -> start("--POSTGRES_PASSWORD=change-me"))
                .hasMessageContaining("POSTGRES_PASSWORD")
                .hasMessageContaining("change-me");
    }

    @Test
    void ownDatabasePasswordStarts() {
        try (ConfigurableApplicationContext ctx = start("--POSTGRES_PASSWORD=zażółć-gęślą")) {
            assertThat(ctx.isActive()).isTrue();
        }
    }
}
