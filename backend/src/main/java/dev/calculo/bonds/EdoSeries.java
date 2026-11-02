package dev.calculo.bonds;

import java.math.BigDecimal;

/** EDO series terms; rates are percent values (5.60 means 5.60%). */
public record EdoSeries(String code, BigDecimal firstYearRate, BigDecimal margin) {}
