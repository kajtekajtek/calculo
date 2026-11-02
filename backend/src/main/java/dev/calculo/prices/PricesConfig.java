package dev.calculo.prices;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(PricesProperties.class)
public class PricesConfig {

    @Bean
    SymbolMapper symbolMapper(PricesProperties props) {
        return new SymbolMapper(props.suffixMap());
    }

    @Bean
    @Profile("!demo")
    PriceProvider yahooPriceProvider(PricesProperties props) {
        return new YahooPriceProvider(props.yahooBaseUrl());
    }

    @Bean
    @Profile("!demo")
    FxProvider nbpFxProvider(PricesProperties props) {
        return new NbpFxProvider(props.nbpBaseUrl());
    }
}
