package com.porter.qwertestash.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.porter.qwertestash.stash.Item;
import com.porter.qwertestash.stash.KitClassifier;
import com.porter.qwertestash.stash.KitItems;
import com.porter.qwertestash.stash.KitNames;
import com.porter.qwertestash.stash.KitRouting;
import com.porter.qwertestash.stash.KitScore;
import com.porter.qwertestash.stash.KitSettings;
import com.porter.qwertestash.stash.KitTemplate;
import com.porter.qwertestash.stash.KitTemplates;
import com.porter.qwertestash.stash.KitTypes;
import com.porter.qwertestash.stash.Stash;
import com.porter.qwertestash.stash.Stash.Box;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The kit report (KIT-CONTRACT.md, "POST /api/v1/kits/report"): the containers of the stash with how much room each has and whether it
 * can take kits, where each kit shulker is, and which ones the bot carries. Built from a snapshot of the stash and plain data (the
 * contents arrays are replaced, never changed in place). Names and labels are cut and cleaned here, the server does it again. Any thread.
 * <p>
 * The v1 builds list the boxes that score as kits. The v2 build (1.16.0) lists the boxes that are kits by the classifier (a type, a
 * template, a hand-set category), with their reasons, and adds {@code classifier}, {@code others} (named boxes that are not kits),
 * {@code templates} (complete and incomplete copies of each kit template) and {@code restock}.
 */
public final class KitReport {
    private KitReport() {
    }

    public static final int MAX_CONTAINERS = 4096;
    public static final int MAX_KITS = 4096;
    public static final int MAX_CARRIED = 36;
    public static final int MAX_LABEL = 32;
    public static final int MAX_NAME = 64;
    public static final int MAX_OTHERS = 1024;
    public static final int MAX_MISSING = 27;
    /** The server takes 512 KiB; stay under it. */
    public static final int MAX_BYTES = 480_000;

    private static final Gson GSON_NULLS = new GsonBuilder().serializeNulls().create();

    /** A finished report: the JSON body, the hash of its content (the readAt fields left out) and what is in it. */
    public record Built(String server, String stash, String player, String hash, byte[] json, int containers, int kits, int carried) {
    }

    private record Container(String id, @Nullable String label, int size, int free, boolean pickable, long readAt, boolean holdsKits) {
    }

    /** One kit type of an entry (v2): how many boxes, and the reason of the first of them. */
    private static final class TypeAgg {
        final String type;
        final @Nullable String template;
        int count = 1;
        final KitClassifier.Result why;

        TypeAgg(KitClassifier.Result why) {
            this.type = why.type();
            this.template = why.template();
            this.why = why;
        }
    }

    /** One entry: the kits of one key (null: unnamed) in one container, or the carried ones of one key (container null). */
    private static final class Agg {
        final @Nullable String key;
        @Nullable String name;
        final @Nullable String container;
        int count;
        KitScore.Result best;
        int minTotal;
        final Map<String, TypeAgg> types = new LinkedHashMap<>();

        Agg(@Nullable String key, @Nullable String name, @Nullable String container, KitScore.Result score) {
            this.key = key;
            this.name = name;
            this.container = container;
            this.count = 1;
            this.best = score;
            this.minTotal = score.total();
        }

        /** The name is the smallest spelling, the score the highest total (ties by the parts), minTotal the lowest: stack order never matters. */
        void add(@Nullable String otherName, KitScore.Result score) {
            count++;
            if (otherName != null && (name == null || otherName.compareTo(name) < 0)) name = otherName;
            if (higher(score, best)) best = score;
            minTotal = Math.min(minTotal, score.total());
        }

        /** v2: the box's type; the reason kept is the first box's of that (type, template). */
        void addType(KitClassifier.@Nullable Result why, boolean first) {
            if (why == null || why.type() == null) return;
            String k = why.type() + "|" + (why.template() == null ? "" : why.template());
            TypeAgg t = types.get(k);
            if (t == null) types.put(k, new TypeAgg(why));
            else t.count++;
        }

