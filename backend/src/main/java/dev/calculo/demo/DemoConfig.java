package dev.calculo.demo;

import dev.calculo.prices.FxProvider;
import dev.calculo.prices.PriceProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Replaces the EODHD and NBP providers (see PricesConfig) so the demo works offline. */
@Configuration
@Profile("demo")
class DemoConfig {
    @Bean
    PriceProvider demoPriceProvider() {
        return new DemoPriceProvider();
    }

    @Bean
    FxProvider demoFxProvider() {
        return new DemoFxProvider();
    }
}
