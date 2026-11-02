package dev.calculo.prices;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DailyPrice(LocalDate day, BigDecimal close) {}
