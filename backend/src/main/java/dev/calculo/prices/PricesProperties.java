package dev.calculo.prices;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("calculo.prices")
public record PricesProperties(
        @DefaultValue("true") boolean syncOnStartup,
        @DefaultValue("true") boolean syncOnImport,
        @DefaultValue("true") boolean scheduleEnabled,
        /** Ticker suffix to provider suffix, as "PL=WA,US="; an empty replacement drops the suffix. */
        @DefaultValue(PricesProperties.DEFAULT_SUFFIX_MAP) String suffixMap,
        @DefaultValue("https://query1.finance.yahoo.com") String yahooBaseUrl,
        @DefaultValue("https://api.nbp.pl") String nbpBaseUrl) {
    static final String DEFAULT_SUFFIX_MAP = "PL=WA";

    /** The parsed {@link #suffixMap}; blank means the default, a malformed entry fails startup. */
    public Map<String, String> suffixes() {
        String spec = suffixMap == null || suffixMap.isBlank() ? DEFAULT_SUFFIX_MAP : suffixMap;
        Map<String, String> suffixes = new HashMap<>();
        for (String entry : spec.split(",")) {
            int equals = entry.indexOf('=');
            if (equals <= 0 || entry.substring(0, equals).isBlank()) {
                throw new IllegalArgumentException("calculo.prices.suffix-map: expected SUFFIX=REPLACEMENT, got \"" + entry + "\"");
            }
            suffixes.put(entry.substring(0, equals).trim(), entry.substring(equals + 1).trim());
        }
        return suffixes;
    }
}
