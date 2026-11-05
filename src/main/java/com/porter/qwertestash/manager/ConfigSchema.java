package com.porter.qwertestash.manager;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Walks the public non-static fields of a config object and builds what the dashboard shows: a schema tree and a flat
 * map of values ({@code "<dot.path>": json}), each with a SHA-1. Types: bool, int, long, double, string, enum and
 * stringList. Maps, final collections and types it doesn't know are read-only with a reason. A secret is only described
 * (secret:true, set:bool): its value is never read into the JSON. Which setting is a secret, read-only or hidden, and how
 * it is labelled, is the {@link Policy}'s to say, so the same walk serves every scope.
 */
public final class ConfigSchema {
    private ConfigSchema() {
    }

    private static final Gson GSON = new Gson();
    /** The server's limits: nodes in a tree and levels in it. */
    public static final int MAX_NODES = 5000, MAX_DEPTH = 12;

    public interface Policy {
        /** What is known about the setting at this path, or null. */
        SettingsSpec.@Nullable Spec spec(String path);

        boolean secret(String path);

        /** Why the setting can't be changed from the dashboard, or null if it can. */
        default @Nullable String lock(String path) {
            SettingsSpec.Spec spec = spec(path);
            return spec == null ? null : spec.readOnly;
        }

        /** The group the setting is shown in, or null to use the spec's section. */
        default @Nullable String section(String path) {
            return null;
        }

        /** Names of the top-level settings in the order they are shown; the rest follow in field order. */
        default Iterable<String> order() {
            return List.of();
        }

        /** True: top-level settings are shown as they are (a tree). False: they are grouped into the specs' sections. */
        default boolean tree() {
            return false;
        }

        /** True: a setting without a spec may be edited. False: only the ones with a spec. */
        default boolean generic() {
            return false;
        }

        /** True: string values (and lists of them) are shown as set-only, like secrets: nothing is known about what they hold. */
        default boolean hideStrings() {
            return false;
        }
    }

    /** The plugin's own settings. */
    public static final Policy PLUGIN = new Policy() {
        @Override
        public SettingsSpec.@Nullable Spec spec(String path) {
            return SettingsSpec.PLUGIN.get(path);
        }

        @Override
        public boolean secret(String path) {
            return Secrets.plugin(path);
        }

        @Override
        public Iterable<String> order() {
            return SettingsSpec.PLUGIN.keySet();
        }
    };

    public record Built(JsonArray schema, JsonObject values, String schemaHash, String valuesHash, int nodes, boolean truncated) {
    }

    /** A setting found by path. */
    public record Target(Object owner, Field field, String path) {
    }

    private static final class Ctx {
        final Class<?> rootClass;
        final Policy policy;
        final JsonObject values = new JsonObject();
        int nodes;
        boolean truncated;

        Ctx(Class<?> rootClass, Policy policy) {
            this.rootClass = rootClass;
            this.policy = policy;
        }

        boolean room() {
            if (nodes >= MAX_NODES) {
                truncated = true;
                return false;
            }
            nodes++;
            return true;
        }
    }

    public static Built build(Object root, Policy policy) {
        Ctx ctx = new Ctx(root.getClass(), policy);
        Map<String, JsonArray> sections = new LinkedHashMap<>();
        JsonArray loose = new JsonArray();
        JsonArray schema = new JsonArray();
        for (String path : orderedPaths(root, policy)) {
            Field f = fieldNamed(root.getClass(), path);
            if (f == null) continue;
            JsonObject node = node(root, f, path, ctx, 1);
            if (node == null) continue;
            if (policy.tree()) {
                schema.add(node);
                continue;
            }
            SettingsSpec.Spec spec = policy.spec(path);
            if (spec != null) sections.computeIfAbsent(spec.section, s -> new JsonArray()).add(node);
            else loose.add(node);
        }
        if (!policy.tree()) {
            for (Map.Entry<String, JsonArray> e : sections.entrySet()) schema.add(group("section:" + e.getKey(), e.getKey(), e.getValue()));
            if (loose.size() > 0) schema.add(group("section:Other", "Other", loose));
        }

        JsonObject sorted = new JsonObject();
        for (Map.Entry<String, JsonElement> e : new TreeMap<>(ctx.values.asMap()).entrySet()) sorted.add(e.getKey(), e.getValue());
        return new Built(schema, sorted, ManagerIndex.sha1(GSON.toJson(schema)), ManagerIndex.sha1(GSON.toJson(sorted)), ctx.nodes, ctx.truncated);
    }

    private static List<String> orderedPaths(Object root, Policy policy) {
        List<String> paths = new ArrayList<>();
        for (Field f : visibleFields(root.getClass())) paths.add(f.getName());
        List<String> ordered = new ArrayList<>();
        for (String known : policy.order()) if (policy.spec(known) != null && paths.remove(known)) ordered.add(known);
        ordered.addAll(paths);
        return ordered;
    }

    private static JsonObject group(String path, String label, JsonArray children) {
        JsonObject g = new JsonObject();
        g.addProperty("path", path);
        g.addProperty("label", label);
        g.addProperty("type", "group");
        g.add("children", children);
        return g;
    }

