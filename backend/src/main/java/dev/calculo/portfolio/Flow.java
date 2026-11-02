package dev.calculo.portfolio;

import java.time.LocalDate;

/** A deposit into the portfolio; positive amount means money paid in. */
public record Flow(LocalDate date, double amount) {}