        private static boolean higher(KitScore.Result a, KitScore.Result b) {
            if (a.total() != b.total()) return a.total() > b.total();
            if (a.name() != b.name()) return a.name() > b.name();
            if (a.mix() != b.mix()) return a.mix() > b.mix();
            if (a.full() != b.full()) return a.full() > b.full();
            return a.op() > b.op();
        }
    }

    /** A named box that is not a kit (v2), per key and container. */
    private static final class Other {
        final String key;
        @Nullable String name;
        final String container;
        int count = 1;
        final KitClassifier.Result why;

        Other(String key, @Nullable String name, String container, KitClassifier.Result why) {
            this.key = key;
            this.name = name;
            this.container = container;
            this.why = why;
        }
    }

    /** A box that is a copy of a kit template. {@code container} is null for a carried box. */
    private record TplHit(String template, @Nullable String container, KitTemplates.State state, List<KitTemplates.Missing> missing) {
    }

    /** What the v2 build adds to the body. */
    /** The restock state of the report being built (the builder runs on one thread at a time). */
    private static volatile com.porter.qwertestash.orders.KitRestock.@Nullable Decision restockNow;

    private record V2(String fingerprint, List<Other> others, JsonArray templates) {
    }

    /** How a box is judged: a kit or not, its key, the v1 score for display, and (v2) the classifier's reason. */
    private record Verdict(boolean kit, @Nullable String norm, KitScore.Result score, KitClassifier.@Nullable Result why) {
    }

    private interface Judge {
        Verdict judge(Item shulker);
    }

    /**
     * @param includeExcluded as the index: excluded containers are listed only when the index lists them
     * @param carried         the stacks in the bot's inventory (only kit shulkers are looked at)
     */
    public static Built build(Stash stash, boolean includeExcluded, String player, List<Item> carried) {
        return build(stash, includeExcluded, player, carried, KitScore.Scoring.DEFAULT, KitItems.shared());
    }

    /** @param scoring the stash's scoring: only boxes that are kits by it are reported (the v1 report) */
    public static Built build(Stash stash, boolean includeExcluded, String player, List<Item> carried, KitScore.Scoring scoring, KitItems table) {
        Judge judge = item -> {
            // The custom name is normalised once, for the name points and for the key
            String norm = item.customName ? KitNames.normalize(item.customText) : null;
            KitScore.Result score = KitScore.of(item, norm, scoring, table);
            return new Verdict(score.kit(), norm, score, null);
        };
        return core(stash, includeExcluded, player, carried, judge, null);
    }

    /**
     * The v2 report: boxes that are kits by the classifier, with their reasons; {@code scoring} and {@code items} only give the v1 {@code score}
     * (for display on old servers).
     * @param settings the cached kit settings (treatAs, templates, categories), or null
     */
    public static Built buildV2(Stash stash, boolean includeExcluded, String player, List<Item> carried, KitScore.Scoring scoring, KitItems items, @Nullable KitSettings settings) {
        return buildV2(stash, includeExcluded, player, carried, scoring, items, settings, null);
    }

    /** As above, with the state of kit restock for {@code restock} (null: off). */
    public static Built buildV2(Stash stash, boolean includeExcluded, String player, List<Item> carried, KitScore.Scoring scoring, KitItems items, @Nullable KitSettings settings,
                                com.porter.qwertestash.orders.KitRestock.@Nullable Decision restock) {
        if (!com.porter.qwertestash.farm.FarmerGuard.allowKitReport()) throw new IllegalStateException("farmer role"); // no kit report leaves a farmer
        restockNow = restock;
        KitTypes table = KitTypes.shared();
        KitSettings.Types types = settings == null ? null : settings.types();
        Judge judge = item -> {
            String norm = item.customName ? KitNames.normalize(item.customText) : null;
            KitScore.Result score = KitScore.of(item, norm, scoring, items);
            KitClassifier.Result why = classify(table, item, norm, types);
            return new Verdict(why.kit(), norm, score, why);
        };
        return core(stash, includeExcluded, player, carried, judge, new Context(table, settings));
    }

