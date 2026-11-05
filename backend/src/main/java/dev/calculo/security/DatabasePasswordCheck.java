package dev.calculo.security;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Refuses to start with the database password still set to the placeholder from .env.example. Runs before any
 * bean is created, so the application never connects to a database guarded by a publicly known password.
 */
public class DatabasePasswordCheck implements EnvironmentPostProcessor {
    private static final String PLACEHOLDER = "change-me";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (PLACEHOLDER.equals(environment.getProperty("spring.datasource.password"))) {
            throw new IllegalStateException("POSTGRES_PASSWORD is still the placeholder '" + PLACEHOLDER
                    + "' from .env.example. Set the POSTGRES_PASSWORD environment variable to your own password.");
        }
    }
}
