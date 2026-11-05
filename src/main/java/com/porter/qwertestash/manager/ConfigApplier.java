package com.porter.qwertestash.manager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Applies one change from the dashboard to a config object. The change is checked first (the setting must exist and be
 * editable, the type must fit, enums must be one of their constants, numbers inside their limits, strings and lists not
 * too long) and compared with {@code expect}, the value the page saw: if the setting has changed since, nothing is
 * done and the answer is {@code conflict}. A setting with a command is changed by running it, so the command's own side
 * effects happen and an embed in the error colour counts as a failure; any other is set directly. The value is read back
 * afterwards and sent as the result.
 */
public final class ConfigApplier {
    /** Longest text, and most entries in a list, a change may carry. */
    public static final int MAX_STRING = 1000, MAX_LIST = 500;

    public record Result(boolean ok, @Nullable JsonElement value, @Nullable String error) {
    }

    /** What applying needs from the outside. */
    public interface Env {
        /** Runs a command line (like {@code stash radius 12}). */
        ConsoleRunner.Outcome command(String line);

        /** Called after a setting was set directly, to save the config. */
        void changed(String path);
    }

    private final Object root;
    private final ConfigSchema.Policy policy;
    private final Env env;
    private final String commandPrefix;

    public ConfigApplier(Object root, ConfigSchema.Policy policy, Env env, String commandPrefix) {
        this.root = root;
        this.policy = policy;
        this.env = env;
        this.commandPrefix = commandPrefix;
    }

    /**
     * @param expect the value the page saw, or null to skip the check (a JSON null is a value: the setting was null)
     */
    public Result apply(String path, @Nullable JsonElement value, @Nullable JsonElement expect) {
        ConfigSchema.Target target = ConfigSchema.find(root, path);
        SettingsSpec.Spec spec = policy.spec(path);
        if (target == null || (spec != null && spec.hidden)) return fail("no such setting");
        if (policy.secret(path)) return fail("secrets can't be changed here");
        Field field = target.field();
        String lock = policy.lock(path);
        if (lock != null) return fail("read-only: " + lock);
        if (spec == null && !policy.generic()) return fail("read-only: not editable from the dashboard");
        if (Modifier.isFinal(field.getModifiers())) return fail("read-only: fixed");
        if (java.util.Map.class.isAssignableFrom(field.getType())) return fail("read-only: edit via command");
        if (Collection.class.isAssignableFrom(field.getType()) && !ConfigSchema.editableList(field)) return fail("read-only: edit via command");
        if (spec == null) spec = GenericRules.of(field, target.owner());
        try {
            Object current = field.get(target.owner());
            if (ConfigSchema.hiddenValue(policy, field, current)) return fail("this value is not shown or changed here");
        } catch (IllegalAccessException | RuntimeException e) {
            return fail("no such setting");
        }

        Object typed;
        try {
            typed = validate(field, spec, value);
        } catch (IllegalArgumentException e) {
            return fail(e.getMessage());
        }

        if (expect != null) {
            JsonElement now = ConfigSchema.read(target);
            if (now == null) now = JsonNull.INSTANCE;
            if (!now.equals(expect)) return new Result(false, now, "conflict");
        }

        try {
            String line = commandFor(spec, field, typed);
            if (line != null) {
                ConsoleRunner.Outcome out = env.command(commandPrefix + line);
                if (!out.ok()) return new Result(false, ConfigSchema.read(target), out.error() != null ? out.error() : "the command failed: " + ConsoleRunner.text(out.output()));
            }
            else {
                set(target, typed);
                env.changed(path);
            }
        } catch (RuntimeException | LinkageError | IllegalAccessException e) {
            return new Result(false, ConfigSchema.read(target), "couldn't apply: " + e);
        }
        return new Result(true, ConfigSchema.read(target), null);
    }

    private static Result fail(String message) {
        return new Result(false, null, message);
    }

    /** The command for a value, or null when the setting is set directly. */
    private static @Nullable String commandFor(SettingsSpec.Spec spec, Field field, Object typed) {
        if (spec.command == null) return null;
        if (typed instanceof String s && s.isBlank() && spec.blankCommand != null) return spec.blankCommand;
        String text;
        if (typed instanceof Boolean b) text = b ? "on" : "off";
        else if (typed instanceof Double d) text = d == Math.rint(d) ? String.valueOf((long) (double) d) : String.valueOf(d);
        else text = String.valueOf(typed instanceof Enum<?> e ? e.name() : typed);
        return spec.command.replace("{v}", text);
    }