    /** The classifier on a shulker as the sorter reads it: the occupied slots in slot order. A table that can't be read means no kit. */
    public static KitClassifier.Result classify(@Nullable KitTypes table, Item shulker, @Nullable String norm, KitSettings.@Nullable Types types) {
        if (table == null) return new KitClassifier.Result(false, KitClassifier.NO_PURPOSE, null, 0, 0, 0, 0, 0, List.of(), List.of(), null, List.of());
        return KitClassifier.classify(table, slots(shulker), norm, types);
    }

    private static List<KitTemplates.BoxStack> slots(Item shulker) {
        List<KitTemplates.BoxStack> out = new ArrayList<>();
        for (Item inner : shulker.contents) if (inner != null && inner.id != null) out.add(new KitTemplates.BoxStack("minecraft:" + inner.id, inner.matchKey(), inner.count));
        return out;
    }

    private record Context(@Nullable KitTypes table, @Nullable KitSettings settings) {
    }

    private static Built core(Stash stash, boolean includeExcluded, String player, List<Item> carried, Judge judge, @Nullable Context v2) {
        Map<String, Container> containers = new LinkedHashMap<>();
        Map<String, Agg> kits = new LinkedHashMap<>();
        Map<String, Other> others = new LinkedHashMap<>();
        List<TplHit> hits = new ArrayList<>();
        List<KitTemplate> templates = v2 == null || v2.settings() == null || v2.settings().types() == null ? List.of() : v2.settings().types().templates();
        for (Box box : stash.boxes) {
            if (box.delivery || !stash.inScope(box) || (box.excluded && !includeExcluded)) continue;
            String id = KitRouting.canonicalId(box);
            if (!KitSettings.validId(id) || containers.containsKey(id)) continue;
            Item[] contents = box.contents;
            int size = contents != null ? (contents.length >= 54 ? 54 : 27) : (box.parts.size() > 1 ? 54 : 27);
            int free = 0;
            boolean holds = false;
            if (contents != null) {
                for (Item item : contents) {
                    if (item == null) {
                        free++;
                        continue;
                    }
                    if (!item.isShulker()) continue;
                    Verdict v = judge.judge(item);
                    String key = KitNames.usable(v.norm()) ? v.norm() : null;
                    String name = key == null ? null : name(item.customText);
                    if (!templates.isEmpty()) hit(templates, item, v.norm(), id, hits);
                    if (!v.kit()) {
                        // v2: a named box that is not a kit is listed with the reason, one entry per key and container
                        if (v.why() != null && key != null) {
                            String k = key + "\t" + id;
                            Other o = others.get(k);
                            if (o == null) others.put(k, new Other(key, name, id, v.why()));
                            else {
                                o.count++;
                                if (name != null && (o.name == null || name.compareTo(o.name) < 0)) o.name = name;
                            }
                        }
                        continue;
                    }
                    holds = true;
                    // One entry per (key, container), unnamed kits under key null
                    String k = (key == null ? "\u0000" : key) + "\t" + id;
                    Agg agg = kits.get(k);
                    if (agg == null) kits.put(k, agg = new Agg(key, name, id, v.score()));
                    else agg.add(name, v.score());
                    agg.addType(v.why(), false);
                }
            }
            String label = box.labels.isEmpty() ? null : clean(box.labels.getFirst(), MAX_LABEL);
            containers.put(id, new Container(id, label == null || label.isEmpty() ? null : label, size, Math.min(size, free), KitRouting.pickable(box),
                Math.max(0, box.readAt), holds));
        }

        Map<String, Agg> carriedAgg = new LinkedHashMap<>();
        for (Item item : carried) {
            if (item == null || !item.isShulker()) continue;
            Verdict v = judge.judge(item);
            if (!templates.isEmpty()) hit(templates, item, v.norm(), null, hits);
            if (!v.kit()) continue;
            String key = KitNames.usable(v.norm()) ? v.norm() : null;
            String name = key == null ? null : name(item.customText);
            String k = key == null ? "\u0000" : key;
            Agg agg = carriedAgg.get(k);
            if (agg == null) carriedAgg.put(k, agg = new Agg(key, name, null, v.score()));
            else agg.add(name, v.score());
            agg.addType(v.why(), false);
        }

        List<Container> list = new ArrayList<>(containers.values());
        List<Agg> kitList = new ArrayList<>(kits.values());
        // Over the limits, the ones that matter stay: those holding kits first, then those that can take them
        list.sort(Comparator.comparing((Container c) -> !c.holdsKits()).thenComparing(c -> !c.pickable()).thenComparing(Container::id));
        int limit = Math.min(MAX_CONTAINERS, list.size());
        V2 extra = null;
        if (v2 != null) {
            List<Other> otherList = new ArrayList<>(others.values());
            otherList.sort(Comparator.comparing((Other o) -> o.key).thenComparing(o -> o.container));
            if (otherList.size() > MAX_OTHERS) otherList = new ArrayList<>(otherList.subList(0, MAX_OTHERS));
            Set<String> pickable = new HashSet<>();
            for (Container c : list) if (c.pickable()) pickable.add(c.id());
            extra = new V2(v2.table() == null ? "" : v2.table().fingerprint(), otherList, templatesJson(templates, hits, pickable, v2.settings()));
        }
        Built built = assemble(stash, player, list, limit, kitList, carriedAgg, extra);
        while (built.json().length > MAX_BYTES && (limit > 0 || (extra != null && !extra.others().isEmpty()))) {
            // The named non-kits go first, then containers
            if (extra != null && !extra.others().isEmpty()) extra = new V2(extra.fingerprint(), new ArrayList<>(extra.others().subList(0, extra.others().size() / 2)), extra.templates());
            else limit = limit * 3 / 4;
            built = assemble(stash, player, list, limit, kitList, carriedAgg, extra);
        }
        return built;
    }

