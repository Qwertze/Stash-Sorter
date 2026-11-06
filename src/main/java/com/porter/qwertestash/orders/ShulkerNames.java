package com.porter.qwertestash.orders;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The shulker names an order asks for (PROTOCOL.md "Shulker names"): one name for every shulker it delivers ("all"), or
 * one per shulker by its index in the plan ("each"). The server checks the names, and the bot checks them again: a name
 * that breaks the rules is not used, and that shulker is delivered unnamed. Plain data, no game types.
 */
public final class ShulkerNames {
    public static final String ALL = "all", EACH = "each";
    /** The anvil takes at most this many characters. */
    public static final int MAX_LENGTH = 50;
    /** One name per shulker of the biggest order. */
    public static final int MAX_EACH = Order.MAX_BINS;
    /** What every unnamed note says after its count. */
    public static final String UNNAMED_MARK = "shulker(s) delivered unnamed";

    public final String mode;
    private final @Nullable String all;
    private final List<String> each;

    private ShulkerNames(String mode, @Nullable String all, List<String> each) {
        this.mode = mode;
        this.all = all;
        this.each = each;
    }

    public static ShulkerNames all(String name) {
        return new ShulkerNames(ALL, name, List.of());
    }

    public static ShulkerNames each(List<String> names) {
        return new ShulkerNames(EACH, null, Collections.unmodifiableList(new ArrayList<>(names)));
    }

    /**
     * Reads an order's {@code names}. Null when there is nothing to name: the field is missing, null, of an unknown mode or of
     * the wrong types (naming never fails an order, so a broken field is read as no names), or every name is blank.
     */
    public static @Nullable ShulkerNames parse(@Nullable JsonElement e) {
        if (e == null || !e.isJsonObject()) return null;
        JsonObject o = e.getAsJsonObject();
        try {
            if (!o.has("mode") || !o.get("mode").isJsonPrimitive()) return null;
            String mode = o.get("mode").getAsString();
            if (mode.equals(ALL)) {
                if (!o.has("name") || !o.get("name").isJsonPrimitive() || !o.get("name").getAsJsonPrimitive().isString()) return null;
                String name = o.get("name").getAsString().trim();
                return name.isEmpty() ? null : all(name);
            }
            if (mode.equals(EACH)) {
                if (!o.has("names") || !o.get("names").isJsonArray()) return null;
                JsonArray array = o.getAsJsonArray("names");
                List<String> names = new ArrayList<>();
                boolean any = false;
                for (JsonElement n : array) {
                    if (names.size() >= MAX_EACH) break;
                    // A wrong entry is one shulker left unnamed, not the whole list
                    String s = n != null && n.isJsonPrimitive() && n.getAsJsonPrimitive().isString() ? n.getAsString().trim() : "";
                    names.add(s);
                    any |= !s.isEmpty();
                }
                return any ? each(names) : null;
            }
        } catch (RuntimeException ex) {
            // Read as no names
        }
        return null;
    }

    /**
     * The name for the shulker at this index of the plan (0-based), trimmed, or null when it gets none. The name is as the
     * order gave it: {@link #problem} says whether it may be used.
     */
    public @Nullable String nameFor(int index) {
        if (index < 0) return null;
        if (mode.equals(ALL)) return all;
        if (index >= each.size()) return null;
        String s = each.get(index);
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Why this name can't go on a shulker (the anvil's rules), or null when it can. */
    public static @Nullable String problem(@Nullable String name) {
        if (name == null) return "no name";
        String t = name.trim();
        if (t.isEmpty()) return "a blank name";
        if (t.length() > MAX_LENGTH) return "a name longer than " + MAX_LENGTH + " characters";
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '§') return "a name with a § in it";
            if (Character.isISOControl(c)) return "a name with a control character in it";
        }
        return null;
    }

    /** The name, trimmed, if it may be used; else null. */
    public static @Nullable String normalise(@Nullable String name) {
        return problem(name) == null ? name.trim() : null;
    }

    /**
     * The one note an order's problems get for the shulkers it delivered without their names, worded as the contract
     * with the addon has it: "2 shulker(s) delivered unnamed: no anvil set, too few XP levels". Null when every shulker got
     * its name.
     */
    public static @Nullable String unnamedNote(int count, List<String> reasons) {
        if (count <= 0) return null;
        return count + " " + UNNAMED_MARK + (reasons.isEmpty() ? "" : ": " + String.join(", ", reasons));
    }
}
