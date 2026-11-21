package com.meridian.payments.intake.provider.andina;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Since Andina API 2.3 (2026-11-28), {@code transaccion.referencia} arrives exactly as typed by
 * the payer. Normalization (assumption 3):
 * <ol>
 *   <li>trim spaces and convert to upper case;</li>
 *   <li>prefix a value of exactly 9 digits with {@code POL-CO-};</li>
 *   <li>accept only {@code POL-CO-} followed by 9 digits.</li>
 * </ol>
 * Anything else is never guessed: a wrong guess could attach a payment to someone else's policy.
 */
public final class PolicyReferenceNormalizer {

    private static final Pattern NINE_DIGITS = Pattern.compile("^[0-9]{9}$");
    private static final Pattern POLICY_NUMBER = Pattern.compile("^POL-CO-[0-9]{9}$");

    private PolicyReferenceNormalizer() {
    }

    public static Optional<String> normalize(String reference) {
        if (reference == null) {
            return Optional.empty();
        }
        String value = reference.strip().toUpperCase(Locale.ROOT);
        if (NINE_DIGITS.matcher(value).matches()) {
            value = "POL-CO-" + value;
        }
        return POLICY_NUMBER.matcher(value).matches() ? Optional.of(value) : Optional.empty();
    }
}