    /** A shulker that is a copy (complete or part built) of a template, for the report's counts. Carried boxes pass container null. */
    private static void hit(List<KitTemplate> templates, Item item, @Nullable String norm, @Nullable String container, List<TplHit> hits) {
        KitTemplates.Found f = KitTemplates.find(templates, slots(item), norm);
        if (f != null) hits.add(new TplHit(f.template().id(), container, f.match().state(), f.match().missing()));
    }

    private static JsonArray templatesJson(List<KitTemplate> templates, List<TplHit> hits, Set<String> pickable, @Nullable KitSettings settings) {
        JsonArray out = new JsonArray();
        List<KitTemplate> sorted = new ArrayList<>(templates);
        sorted.sort(Comparator.comparing(KitTemplate::id));
        for (KitTemplate t : sorted) {
            String target = settings == null ? null : KitRouting.templateTarget(t, settings);
            int complete = 0, incomplete = 0, elsewhere = 0, carried = 0;
            Map<String, KitTemplates.Missing> missing = new TreeMap<>();
            for (TplHit h : hits) {
                if (!h.template().equals(t.id())) continue;
                if (h.container() == null) {
                    if (h.state() == KitTemplates.State.COMPLETE) carried++;
                }
                else if (h.container().equals(target)) {
                    if (h.state() == KitTemplates.State.COMPLETE) complete++;
                    else {
                        incomplete++;
                        for (KitTemplates.Missing m : h.missing()) {
                            String k = m.key() + "\t" + m.match();
                            KitTemplates.Missing old = missing.get(k);
                            missing.put(k, new KitTemplates.Missing(m.key(), m.match(), (old == null ? 0 : old.count()) + m.count()));
                        }
                    }
                }
                else if (h.state() == KitTemplates.State.COMPLETE && pickable.contains(h.container())) elsewhere++;
            }
            List<KitTemplates.Missing> list = new ArrayList<>(missing.values());
            list.sort(Comparator.comparingInt((KitTemplates.Missing m) -> -m.count()).thenComparing(KitTemplates.Missing::key));
            JsonObject o = new JsonObject();
            o.addProperty("id", t.id());
            o.addProperty("complete", Math.min(4096, complete));
            o.addProperty("incomplete", Math.min(4096, incomplete));
            o.addProperty("elsewhere", Math.min(4096, elsewhere));
            o.addProperty("carried", Math.min(4096, carried));
            JsonArray miss = new JsonArray();
            for (KitTemplates.Missing m : list) {
                if (miss.size() >= MAX_MISSING) break;
                JsonObject mo = new JsonObject();
                mo.addProperty("match", m.match());
                mo.addProperty("key", m.key());
                mo.addProperty("count", Math.max(1, Math.min(4096, m.count())));
                miss.add(mo);
            }
            o.add("missing", miss);
            out.add(o);
        }
        return out;
    }

