package dev.calculo.bonds;

import java.util.Optional;

/** Looks up EDO series terms (rates) for a series code; empty when they cannot be determined. */
public interface EdoSeriesSource {
    Optional<EdoSeries> fetch(String code);
}
