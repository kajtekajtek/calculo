package dev.calculo.bonds;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Set;

/** Value of one bond; {@code estimated} is true when some CPI month was missing and margin-only was used. */
public record BondValue(BigDecimal value, boolean estimated, Set<YearMonth> missingCpi) {}
