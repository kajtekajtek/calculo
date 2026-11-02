package dev.calculo.prices;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("calculo.prices")
public record PricesProperties(
        @DefaultValue("true") boolean syncOnStartup,
        @DefaultValue("true") boolean syncOnImport,
        @DefaultValue("true") boolean scheduleEnabled,
        Map<String, String> suffixMap,
        @DefaultValue("https://query1.finance.yahoo.com") String yahooBaseUrl,
        @DefaultValue("https://api.nbp.pl") String nbpBaseUrl) {
    public PricesProperties {
        if (suffixMap == null || suffixMap.isEmpty()) suffixMap = Map.of("PL", "WA");
    }
}
