package dev.calculo.prices;

/** A provider could not deliver the requested prices or rates. */
public class PriceUnavailableException extends Exception {
    public PriceUnavailableException(String message) {
        super(message);
    }

    public PriceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
