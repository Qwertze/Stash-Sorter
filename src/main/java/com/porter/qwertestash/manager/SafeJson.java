package com.porter.qwertestash.manager;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Parses JSON that came from the network. Text nested deeper than 32 levels is refused before it is parsed (a cheap scan
 * that skips strings), and an Error from the parser (stack overflow, out of memory) becomes a plain parse failure, so
 * one bad reply can't take a thread down.
 */
public final class SafeJson {
    private SafeJson() {
    }

    public static final int MAX_DEPTH = 32;

    /** @throws JsonParseException when the text is not JSON, is nested too deep, or could not be parsed safely */
    public static JsonElement parse(String text) {
        if (tooDeep(text)) throw new JsonParseException("nested too deeply");
        try {
            return JsonParser.parseString(text);
        } catch (JsonParseException e) {
            throw e;
        } catch (RuntimeException | StackOverflowError | OutOfMemoryError e) {
            throw new JsonParseException("couldn't parse: " + e.getClass().getSimpleName());
        }
    }

    static boolean tooDeep(String text) {
        int depth = 0;
        boolean inString = false;
        for (int i = 0, n = text.length(); i < n; i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') i++;
                else if (c == '"') inString = false;
            }
            else if (c == '"') inString = true;
            else if (c == '[' || c == '{') {
                if (++depth > MAX_DEPTH) return true;
            }
            else if (c == ']' || c == '}') depth = Math.max(0, depth - 1);
        }
        return false;
    }
}
