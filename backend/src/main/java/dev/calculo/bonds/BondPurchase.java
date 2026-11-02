package dev.calculo.bonds;

import java.time.LocalDate;

public record BondPurchase(long id, String seriesCode, LocalDate purchaseDate, int quantity) {}
