package dev.calculo.prices;

import java.util.Locale;
import java.util.Map;

/** Turns a ledger ticker into the symbol the price provider knows. */
public class SymbolMapper {
    private final Map<String, String> suffixes;

    public SymbolMapper(Map<String, String> suffixMap) {
        Map<String, String> normalized = new java.util.HashMap<>();
        suffixMap.forEach((k, v) -> normalized.put(k.toUpperCase(Locale.ROOT), v));
        this.suffixes = normalized;
    }

    /** An explicit price symbol wins; otherwise a mapped ticker suffix is replaced (AAA.PL to AAA.WA) or, when the replacement is empty, dropped (AAA.US to AAA). */
    public String symbol(String ticker, String priceSymbol) {
        if (priceSymbol != null && !priceSymbol.isBlank()) return priceSymbol.trim();
        int dot = ticker.lastIndexOf('.');
        if (dot < 0) return ticker;
        String replacement = suffixes.get(ticker.substring(dot + 1).toUpperCase(Locale.ROOT));
        if (replacement == null) return ticker;
        return replacement.isEmpty() ? ticker.substring(0, dot) : ticker.substring(0, dot + 1) + replacement;
    }
}