    static List<Field> visibleFields(Class<?> type) {
        List<Field> out = new ArrayList<>();
        for (Field f : type.getDeclaredFields()) {
            int m = f.getModifiers();
            if (!Modifier.isPublic(m) || Modifier.isStatic(m) || Modifier.isTransient(m) || f.isSynthetic()) continue;
            out.add(f);
        }
        return out;
    }

    private static @Nullable Field fieldNamed(Class<?> type, String name) {
        for (Field f : visibleFields(type)) if (f.getName().equals(name)) return f;
        return null;
    }

    /** A nested config object belongs to the config being walked: same package as its root, or nested in it. */
    private static boolean owned(Class<?> type, Class<?> root) {
        if (type.isPrimitive() || type.isArray() || type.isEnum() || type.isInterface() || type.getName().startsWith("java.")) return false;
        if (type.getPackageName().equals(root.getPackageName())) return true;
        Class<?> outer = type;
        while (outer.getEnclosingClass() != null) outer = outer.getEnclosingClass();
        return outer == root;
    }

    /** The element type of a collection field is String. */
    private static boolean stringElements(Field f) {
        Type g = f.getGenericType();
        if (!(g instanceof ParameterizedType p) || p.getActualTypeArguments().length != 1) return false;
        return p.getActualTypeArguments()[0] == String.class;
    }

    /** A list field the dashboard can replace by assigning a new ArrayList. */
    static boolean editableList(Field f) {
        return Collection.class.isAssignableFrom(f.getType()) && f.getType().isAssignableFrom(ArrayList.class) && stringElements(f) && !Modifier.isFinal(f.getModifiers());
    }

    /** A value that is shown as set-only whatever its name: a login inside a URL, or any text in a scope that hides them. */
    static boolean hiddenValue(Policy policy, Field f, @Nullable Object value) {
        if (value instanceof String s && Secrets.hasUserInfo(s)) return true;
        if (!policy.hideStrings()) return false;
        return f.getType() == String.class || Collection.class.isAssignableFrom(f.getType());
    }

    private static JsonObject leaf(String path, String label, String type, @Nullable String help, @Nullable String section) {
        JsonObject n = new JsonObject();
        n.addProperty("path", path);
        n.addProperty("label", label);
        n.addProperty("type", type);
        if (help != null && !help.isEmpty()) n.addProperty("help", help);
        if (section != null) n.addProperty("section", section);
        return n;
    }

    private static JsonObject readOnly(JsonObject n, String reason) {
        n.addProperty("readOnly", true);
        n.addProperty("reason", reason);
        return n;
    }

    /** The node for a setting, with its value added to the values; null for a hidden one or when the tree is full. */
    private static @Nullable JsonObject node(Object owner, Field f, String path, Ctx ctx, int depth) {
        Policy policy = ctx.policy;
        SettingsSpec.Spec spec = policy.spec(path);
        if (spec != null && spec.hidden) return null;
        if (depth > MAX_DEPTH) {
            ctx.truncated = true;
            return null;
        }
        Class<?> type = f.getType();
        String label = spec != null ? spec.label : label(f.getName());
        String section = spec != null ? spec.section : policy.section(path);

        Object value;
        try {
            value = f.get(owner);
        } catch (IllegalAccessException | RuntimeException e) {
            return null;
        }

        boolean hidden = policy.secret(path) || hiddenValue(policy, f, value);
        if (hidden) {
            if (!ctx.room()) return null;
            JsonObject n = leaf(path, label, "string", null, section);
            n.addProperty("secret", true);
            boolean set = value instanceof String s ? !s.isBlank() : value instanceof Map<?, ?> m ? !m.isEmpty() : value instanceof Collection<?> c ? !c.isEmpty() : value != null;
            n.addProperty("set", set);
            return readOnly(n, "secret");
        }

        String lock = policy.lock(path);
        boolean isFinal = Modifier.isFinal(f.getModifiers());
        String help = spec != null ? spec.help : null;

        if (Map.class.isAssignableFrom(type)) {
            if (!ctx.room()) return null;
            return readOnly(leaf(path, label, "string", help, section), "edit via command (" + (value instanceof Map<?, ?> m ? m.size() : 0) + " entries)");
        }
        if (Collection.class.isAssignableFrom(type)) {
            if (!ctx.room()) return null;
            JsonObject n = leaf(path, label, "stringList", help, section);
            int size = value instanceof Collection<?> c ? c.size() : 0;
            boolean strings = value instanceof Collection<?> c && stringsOnly(c);
            if (lock != null) readOnly(n, lock);
            else if (!editableList(f)) readOnly(n, "edit via command (" + size + " entries)");
            if (strings) {
                JsonArray list = new JsonArray();
                for (Object o : (Collection<?>) value) list.add(String.valueOf(o));
                ctx.values.add(path, list);
            }
            return n;
        }

        String kind = kind(type);
        if (kind == null) {
            if (value != null && owned(type, ctx.rootClass)) {
                // A nested config object: a group of its own
                if (!ctx.room()) return null;
                JsonArray children = new JsonArray();
                for (Field child : visibleFields(type)) {
                    JsonObject c = node(value, child, path + "." + child.getName(), ctx, depth + 1);
                    if (c != null) children.add(c);
                }
                return group(path, label, children);
            }
            if (!ctx.room()) return null;
            return readOnly(leaf(path, label, "string", help, section), "not editable here (" + type.getSimpleName() + ")");
        }

        if (!ctx.room()) return null;
        JsonObject n = leaf(path, label, kind, help, section);
        if (!type.isPrimitive()) n.addProperty("nullable", true);
        if (kind.equals("enum")) {
            JsonArray options = new JsonArray();
            for (Object c : type.getEnumConstants()) options.add(((Enum<?>) c).name());
            n.add("options", options);
        }
        if (kind.equals("int") || kind.equals("long") || kind.equals("double")) {
            double[] range = range(kind, spec == null && policy.generic() ? GenericRules.of(f, owner) : spec);
            if (range[0] != Double.NEGATIVE_INFINITY) n.addProperty("min", number(range[0], !kind.equals("double")));
            if (range[1] != Double.POSITIVE_INFINITY) n.addProperty("max", number(range[1], !kind.equals("double")));
        }
        if (lock != null) readOnly(n, lock);
        else if (isFinal) readOnly(n, "fixed");
        JsonElement json = toJson(value);
        if (json != null) ctx.values.add(path, json);
        return n;
    }

