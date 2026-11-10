package com.porter.qwertestash.orders;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * The small recipe table the tests craft by ({@code src/test/resources/recipes-test.json}: about 130 crafting recipes in the
 * shape the server sends, enough for shulker boxes, chests, planks, stairs, slabs, fences, ladders, iron bars and beds). The real
 * table is fetched from the Stash Manager server at run time and is not part of the repository.
 */
public final class TestRecipes {
    private TestRecipes() {
    }

    private static Recipes table;

    public static synchronized Recipes table() {
        if (table == null) {
            try (InputStream in = TestRecipes.class.getResourceAsStream("/recipes-test.json")) {
                if (in == null) throw new IllegalStateException("recipes-test.json is missing");
                table = Recipes.parse(new InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return table;
    }

    public static String json() {
        try (InputStream in = TestRecipes.class.getResourceAsStream("/recipes-test.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
