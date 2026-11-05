package dev.calculo.prices;

/** The provider cannot work for any symbol until its configuration is fixed (missing or rejected key). */
public class ProviderNotConfiguredException extends PriceUnavailableException {
    public ProviderNotConfiguredException(String message) {
        super(message);
    }
}