    private static Built assemble(Stash stash, String player, List<Container> priority, int limit, List<Agg> allKits, Map<String, Agg> carriedAgg, @Nullable V2 v2) {
        List<Container> list = new ArrayList<>(priority.subList(0, limit));
        list.sort(Comparator.comparing(Container::id));
        Set<String> ids = new HashSet<>();
        for (Container c : list) ids.add(c.id());

        List<Agg> kitList = new ArrayList<>();
        for (Agg k : allKits) if (ids.contains(k.container)) kitList.add(k);
        kitList.sort(Comparator.comparing((Agg a) -> a.key == null ? "" : a.key).thenComparing(a -> a.key != null).thenComparing(a -> a.container));
        if (kitList.size() > MAX_KITS) kitList = new ArrayList<>(kitList.subList(0, MAX_KITS));

        JsonArray containers = new JsonArray();
        long newest = 0;
        List<Long> reads = new ArrayList<>();
        for (Container c : list) {
            JsonObject o = new JsonObject();
            o.addProperty("id", c.id());
            if (c.label() == null) o.add("label", JsonNull.INSTANCE);
            else o.addProperty("label", c.label());
            o.addProperty("size", c.size());
            o.addProperty("free", c.free());
            o.addProperty("pickable", c.pickable());
            containers.add(o);
            reads.add(c.readAt());
            newest = Math.max(newest, c.readAt());
        }
        JsonArray kits = new JsonArray();
        for (Agg k : kitList) {
            JsonObject o = new JsonObject();
            putKey(o, k);
            o.addProperty("container", k.container);
            o.addProperty("count", Math.max(1, Math.min(54, k.count)));
            o.add("score", score(k.best));
            o.addProperty("minTotal", Math.max(0, Math.min(40, k.minTotal)));
            if (v2 != null) o.add("types", typesJson(k, 54));
            kits.add(o);
        }
        JsonArray carried = new JsonArray();
        List<String> keys = new ArrayList<>(carriedAgg.keySet());
        keys.sort(null);
        for (String key : keys) {
            if (carried.size() >= MAX_CARRIED) break;
            Agg a = carriedAgg.get(key);
            JsonObject o = new JsonObject();
            putKey(o, a);
            o.addProperty("count", Math.max(1, Math.min(36, a.count)));
            o.add("score", score(a.best));
            if (v2 != null) o.add("types", typesJson(a, 36));
            carried.add(o);
        }

        // The hash leaves every readAt out: a read that found nothing changed is not a change
        JsonObject body = new JsonObject();
        body.add("containers", containers);
        body.add("kits", kits);
        body.add("carried", carried);
        JsonObject classifier = null;
        JsonArray othersJson = null;
        JsonObject restock = null;
        if (v2 != null) {
            classifier = new JsonObject();
            classifier.addProperty("version", 2);
            classifier.addProperty("table", v2.fingerprint());
            othersJson = new JsonArray();
            for (Other o : v2.others()) {
                if (!ids.contains(o.container)) continue;
                JsonObject oo = new JsonObject();
                oo.addProperty("key", o.key);
                oo.addProperty("name", o.name == null ? o.key : o.name);
                oo.addProperty("container", o.container);
                oo.addProperty("count", Math.max(1, Math.min(54, o.count)));
                oo.add("why", whyJson(o.why));
                othersJson.add(oo);
            }
            restock = new JsonObject();
            com.porter.qwertestash.orders.KitRestock.Decision rd = restockNow;
            restock.addProperty("state", rd == null ? "off" : rd.state().name().toLowerCase(java.util.Locale.ROOT));
            if (rd == null || rd.template() == null) restock.add("template", JsonNull.INSTANCE);
            else restock.addProperty("template", rd.template());
            if (rd == null || rd.reason() == null) restock.add("reason", JsonNull.INSTANCE);
            else restock.addProperty("reason", rd.reason());
            body.add("classifier", classifier);
            body.add("others", othersJson);
            body.add("templates", v2.templates());
            body.add("restock", restock);
        }
        String hash = ManagerIndex.sha1((v2 == null ? "v1\n" : "v2\n") + GSON_NULLS.toJson(body));
        for (int i = 0; i < containers.size(); i++) containers.get(i).getAsJsonObject().addProperty("readAt", reads.get(i));

        String server = ManagerIndex.clamp(stash.server, 256), name = ManagerIndex.clamp(stash.name, 256), who = ManagerIndex.clamp(player, 256);
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("server", server);
        root.addProperty("stash", name);
        root.addProperty("player", who);
        root.addProperty("hash", hash);
        root.addProperty("readAt", newest);
        if (v2 != null) root.add("classifier", classifier);
        root.add("containers", containers);
        root.add("kits", kits);
        root.add("carried", carried);
        if (v2 != null) {
            root.add("others", othersJson);
            root.add("templates", v2.templates());
            root.add("restock", restock);
        }
        return new Built(server, name, who, hash, GSON_NULLS.toJson(root).getBytes(StandardCharsets.UTF_8), containers.size(), kits.size(), carried.size());
    }