    private static Number number(double d, boolean whole) {
        return whole ? (Number) (long) d : (Number) d;
    }

    /** min and max for a number: the spec's, else the type's (infinite for a double). */
    static double[] range(String kind, SettingsSpec.@Nullable Spec spec) {
        double min = switch (kind) {
            case "int" -> Integer.MIN_VALUE;
            case "long" -> Long.MIN_VALUE;
            default -> Double.NEGATIVE_INFINITY;
        };
        double max = switch (kind) {
            case "int" -> Integer.MAX_VALUE;
            case "long" -> Long.MAX_VALUE;
            default -> Double.POSITIVE_INFINITY;
        };
        if (spec != null && spec.min != null) min = spec.min;
        if (spec != null && spec.max != null) max = spec.max;
        return new double[] {min, max};
    }

    /** The schema type of a Java type, or null if it isn't a simple one. */
    static @Nullable String kind(Class<?> type) {
        if (type == boolean.class || type == Boolean.class) return "bool";
        if (type == int.class || type == Integer.class || type == short.class || type == byte.class) return "int";
        if (type == long.class || type == Long.class) return "long";
        if (type == double.class || type == Double.class || type == float.class || type == Float.class) return "double";
        if (type == String.class) return "string";
        if (type.isEnum()) return "enum";
        return null;
    }

    private static boolean stringsOnly(Collection<?> c) {
        for (Object o : c) if (!(o instanceof String)) return false;
        return true;
    }

    static @Nullable JsonElement toJson(@Nullable Object value) {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof Boolean b) return new JsonPrimitive(b);
        if (value instanceof Number n) {
            if (n instanceof Double d && (d.isNaN() || d.isInfinite())) return null;
            if (n instanceof Float f && (f.isNaN() || f.isInfinite())) return null;
            if (n instanceof Double || n instanceof Float) {
                double d = n.doubleValue();
                // 16.0 as 16
                return d == Math.rint(d) && Math.abs(d) < 1e15 ? new JsonPrimitive((long) d) : new JsonPrimitive(d);
            }
            return new JsonPrimitive(n);
        }
        if (value instanceof String s) return new JsonPrimitive(s);
        if (value instanceof Enum<?> e) return new JsonPrimitive(e.name());
        return null;
    }

    /** "managerScanInterval" as "Manager scan interval". */
    static String label(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) {
            if (Character.isUpperCase(c) && sb.length() > 0) sb.append(' ').append(Character.toLowerCase(c));
            else sb.append(sb.length() == 0 ? Character.toUpperCase(c) : c);
        }
        return sb.toString();
    }

    /** The setting at a dotted path under root, or null. */
    public static @Nullable Target find(Object root, String path) {
        Object owner = root;
        String[] parts = path.split("\\.");
        for (int i = 0; i < parts.length; i++) {
            if (owner == null) return null;
            Field f = fieldNamed(owner.getClass(), parts[i]);
            if (f == null) return null;
            if (i == parts.length - 1) return new Target(owner, f, path);
            try {
                owner = f.get(owner);
            } catch (IllegalAccessException | RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    /** The current value as JSON (null if it can't be shown). */
    public static @Nullable JsonElement read(Target target) {
        try {
            Object value = target.field().get(target.owner());
            if (value instanceof Collection<?> c && stringsOnly(c)) {
                JsonArray list = new JsonArray();
                for (Object o : c) list.add(String.valueOf(o));
                return list;
            }
            return toJson(value);
        } catch (IllegalAccessException | RuntimeException e) {
            return null;
        }
    }
}
