package dev.calculo.prices;

import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(PricesProperties.class)
public class PricesConfig {

    @Bean
    SymbolMapper symbolMapper(PricesProperties props) {
        return new SymbolMapper(props.suffixes());
    }

    @Bean
    @Profile("!demo")
    PriceProvider eodhdPriceProvider(PricesProperties props, JdbcClient jdbc, ObjectProvider<Clock> clock) {
        Clock c = clock.getIfAvailable(Clock::systemUTC);
        return new EodhdPriceProvider(props.eodhdBaseUrl(), props.eodhdApiKey(), props.exchangeCurrencyMap(),
                new JdbcCallBudget(jdbc, props.dailyCallLimit(), c), c);
    }

    @Bean
    @Profile("!demo")
    FxProvider nbpFxProvider(PricesProperties props) {
        return new NbpFxProvider(props.nbpBaseUrl());
    }
}