    /** The entry's types, one per (category, template), in type order (the seven, then custom categories by id), each with the first box's reason. */
    private static JsonArray typesJson(Agg a, int max) {
        List<TypeAgg> list = new ArrayList<>(a.types.values());
        KitTypes table = KitTypes.shared();
        list.sort(Comparator.comparingInt((TypeAgg t) -> table == null || table.rank(t.type) < 0 ? 100 : table.rank(t.type)).thenComparing(t -> t.type)
            .thenComparing(t -> t.template == null ? "" : t.template));
        JsonArray out = new JsonArray();
        for (TypeAgg t : list) {
            JsonObject o = new JsonObject();
            o.addProperty("type", t.type);
            if (t.template != null) o.addProperty("template", t.template);
            o.addProperty("count", Math.max(1, Math.min(max, t.count)));
            o.add("why", whyJson(t.why));
            out.add(o);
        }
        return out;
    }

    private static int n27(int v) {
        return Math.max(0, Math.min(27, v));
    }

    /** The classifier's result as the contract has it: code, type, slots, covered, kinds, need and minKinds for the near misses, items, words, template, missing. */
    static JsonObject whyJson(KitClassifier.Result r) {
        JsonObject o = new JsonObject();
        o.addProperty("code", r.code());
        if (r.type() == null) o.add("type", JsonNull.INSTANCE);
        else o.addProperty("type", r.type());
        o.addProperty("slots", n27(r.slots()));
        o.addProperty("covered", n27(r.covered()));
        o.addProperty("kinds", n27(r.kinds()));
        if (r.code().equals(KitClassifier.MIXED) || r.code().equals(KitClassifier.FEW_KINDS)) {
            o.addProperty("need", n27(r.need()));
            o.addProperty("minKinds", n27(r.minKinds()));
        }
        JsonArray items = new JsonArray();
        for (String i : r.items()) if (items.size() < 3) items.add(i);
        o.add("items", items);
        JsonArray words = new JsonArray();
        for (String w : r.words()) if (words.size() < 4) words.add(w);
        o.add("words", words);
        if (r.template() != null) o.addProperty("template", r.template());
        if (r.code().equals(KitClassifier.TEMPLATE_INCOMPLETE)) {
            JsonArray missing = new JsonArray();
            for (KitTemplates.Missing m : r.missing()) {
                if (missing.size() >= 8) break;
                JsonObject mo = new JsonObject();
                mo.addProperty("key", m.key());
                mo.addProperty("count", Math.max(1, Math.min(4096, m.count())));
                missing.add(mo);
            }
            o.add("missing", missing);
        }
        return o;
    }

