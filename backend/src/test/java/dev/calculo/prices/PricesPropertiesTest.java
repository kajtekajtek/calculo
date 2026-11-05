package dev.calculo.prices;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Loads the real application.yaml, so this also covers the environment placeholders used by compose. */
class PricesPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withBean(JdbcClient.class, () -> Mockito.mock(JdbcClient.class))
            .withUserConfiguration(PricesConfig.class);

    @Test
    void defaultMapsPolishAndGermanTickersToEodhdExchanges() {
        runner.run(context -> {
            SymbolMapper mapper = context.getBean(SymbolMapper.class);
            assertThat(mapper.symbol("AAA.PL", null)).isEqualTo("AAA.WAR");
            assertThat(mapper.symbol("BBB.DE", null)).isEqualTo("BBB.XETRA");
            assertThat(mapper.symbol("XXX.US", null)).isEqualTo("XXX.US");
        });
    }

    @Test
    void environmentVariableReplacesTheMapAndAllowsAnEmptyReplacement() {
        runner.withSystemProperties("CALCULO_SUFFIX_MAP=PL=WAR, US=").run(context -> {
            SymbolMapper mapper = context.getBean(SymbolMapper.class);
            assertThat(mapper.symbol("AAA.PL", null)).isEqualTo("AAA.WAR");
            assertThat(mapper.symbol("BBB.DE", null)).isEqualTo("BBB.DE");
            assertThat(mapper.symbol("XXX.US", null)).isEqualTo("XXX");
        });
    }

    @Test
    void blankValueFallsBackToTheDefault() {
        runner.withSystemProperties("CALCULO_SUFFIX_MAP=").run(context ->
                assertThat(context.getBean(SymbolMapper.class).symbol("AAA.PL", null)).isEqualTo("AAA.WAR"));
    }

    @Test
    void malformedValueFailsStartup() {
        runner.withSystemProperties("CALCULO_SUFFIX_MAP=PL").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void exchangeCurrenciesDefaultToWarsawXetraAndUs() {
        runner.run(context -> assertThat(context.getBean(PricesProperties.class).exchangeCurrencyMap())
                .containsExactlyInAnyOrderEntriesOf(java.util.Map.of("WAR", "PLN", "XETRA", "EUR", "US", "USD")));
    }

    @Test
    void exchangeCurrenciesCanBeReplaced() {
        runner.withSystemProperties("CALCULO_EXCHANGE_CURRENCIES=WAR=PLN, LSE=GBP").run(context ->
                assertThat(context.getBean(PricesProperties.class).exchangeCurrencyMap())
                        .containsExactlyInAnyOrderEntriesOf(java.util.Map.of("WAR", "PLN", "LSE", "GBP")));
    }

    @Test
    void exchangeWithoutCurrencyFailsStartup() {
        runner.withSystemProperties("CALCULO_EXCHANGE_CURRENCIES=WAR=").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void keyAndLimitComeFromTheEnvironmentAndTheProviderIsEodhd() {
        runner.withSystemProperties("EODHD_API_KEY=abc", "CALCULO_PRICE_CALL_LIMIT=7").run(context -> {
            PricesProperties props = context.getBean(PricesProperties.class);
            assertThat(props.eodhdApiKey()).isEqualTo("abc");
            assertThat(props.dailyCallLimit()).isEqualTo(7);
            assertThat(context.getBean(PriceProvider.class)).isInstanceOf(EodhdPriceProvider.class);
        });
    }

    @Test
    void withoutAKeyTheAppStillStartsWithTwentyCallsADay() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(PricesProperties.class).eodhdApiKey()).isEmpty();
            assertThat(context.getBean(PricesProperties.class).dailyCallLimit()).isEqualTo(20);
        });
    }

    @Test
    void limitBelowOneFailsStartup() {
        runner.withSystemProperties("CALCULO_PRICE_CALL_LIMIT=0").run(context -> assertThat(context).hasFailed());
    }
}
