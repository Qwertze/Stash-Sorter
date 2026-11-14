package com.porter.qwerte.core.farm;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Canonical compact JSON writer for program headers: no whitespace, insertion order, shortest round-trip numbers. */
final class JsonOut {
    private JsonOut() {
    }

    static String write(Object o) {
        StringBuilder b = new StringBuilder(256);
        write(b, o);
        return b.toString();
    }

    static String number(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new FarmException("bad_program");
        }
        if (d == 0.0) {
            return "0.0"; // -0.0 would change the hash of an otherwise equal header
        }
        String s = Double.toString(d);
        if (s.indexOf('E') >= 0) {
            s = new BigDecimal(s).stripTrailingZeros().toPlainString();
            if (s.indexOf('.') < 0) {
                s = s + ".0";
            }
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder b, Object o) {
        if (o == null) {
            b.append("null");
        } else if (o instanceof String s) {
            string(b, s);
        } else if (o instanceof Boolean x) {
            b.append(x ? "true" : "false");
        } else if (o instanceof Integer || o instanceof Long) {
            b.append(o.toString());
        } else if (o instanceof Double d) {
            b.append(number(d));
        } else if (o instanceof Map<?, ?> m) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet()) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                string(b, e.getKey());
                b.append(':');
                write(b, e.getValue());
            }
            b.append('}');
        } else if (o instanceof List<?> l) {
            b.append('[');
            for (int k = 0; k < l.size(); k++) {
                if (k > 0) {
                    b.append(',');
                }
                write(b, l.get(k));
            }
            b.append(']');
        } else {
            throw new FarmException("bad_program");
        }
    }

    private static void string(StringBuilder b, String s) {
        b.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        b.append('"');
    }
}
