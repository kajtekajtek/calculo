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
        /** Ticker suffix to provider suffix, as "PL=WAR,US="; an empty replacement drops the suffix. */
        @DefaultValue(PricesProperties.DEFAULT_SUFFIX_MAP) String suffixMap,
        @DefaultValue("https://eodhd.com") String eodhdBaseUrl,
        /** Blank means no prices are fetched, and the dashboard says why. */
        @DefaultValue("") String eodhdApiKey,
        /** Provider exchange code to the currency its closes are in, as "WAR=PLN,XETRA=EUR". */
        @DefaultValue(PricesProperties.DEFAULT_EXCHANGE_CURRENCIES) String exchangeCurrencies,
        /** Requests to the price provider per UTC day; EODHD's free plan allows 20. */
        @DefaultValue("20") int dailyCallLimit,
        @DefaultValue("https://api.nbp.pl") String nbpBaseUrl) {
    static final String DEFAULT_SUFFIX_MAP = "PL=WAR,DE=XETRA";
    static final String DEFAULT_EXCHANGE_CURRENCIES = "WAR=PLN,XETRA=EUR,US=USD";

    public PricesProperties {
        if (dailyCallLimit < 1) {
            throw new IllegalArgumentException("calculo.prices.daily-call-limit must be at least 1, got " + dailyCallLimit);
        }
        // Parsed here so a malformed value fails startup rather than the first sync.
        parse("calculo.prices.suffix-map", suffixMap, DEFAULT_SUFFIX_MAP, true);
        parse("calculo.prices.exchange-currencies", exchangeCurrencies, DEFAULT_EXCHANGE_CURRENCIES, false);
    }

    /** The parsed {@link #suffixMap}; blank means the default. */
    public Map<String, String> suffixes() {
        return parse("calculo.prices.suffix-map", suffixMap, DEFAULT_SUFFIX_MAP, true);
    }

    /** The parsed {@link #exchangeCurrencies}; blank means the default. */
    public Map<String, String> exchangeCurrencyMap() {
        return parse("calculo.prices.exchange-currencies", exchangeCurrencies, DEFAULT_EXCHANGE_CURRENCIES, false);
    }

    private static Map<String, String> parse(String name, String value, String fallback, boolean emptyValueAllowed) {
        String spec = value == null || value.isBlank() ? fallback : value;
        Map<String, String> map = new HashMap<>();
        for (String entry : spec.split(",")) {
            int equals = entry.indexOf('=');
            String key = equals <= 0 ? "" : entry.substring(0, equals).trim();
            String mapped = equals <= 0 ? "" : entry.substring(equals + 1).trim();
            if (key.isEmpty() || (!emptyValueAllowed && mapped.isEmpty())) {
                throw new IllegalArgumentException(name + ": expected KEY=VALUE, got \"" + entry + "\"");
            }
            map.put(key, mapped);
        }
        return map;
    }
}
