package dev.calculo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

/** Checks the cookie attributes that only a real servlet container emits. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionCookieIT extends PostgresTestBase {
    @Autowired TestRestTemplate rest;

    @Test
    void sessionCookieIsHttpOnlyLaxThirtyDaysAndNotSecure() {
        ResponseEntity<Void> r = rest.postForEntity("/api/login", Map.of("password", TestAuth.PASSWORD), Void.class);
        String cookie = r.getHeaders().get(HttpHeaders.SET_COOKIE).stream()
                .filter(c -> c.startsWith("JSESSIONID=")).findFirst().orElseThrow();
        assertThat(cookie).contains("HttpOnly").contains("SameSite=Lax").contains("Max-Age=2592000")
                .doesNotContain("Secure");
    }
}
