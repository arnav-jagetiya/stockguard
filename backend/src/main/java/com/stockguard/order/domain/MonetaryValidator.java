package com.stockguard.order.domain;

import com.stockguard.shared.exception.InvalidOperationException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Validates monetary values strictly against PostgreSQL {@code NUMERIC(19,4)} requirements.
 * Enforces:
 * <ul>
 *   <li>Non-null and non-negative</li>
 *   <li>Fractional scale <= 4 without silent rounding</li>
 *   <li>Integer digits <= 15</li>
 *   <li>Fits max value 999999999999999.9999</li>
 * </ul>
 */
public final class MonetaryValidator {

    public static final BigDecimal MAX_NUMERIC_19_4 = new BigDecimal("999999999999999.9999");
    public static final int MAX_SCALE = 4;
    public static final int MAX_INTEGER_DIGITS = 15;

    private MonetaryValidator() {
    }

    public static BigDecimal validate(BigDecimal value, String fieldName) {
        if (value == null) {
            throw new InvalidOperationException(fieldName + " must not be null");
        }
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            throw new InvalidOperationException(fieldName + " must not be negative: " + value);
        }
        if (value.scale() > MAX_SCALE) {
            throw new InvalidOperationException(
                    fieldName + " fractional scale must not exceed " + MAX_SCALE + " (no silent rounding allowed): " + value
            );
        }
        // Validate integer digits count: value before decimal point must have <= 15 digits
        BigDecimal integerPart = value.setScale(0, RoundingMode.DOWN);
        if (integerPart.compareTo(BigDecimal.ZERO) > 0) {
            int integerDigits = integerPart.toBigInteger().toString().length();
            if (integerDigits > MAX_INTEGER_DIGITS) {
                throw new InvalidOperationException(
                        fieldName + " integer digits must not exceed " + MAX_INTEGER_DIGITS + ": " + value
                );
            }
        }
        if (value.compareTo(MAX_NUMERIC_19_4) > 0) {
            throw new InvalidOperationException(
                    fieldName + " exceeds maximum allowable precision (NUMERIC 19,4): " + value
            );
        }
        return value.setScale(MAX_SCALE, RoundingMode.UNNECESSARY);
    }
}
