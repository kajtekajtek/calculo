package dev.calculo.prices;

import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Shared HTTP plumbing for the price providers. */
final class HttpSupport {
    static final Duration TIMEOUT = Duration.ofSeconds(10);

    record Response(int status, String body) {}

    private HttpSupport() {}

    static RestClient client(String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) TIMEOUT.toMillis());
        factory.setReadTimeout((int) TIMEOUT.toMillis());
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }
}