    /** The value as the field's Java type, or an IllegalArgumentException saying what is wrong with it. */
    static Object validate(Field field, SettingsSpec.Spec spec, @Nullable JsonElement json) {
        Class<?> type = field.getType();
        if (json == null || json.isJsonNull()) {
            if (!type.isPrimitive() && type != String.class && !Collection.class.isAssignableFrom(type)) return null;
            throw new IllegalArgumentException("a value is required");
        }

        if (Collection.class.isAssignableFrom(type)) {
            if (!json.isJsonArray()) throw new IllegalArgumentException("a list of text is required");
            JsonArray array = json.getAsJsonArray();
            if (array.size() > MAX_LIST) throw new IllegalArgumentException("at most " + MAX_LIST + " entries");
            List<String> out = new ArrayList<>();
            for (JsonElement e : array) {
                String s = text(e);
                if (s.length() > spec.maxLength || s.length() > MAX_STRING) throw new IllegalArgumentException("an entry is longer than " + Math.min(spec.maxLength, MAX_STRING) + " characters");
                if (spec.pattern != null && !spec.pattern.matcher(s).matches()) throw new IllegalArgumentException("\"" + s + "\" isn't allowed here");
                if (s.isBlank()) throw new IllegalArgumentException("an entry is empty");
                if (!out.contains(s)) out.add(s);
            }
            return out;
        }

        String kind = ConfigSchema.kind(type);
        if (kind == null) throw new IllegalArgumentException("not editable");
        if (!json.isJsonPrimitive()) throw new IllegalArgumentException("a " + kind + " is required");
        JsonPrimitive p = json.getAsJsonPrimitive();
        switch (kind) {
            case "bool" -> {
                if (!p.isBoolean()) throw new IllegalArgumentException("true or false is required");
                return p.getAsBoolean();
            }
            case "int", "long", "double" -> {
                if (!p.isNumber()) throw new IllegalArgumentException("a number is required");
                double d = p.getAsDouble();
                if (Double.isNaN(d) || Double.isInfinite(d)) throw new IllegalArgumentException("a finite number is required");
                boolean whole = !kind.equals("double") || spec.whole;
                if (whole && d != Math.rint(d)) throw new IllegalArgumentException("a whole number is required");
                double[] range = ConfigSchema.range(kind, spec);
                if (d < range[0] || d > range[1]) throw new IllegalArgumentException("must be between " + fmt(range[0]) + " and " + fmt(range[1]));
                return switch (kind) {
                    case "int" -> convert(type, (long) d);
                    case "long" -> (Object) (long) d;
                    default -> type == float.class || type == Float.class ? (Object) (float) d : (Object) d;
                };
            }
            case "enum" -> {
                if (!p.isString()) throw new IllegalArgumentException("one of the listed options is required");
                String s = p.getAsString();
                for (Object c : type.getEnumConstants()) if (((Enum<?>) c).name().equals(s)) return c;
                throw new IllegalArgumentException("must be one of " + options(type));
            }
            default -> {
                if (!p.isString()) throw new IllegalArgumentException("text is required");
                String s = p.getAsString();
                if (s.length() > Math.min(spec.maxLength, MAX_STRING)) throw new IllegalArgumentException("at most " + Math.min(spec.maxLength, MAX_STRING) + " characters");
                for (int i = 0; i < s.length(); i++) if (Character.isISOControl(s.charAt(i))) throw new IllegalArgumentException("no control characters");
                s = s.trim();
                if (spec.nonBlank && s.isEmpty()) throw new IllegalArgumentException("can't be empty");
                if (spec.pattern != null && !spec.pattern.matcher(s).matches()) throw new IllegalArgumentException("contains characters that aren't allowed here");
                return s;
            }
        }
    }

    private static Object convert(Class<?> type, long v) {
        if (type == short.class) return (short) v;
        if (type == byte.class) return (byte) v;
        return (int) v;
    }

    private static String text(JsonElement e) {
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("a list of text is required");
        String s = e.getAsString().trim();
        for (int i = 0; i < s.length(); i++) if (Character.isISOControl(s.charAt(i))) throw new IllegalArgumentException("no control characters");
        return s;
    }

    private static String options(Class<?> type) {
        List<String> names = new ArrayList<>();
        for (Object c : type.getEnumConstants()) names.add(((Enum<?>) c).name());
        return String.join(", ", names);
    }

    private static String fmt(double d) {
        if (d == Double.NEGATIVE_INFINITY || d == Double.POSITIVE_INFINITY) return d > 0 ? "no maximum" : "no minimum";
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    @SuppressWarnings("unchecked")
    private static void set(ConfigSchema.Target target, @Nullable Object typed) throws IllegalAccessException {
        Field f = target.field();
        if (typed instanceof List<?> list && Collection.class.isAssignableFrom(f.getType())) {
            // The collection is replaced as a whole, so readers never see it half changed
            if (spec(f)) typed = list.stream().map(s -> ((String) s).toLowerCase(Locale.ROOT)).distinct().toList();
            f.set(target.owner(), new ArrayList<>((List<Object>) typed));
            return;
        }
        f.set(target.owner(), typed);
    }

    /** Lists of item ids are kept lower case. */
    private static boolean spec(Field f) {
        return f.getName().equals("compactExclude");
    }
}
