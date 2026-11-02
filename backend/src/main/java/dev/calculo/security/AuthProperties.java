package dev.calculo.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** The single user's password, taken from the CALCULO_PASSWORD environment variable. */
@ConfigurationProperties("calculo.auth")
public record AuthProperties(String password) {
    private static final String PLACEHOLDER = "change-me";

    public AuthProperties {
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "CALCULO_PASSWORD is not set. Set the CALCULO_PASSWORD environment variable to the login password.");
        }
        if (PLACEHOLDER.equals(password)) {
            throw new IllegalStateException("CALCULO_PASSWORD is still the placeholder '" + PLACEHOLDER
                    + "' from .env.example. Set the CALCULO_PASSWORD environment variable to your own password.");
        }
    }

    @Override
    public String toString() {
        return "AuthProperties[password=****]";
    }
}
