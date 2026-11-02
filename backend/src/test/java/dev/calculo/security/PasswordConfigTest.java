package dev.calculo.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class PasswordConfigTest {
    @Configuration
    @EnableConfigurationProperties(AuthProperties.class)
    @Import(PasswordChecker.class)
    static class Config {
        @Bean Clock clock() { return Clock.systemUTC(); }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void blankPasswordFailsStartupNamingTheVariable() {
        for (String value : new String[] {"calculo.auth.password=", "calculo.auth.password=   "}) {
            runner.withPropertyValues(value).run(ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(rootMessage(ctx.getStartupFailure())).contains("CALCULO_PASSWORD");
            });
        }
    }

    @Test
    void missingPasswordFailsStartupNamingTheVariable() {
        runner.run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootMessage(ctx.getStartupFailure())).contains("CALCULO_PASSWORD");
        });
    }

    @Test
    void configuredPasswordIsComparedByValue() {
        runner.withPropertyValues("calculo.auth.password=zażółć").run(ctx -> {
            PasswordChecker checker = ctx.getBean(PasswordChecker.class);
            assertThat(checker.matches("zażółć")).isTrue();
            assertThat(checker.matches("zazolc")).isFalse();
            assertThat(checker.matches(null)).isFalse();
        });
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage();
    }
}
