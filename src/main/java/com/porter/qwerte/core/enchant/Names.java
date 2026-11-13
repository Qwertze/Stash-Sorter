package com.porter.qwerte.core.enchant;

/** Plain-text names for refusal messages. */
public final class Names {
    private Names() {
    }

    private static final String[] ROMAN = {"0", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

    public static String roman(int level) {
        return level >= 0 && level < ROMAN.length ? ROMAN[level] : Integer.toString(level);
    }

    /** "fire_aspect" becomes "Fire Aspect". */
    public static String display(String id) {
        StringBuilder b = new StringBuilder();
        for (String part : id.split("_")) {
            if (part.isEmpty()) {
                continue;
            }
            if (b.length() > 0) {
                b.append(' ');
            }
            b.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return b.toString();
    }

    /** "Sharpness V". */
    public static String leveled(String id, int level) {
        return display(id) + " " + roman(level);
    }
}
