package com.porter.qwertestash.manager;

import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.Locale;

/**
 * Limits for a numeric setting nobody wrote a spec for (the proxy's and other plugins' settings): the range of the Java
 * type (so a short or byte never wraps), plus conservative limits by name and size. A port is 1-65535 and the compression
 * level -1 to 9. Anything about seconds, intervals, rates, ticks, delays, timeouts or a maximum must be at least 1 (above 0
 * for a decimal), and a size, cache, buffer, distance or limit at least 0. A whole number may not exceed 1,000,000 unless
 * it already does (then 10 times what it is now), a decimal must be finite and at most 1e6 in size.
 */
final class GenericRules {
    private GenericRules() {
    }

    static final double CAP = 1_000_000;

    /** @param owner the object the field belongs to, for the value it has now (null when not known) */
    static SettingsSpec.Spec of(Field f, @Nullable Object owner) {
        SettingsSpec.Spec spec = new SettingsSpec.Spec("", "", "");
        Class<?> t = f.getType();
        String name = f.getName();
        String lower = name.toLowerCase(Locale.ROOT);
        boolean decimal = t == double.class || t == Double.class || t == float.class || t == Float.class;
        boolean whole = t == int.class || t == Integer.class || t == long.class || t == Long.class;

        if (t == short.class || t == Short.class) spec.range(Short.MIN_VALUE, Short.MAX_VALUE);
        else if (t == byte.class || t == Byte.class) spec.range(Byte.MIN_VALUE, Byte.MAX_VALUE);

        if (name.equals("compressionLevel")) {
            spec.range(-1, 9);
        }
        else if (name.equals("port") || name.endsWith("Port")) {
            spec.range(1, 65535);
        }
        else {
            if (lower.contains("seconds") || lower.contains("interval") || lower.contains("rate") || lower.contains("tick")
                || lower.contains("delay") || lower.contains("timeout") || lower.contains("max")) {
                spec.min = decimal ? 1e-9 : 1.0;
            }
            else if (lower.contains("size") || lower.contains("cache") || lower.contains("buffer") || lower.contains("distance") || lower.contains("limit")) {
                spec.min = 0.0;
            }
            if (spec.max == null) {
                if (decimal) {
                    spec.max = CAP;
                    if (spec.min == null) spec.min = -CAP;
                }
                else if (whole) {
                    double max = CAP;
                    Number now = current(f, owner);
                    if (now != null && Math.abs(now.doubleValue()) > CAP) max = Math.abs(now.doubleValue()) * 10;
                    spec.max = max;
                }
            }
        }
        return spec;
    }

    private static @Nullable Number current(Field f, @Nullable Object owner) {
        if (owner == null) return null;
        try {
            return f.get(owner) instanceof Number n ? n : null;
        } catch (IllegalAccessException | RuntimeException e) {
            return null;
        }
    }
}
