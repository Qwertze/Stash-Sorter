package com.porter.qwertestash.orders;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwertestash.manager.Extras;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The B3 test data, written by the real server's own code (stash-manager-server 1d99fa1, {@code HandRecipes} and {@code SpecialRecipes}): the extras body of the
 * 1.21.4 table, and the grids its planner gives to a set of rocket and star specs. Nothing here comes from game files.
 */
public final class FireworkFixtures {
    private FireworkFixtures() {
    }

    /** The extras hash the real B3 server gives its 1.21.4 (and 1.21.11) extras. */
    public static final String SERVER_HASH = "8b91796f5ddac63017d2b042a1da40334e05e46a";

    public static JsonObject extrasJson() {
        try (InputStream in = FireworkFixtures.class.getResourceAsStream("/extras-b3-1.21.4.json")) {
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static final Extras EXTRAS;
    public static final FireworkSpecs.Tables TABLES;

    static {
        try {
            EXTRAS = Extras.parse(extrasJson());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        TABLES = FireworkSpecs.Tables.of(EXTRAS);
    }

    /** The firework colour int of a dye name in the extras table under test: {@code fw(red)} of the contract's vectors. */
    public static int fw(String dye) {
        return TABLES.dyeColor().get(dye);
    }

    /** One special recipe as the server wrote it: recipe id, the spec it makes and its nine grid cells. */
    public record Grid(String recipe, String spec, String result, int count, List<Order.Cell> cells) {
        /** The step the server would store for {@code fills} fills of it (inputs: each cell once per fill). */
        public Order.CraftStep step(int fills) {
            Map<String, Long> in = new LinkedHashMap<>();
            for (Order.Cell c : cells) if (c != null) in.merge(c.ref(), (long) fills, Long::sum);
            List<Order.Qty> inputs = new ArrayList<>();
            for (var e : in.entrySet()) {
                String id = Pseudo.base(e.getKey());
                inputs.add(new Order.Qty(id, (int) (long) e.getValue(), null, Pseudo.spec(e.getKey())));
            }
            return new Order.CraftStep(recipe, id(), count, fills, inputs, false, List.of(), spec, cells);
        }

        private String id() {
            return Pseudo.base(result);
        }
    }

    public static List<Grid> grids() {
        try (InputStream in = FireworkFixtures.class.getResourceAsStream("/special-grids-b3.json")) {
            JsonArray a = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
            List<Grid> out = new ArrayList<>();
            for (JsonElement e : a) {
                JsonObject o = e.getAsJsonObject();
                List<Order.Cell> cells = new ArrayList<>();
                for (JsonElement c : o.getAsJsonArray("grid")) {
                    if (c.isJsonNull()) {
                        cells.add(null);
                        continue;
                    }
                    JsonObject co = c.getAsJsonObject();
                    cells.add(new Order.Cell(co.get("id").getAsString(), co.has("spec") ? co.get("spec").getAsString() : null));
                }
                out.add(new Grid(o.get("recipe").getAsString(), o.get("spec").getAsString(), o.get("result").getAsString(), o.get("count").getAsInt(), cells));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The server's grid for this spec (the first one: a creeper star has one per head), or throws. */
    public static Grid grid(String recipe, String spec) {
        for (Grid g : grids()) if (g.recipe().equals(recipe) && g.spec().equals(spec)) return g;
        throw new IllegalArgumentException(recipe + " " + spec);
    }
}
