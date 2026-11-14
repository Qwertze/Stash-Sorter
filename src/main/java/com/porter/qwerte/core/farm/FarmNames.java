package com.porter.qwerte.core.farm;

/**
 * Farm name rule (contract P7, E1): trimmed, 1..32 characters, no section sign, no control characters, fewer than 3 digit
 * characters in total, and no match of the position pattern. Names stay on the owner's page; the digit rule is what keeps
 * "Farm 1234" from holding a coordinate.
 */
public final class FarmNames {
    public static final int MAX_LENGTH = 32;

    private FarmNames() {
    }

    /** The trimmed name when valid, else null (the caller answers {@code bad_field} with field name). */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String n = raw.strip();
        if (n.isEmpty() || n.length() > MAX_LENGTH) {
            return null;
        }
        int digits = 0;
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c == '§' || Character.isISOControl(c)) {
                return null;
            }
            if (Character.isDigit(c) && ++digits >= 3) {
                return null;
            }
        }
        return PositionGuard.looksLikePosition(n) ? null : n;
    }
}
