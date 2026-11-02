package dev.calculo;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/** Shared helper that makes a TestRestTemplate behave like a logged-in browser (session cookie plus CSRF token). */
public final class TestAuth {
    public static final String PASSWORD = "test-password";

    private TestAuth() {}

    /** Logs in and (re)installs an interceptor that sends the session cookie and the CSRF header. */
    public static void login(TestRestTemplate rest) {
        rest.getRestTemplate().getInterceptors().removeIf(i -> i instanceof SessionInterceptor);

        ResponseEntity<Void> me = rest.getForEntity("/api/me", Void.class);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        String xsrf = cookie(me.getHeaders(), "XSRF-TOKEN");

        SessionInterceptor anonymous = new SessionInterceptor(null, xsrf);
        rest.getRestTemplate().getInterceptors().add(anonymous);
        ResponseEntity<Void> login = rest.postForEntity("/api/login", Map.of("password", PASSWORD), Void.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String session = cookie(login.getHeaders(), "JSESSIONID");

        rest.getRestTemplate().getInterceptors().remove(anonymous);
        rest.getRestTemplate().getInterceptors().add(new SessionInterceptor(session, xsrf));
    }

    private static String cookie(HttpHeaders headers, String name) {
        List<String> all = headers.get(HttpHeaders.SET_COOKIE);
        assertThat(all).as("Set-Cookie headers").isNotNull();
        return all.stream().filter(c -> c.startsWith(name + "=")).findFirst()
                .map(c -> c.substring(name.length() + 1, c.indexOf(';') < 0 ? c.length() : c.indexOf(';')))
                .orElseThrow(() -> new AssertionError("no " + name + " cookie in " + all));
    }

    private record SessionInterceptor(String session, String xsrf) implements ClientHttpRequestInterceptor {
        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
                throws IOException {
            if (!request.getHeaders().containsKey(HttpHeaders.COOKIE)) {
                String cookies = "XSRF-TOKEN=" + xsrf + (session == null ? "" : "; JSESSIONID=" + session);
                request.getHeaders().set(HttpHeaders.COOKIE, cookies);
            }
            if (request.getMethod() != HttpMethod.GET) request.getHeaders().set("X-XSRF-TOKEN", xsrf);
            return execution.execute(request, body);
        }
    }
}
