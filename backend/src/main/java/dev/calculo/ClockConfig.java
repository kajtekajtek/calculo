package dev.calculo;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {
    public static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

    @Bean
    public Clock clock() {
        return Clock.system(WARSAW);
    }
}
