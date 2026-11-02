package dev.calculo.prices;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Loads the real application.yaml, so this also covers the CALCULO_SUFFIX_MAP placeholder used by compose. */
class PricesPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PricesConfig.class);

    @Test
    void defaultMapsPolishTickersOnly() {
        runner.run(context -> {
            SymbolMapper mapper = context.getBean(SymbolMapper.class);
            assertThat(mapper.symbol("AAA.PL", null)).isEqualTo("AAA.WA");
            assertThat(mapper.symbol("XXX.US", null)).isEqualTo("XXX.US");
        });
    }

    @Test
    void environmentVariableReplacesTheMapAndAllowsAnEmptyReplacement() {
        runner.withSystemProperties("CALCULO_SUFFIX_MAP=PL=WA, US=").run(context -> {
            SymbolMapper mapper = context.getBean(SymbolMapper.class);
            assertThat(mapper.symbol("AAA.PL", null)).isEqualTo("AAA.WA");
            assertThat(mapper.symbol("XXX.US", null)).isEqualTo("XXX");
        });
    }

    @Test
    void blankValueFallsBackToTheDefault() {
        runner.withSystemProperties("CALCULO_SUFFIX_MAP=").run(context ->
                assertThat(context.getBean(SymbolMapper.class).symbol("AAA.PL", null)).isEqualTo("AAA.WA"));
    }

    @Test
    void malformedValueFailsStartup() {
        runner.withSystemProperties("CALCULO_SUFFIX_MAP=PL").run(context -> assertThat(context).hasFailed());
    }
}
