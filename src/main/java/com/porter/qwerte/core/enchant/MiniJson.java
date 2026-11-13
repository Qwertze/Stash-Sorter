package com.porter.qwerte.core.enchant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny JSON reader for the bundled tables (objects, arrays, strings, integers, booleans, null). No dependencies.
 * Resource-only: it reads files that ship inside the jar and is NOT meant for untrusted input (it still limits the
 * nesting depth and reports truncated text as an IllegalArgumentException).
 */
final class MiniJson {
    private final String s;
    private int i;
    private int depth;
    private static final int MAX_DEPTH = 64;

    private MiniJson(String s) {
        this.s = s;
    }

    static Object parse(String text) {
        MiniJson p = new MiniJson(text);
        try {
            p.ws();
            Object v = p.value();
            p.ws();
            if (p.i != p.s.length()) {
                throw p.err("trailing data");
            }
            return v;
        } catch (StringIndexOutOfBoundsException | NumberFormatException e) {
            throw new IllegalArgumentException("bad json: truncated or malformed near " + p.i, e);
        }
    }

    private IllegalArgumentException err(String m) {
        return new IllegalArgumentException("bad json at " + i + ": " + m);
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }

    private Object value() {
        if (i >= s.length()) {
            throw err("unexpected end");
        }
        char c = s.charAt(i);
        switch (c) {
            case '{':
            case '[':
                if (++depth > MAX_DEPTH) {
                    throw err("nesting too deep");
                }
                Object nested = c == '{' ? object() : array();
                depth--;
                return nested;
            case '"':
                return string();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            default:
                return number();
        }
    }

    private void expect(String word) {
        if (!s.startsWith(word, i)) {
            throw err("expected " + word);
        }
        i += word.length();
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (s.charAt(i) == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            String k = string();
            ws();
            if (s.charAt(i++) != ':') {
                throw err("expected :");
            }
            ws();
            m.put(k, value());
            ws();
            char c = s.charAt(i++);
            if (c == '}') {
                return m;
            }
            if (c != ',') {
                throw err("expected , or }");
            }
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++;
        ws();
        if (s.charAt(i) == ']') {
            i++;
            return l;
        }
        while (true) {
            ws();
            l.add(value());
            ws();
            char c = s.charAt(i++);
            if (c == ']') {
                return l;
            }
            if (c != ',') {
                throw err("expected , or ]");
            }
        }
    }

    private String string() {
        if (s.charAt(i) != '"') {
            throw err("expected string");
        }
        i++;
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') {
                return b.toString();
            }
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> b.append('\n');
                    case 't' -> b.append('\t');
                    case 'r' -> b.append('\r');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'u' -> {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> b.append(e);
                }
            } else {
                b.append(c);
            }
        }
    }

    private Long number() {
        int start = i;
        if (s.charAt(i) == '-') {
            i++;
        }
        while (i < s.length() && Character.isDigit(s.charAt(i))) {
            i++;
        }
        if (start == i) {
            throw err("unexpected character");
        }
        return Long.parseLong(s.substring(start, i));
    }
}
