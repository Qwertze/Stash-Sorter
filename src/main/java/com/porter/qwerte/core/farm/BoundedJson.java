package com.porter.qwerte.core.farm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded JSON reader for untrusted farm program headers (contract C6.11 and A2). Unlike the resource-only MiniJson it
 * limits nesting (16), string length (256 characters), number size and node count, and every violation is the closed
 * code {@code bad_program} without any input text in it. Values: Map (insertion ordered), List, String, Long (integer
 * tokens), Double (decimal tokens, finite), Boolean, null. Duplicate object keys are refused.
 */
public final class BoundedJson {
    public static final int MAX_DEPTH = 16;
    public static final int MAX_STRING = 256;
    private static final int MAX_NUMBER_CHARS = 32;
    private static final int MAX_NODES = 100_000;

    private final String s;
    private int i;
    private int depth;
    private int nodes;

    private BoundedJson(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        BoundedJson p = new BoundedJson(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) {
            throw bad();
        }
        return v;
    }

    private static FarmException bad() {
        return new FarmException("bad_program");
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                i++;
            } else {
                break;
            }
        }
    }

    private Object value() {
        if (i >= s.length() || ++nodes > MAX_NODES) {
            throw bad();
        }
        char c = s.charAt(i);
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            case 't':
                return literal("true", Boolean.TRUE);
            case 'f':
                return literal("false", Boolean.FALSE);
            case 'n':
                return literal("null", null);
            default:
                return number();
        }
    }

    private Object literal(String word, Object v) {
        if (!s.startsWith(word, i)) {
            throw bad();
        }
        i += word.length();
        return v;
    }

    private Map<String, Object> object() {
        if (++depth > MAX_DEPTH) {
            throw bad();
        }
        i++;
        Map<String, Object> m = new LinkedHashMap<>();
        ws();
        if (i < s.length() && s.charAt(i) == '}') {
            i++;
            depth--;
            return m;
        }
        while (true) {
            ws();
            if (i >= s.length() || s.charAt(i) != '"') {
                throw bad();
            }
            String k = string();
            ws();
            if (i >= s.length() || s.charAt(i) != ':') {
                throw bad();
            }
            i++;
            ws();
            Object v = value();
            if (m.containsKey(k)) {
                throw bad();
            }
            m.put(k, v);
            ws();
            if (i >= s.length()) {
                throw bad();
            }
            char c = s.charAt(i++);
            if (c == '}') {
                break;
            }
            if (c != ',') {
                throw bad();
            }
        }
        depth--;
        return m;
    }

    private List<Object> array() {
        if (++depth > MAX_DEPTH) {
            throw bad();
        }
        i++;
        List<Object> l = new ArrayList<>();
        ws();
        if (i < s.length() && s.charAt(i) == ']') {
            i++;
            depth--;
            return l;
        }
        while (true) {
            ws();
            l.add(value());
            ws();
            if (i >= s.length()) {
                throw bad();
            }
            char c = s.charAt(i++);
            if (c == ']') {
                break;
            }
            if (c != ',') {
                throw bad();
            }
        }
        depth--;
        return l;
    }

    private String string() {
        i++; // opening quote
        StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length()) {
                throw bad();
            }
            char c = s.charAt(i++);
            if (c == '"') {
                break;
            }
            if (c < 0x20) {
                throw bad();
            }
            if (c == '\\') {
                if (i >= s.length()) {
                    throw bad();
                }
                char e = s.charAt(i++);
                switch (e) {
                    case '"', '\\', '/' -> b.append(e);
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'n' -> b.append('\n');
                    case 'r' -> b.append('\r');
                    case 't' -> b.append('\t');
                    case 'u' -> {
                        if (i + 4 > s.length()) {
                            throw bad();
                        }
                        int cp = 0;
                        for (int k = 0; k < 4; k++) {
                            int d = Character.digit(s.charAt(i + k), 16);
                            if (d < 0) {
                                throw bad();
                            }
                            cp = cp * 16 + d;
                        }
                        i += 4;
                        b.append((char) cp);
                    }
                    default -> throw bad();
                }
            } else {
                b.append(c);
            }
            if (b.length() > MAX_STRING) {
                throw bad();
            }
        }
        return b.toString();
    }

    private Object number() {
        int st = i;
        if (i < s.length() && s.charAt(i) == '-') {
            i++;
        }
        int digitsStart = i;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
            i++;
        }
        int intDigits = i - digitsStart;
        if (intDigits == 0 || (intDigits > 1 && s.charAt(digitsStart) == '0')) {
            throw bad();
        }
        boolean decimal = false;
        if (i < s.length() && s.charAt(i) == '.') {
            decimal = true;
            i++;
            int f = i;
            while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                i++;
            }
            if (i == f) {
                throw bad();
            }
        }
        if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            decimal = true;
            i++;
            if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                i++;
            }
            int f = i;
            while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                i++;
            }
            if (i == f) {
                throw bad();
            }
        }
        if (i - st > MAX_NUMBER_CHARS) {
            throw bad();
        }
        String tok = s.substring(st, i);
        try {
            if (!decimal) {
                if (intDigits > 18) {
                    throw bad();
                }
                return Long.parseLong(tok);
            }
            double d = Double.parseDouble(tok);
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw bad();
            }
            return d;
        } catch (NumberFormatException e) {
            throw bad();
        }
    }
}
