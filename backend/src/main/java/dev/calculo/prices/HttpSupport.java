package dev.calculo.prices;

import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Shared HTTP plumbing for the price providers. */
final class HttpSupport {
    static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** {@code retryAfter} is the raw Retry-After header, null when the caller does not read it or it is absent. */
    record Response(int status, String body, String retryAfter) {
        Response(int status, String body) {
            this(status, body, null);
        }
    }

    private HttpSupport() {}

    static RestClient client(String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) TIMEOUT.toMillis());
        factory.setReadTimeout((int) TIMEOUT.toMillis());
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }
}
