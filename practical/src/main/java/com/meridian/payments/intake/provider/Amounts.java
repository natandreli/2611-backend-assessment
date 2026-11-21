package com.meridian.payments.intake.provider;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.regex.Pattern;

/**
 * Converts provider amounts to the canonical format: a decimal string in the currency's major
 * unit, with the number of decimals defined by ISO 4217 (MXN and COP: 2, CLP: 0).
 * {@link BigDecimal} is used everywhere, never floating point.
 */
public final class Amounts {

    private static final Pattern DECIMAL = Pattern.compile("^[0-9]+(\\.[0-9]+)?$");

    private Amounts() {
    }

    /** Quetzal: integer in the minor unit, for example 125000 MXN centavos becomes "1250.00". */
    public static String fromMinorUnits(long minorUnits, String currencyCode) {
        if (minorUnits < 0) {
            throw new ParkException("negative amount");
        }
        return BigDecimal.valueOf(minorUnits, fractionDigits(currencyCode)).toPlainString();
    }

    /** Andina: decimal string in the major unit, for example "185000.00" COP. */
    public static String fromMajorUnits(String value, String currencyCode) {
        if (value == null || !DECIMAL.matcher(value).matches()) {
            throw new ParkException("amount is not a decimal number");
        }
        try {
            return new BigDecimal(value).setScale(fractionDigits(currencyCode)).toPlainString();
        } catch (ArithmeticException e) {
            throw new ParkException("amount has more decimals than its currency allows");
        }
    }

    private static int fractionDigits(String currencyCode) {
        try {
            int digits = Currency.getInstance(currencyCode).getDefaultFractionDigits();
            if (digits < 0) {
                throw new ParkException("currency has no decimal rule: " + currencyCode);
            }
            return digits;
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ParkException("unknown currency");
        }
    }
}