    private static void putKey(JsonObject o, Agg a) {
        if (a.key == null) {
            o.add("key", JsonNull.INSTANCE);
            o.add("name", JsonNull.INSTANCE);
        }
        else {
            o.addProperty("key", a.key);
            o.addProperty("name", a.name == null ? a.key : a.name);
        }
    }

    private static JsonObject score(KitScore.Result r) {
        JsonObject s = new JsonObject();
        s.addProperty("total", Math.max(0, Math.min(40, r.total())));
        s.addProperty("name", Math.max(0, Math.min(10, r.name())));
        s.addProperty("mix", Math.max(0, Math.min(10, r.mix())));
        s.addProperty("full", Math.max(0, Math.min(10, r.full())));
        s.addProperty("op", Math.max(0, Math.min(10, r.op())));
        return s;
    }

    /**
     * Whether the report goes out now. Only with kit sorting on, after a heartbeat reply carried the needKitsReport key, for the stash the
     * heartbeat is about, at most every 10 s, never a hash the server refused, and then when the hash differs from the last one sent
     * (the first report is sent on the bot's own initiative: nothing was sent yet) or the server asked.
     */
    public static boolean due(boolean kitSorting, boolean reportKnown, boolean sameStash, long sinceAttemptMs, String hash, @Nullable String sentHash,
                              @Nullable String rejectedHash, boolean asked) {
        if (!kitSorting || !reportKnown || !sameStash || sinceAttemptMs < 10_000) return false;
        if (hash.equals(rejectedHash)) return false;
        return asked || !hash.equals(sentHash);
    }

    /**
     * A cheap number that changes when anything the report is built from changes: the containers and their flags, labels, read times and the
     * identity of the contents array (a read replaces it, it is never changed in place). Used to skip rebuilding an unchanged report.
     */
    public static long fingerprint(Stash stash, boolean includeExcluded) {
        long fp = 17;
        for (Box box : stash.boxes) {
            if (box.delivery || !stash.inScope(box) || (box.excluded && !includeExcluded)) continue;
            fp = fp * 31 + System.identityHashCode(box.contents);
            fp = fp * 31 + box.readAt;
            fp = fp * 31 + box.parts.size();
            fp = fp * 31 + box.pos.hashCode();
            fp = fp * 31 + (box.excluded ? 1 : 0) + (box.dropOff ? 2 : 0) + (box.unreachable ? 4 : 0);
            fp = fp * 31 + box.labels.hashCode();
        }
        return fp;
    }

    /** What the carried shulkers look like to the report: their names and contents. Equal signatures give equal carried entries. */
    public static long carriedSignature(List<Item> carried) {
        long sig = 7;
        for (Item item : carried) {
            if (item == null) continue;
            sig = sig * 31 + (item.customName && item.customText != null ? item.customText.hashCode() : 0);
            sig = sig * 31 + item.contents.size();
            for (Item inner : item.contents) sig = sig * 31 + (inner == null || inner.id == null ? 0 : inner.id.hashCode() * 31 + inner.count);
        }
        return sig;
    }

    private static @Nullable String name(@Nullable String text) {
        String s = clean(text, MAX_NAME);
        return s.isEmpty() ? null : s;
    }

    /** Control and format characters dropped, tab and line breaks as a space, cut to {@code max} UTF-16 units (ending in an ellipsis when cut). */
    static String clean(@Nullable String text, int max) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(Math.min(text.length(), max + 8));
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (cp == '\t' || cp == '\n' || cp == '\r') cp = ' ';
            else if (type == Character.CONTROL || type == Character.FORMAT) continue;
            sb.appendCodePoint(cp);
            if (sb.length() > max * 2) break;
        }
        String s = sb.toString().strip();
        if (s.length() <= max) return s;
        int end = max - 1;
        if (Character.isHighSurrogate(s.charAt(end - 1))) end--;
        return s.substring(0, end).stripTrailing() + "…";
    }
}
