package com.porter.qwerte.core.farm;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The coordinate rule's string and key guards (contract P3, G1, G2) as pure functions, so the server, the plugin and the
 * addon share one definition. The pattern is the plugin's {@code ActivityFeed.POSITION}; it matches integer triples and
 * {@code x=.. y=.. z=..}, not decimal coordinates (the log filter handles those by source and message prefix).
 */
public final class PositionGuard {
    public static final Pattern POSITION = Pattern.compile(
            "x=-?\\d+,?\\s*y=-?\\d+,?\\s*z=-?\\d+|(?<![\\w.\\-])-?\\d{1,8}(?:\\s*,\\s*|\\s+)-?\\d{1,4}(?:\\s*,\\s*|\\s+)-?\\d{1,8}(?![\\w.])");
    /** G1: key names refused at any depth, matched case-insensitively as whole names. */
    public static final Set<String> FORBIDDEN_KEYS = Set.of("x", "y", "z", "pos", "position", "origin", "coords",
            "anchor");
    private static final int MAX_DEPTH = 64;

    private PositionGuard() {
    }

    /** G2: true when the string looks like a position. */
    public static boolean looksLikePosition(String s) {
        return s != null && POSITION.matcher(s).find();
    }

    /** G1: true when a Map key anywhere in the tree (Maps and Lists, up to depth 64) is a forbidden name. */
    public static boolean hasPositionField(Object tree) {
        return walk(tree, 0);
    }

    private static boolean walk(Object o, int depth) {
        if (depth > MAX_DEPTH) {
            return true; // fail closed
        }
        if (o instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() instanceof String k && FORBIDDEN_KEYS.contains(k.toLowerCase(Locale.ROOT))) {
                    return true;
                }
                if (walk(e.getValue(), depth + 1)) {
                    return true;
                }
            }
        } else if (o instanceof List<?> l) {
            for (Object x : l) {
                if (walk(x, depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }
}
