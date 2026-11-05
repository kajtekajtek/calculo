package dev.calculo.prices;

/** A provider's daily request allowance. */
@FunctionalInterface
public interface CallBudget {
    /** Takes one request from today's allowance; false when it is spent, and then nothing is taken. */
    boolean tryUse();
}
