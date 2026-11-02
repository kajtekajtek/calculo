package dev.calculo.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.stereotype.Component;

/** Compares a candidate with the configured password in constant time (over fixed-length digests). */
@Component
public class PasswordChecker {
    private final byte[] expected;

    public PasswordChecker(AuthProperties properties) {
        this.expected = digest(properties.password());
    }

    public boolean matches(String candidate) {
        return candidate != null && MessageDigest.isEqual(expected, digest(candidate));
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
