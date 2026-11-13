package com.porter.qwertestash.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qwertecore.CoreSync;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The drift check of the synced copy of qwerte-core (the same code the build's checkQwerteCore and syncQwerteCore tasks run): equal copies pass, a changed,
 * missing or stale file fails, line endings do not matter, an absent library folder is a skip (a fresh clone of the plugin alone), and the real copy in this
 * repository matches the library when the library is next to it.
 */
class CoreDriftTest {
    @TempDir
    Path tmp;

    private static void write(Path root, String rel, String text) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text, StandardCharsets.UTF_8);
    }

    private Path core() throws IOException {
        Path core = tmp.resolve("Qwertze/qwerte-core");
        write(core, "src/main/java/com/porter/qwerte/core/enchant/Anvil.java", "package a;\nclass Anvil {}\n");
        write(core, "src/main/java/com/porter/qwerte/core/enchant/Planner.java", "package a;\nclass Planner {}\n");
        write(core, "src/main/resources/com/porter/qwerte/core/enchant/enchant-1.21.4.json", "{\"mc\":\"1.21.4\"}\n");
        // outside the library's package: never copied or compared
        write(core, "src/main/java/other/Skip.java", "class Skip {}\n");
        return core;
    }

    private Path plugin() throws IOException {
        Path plugin = tmp.resolve("QwerteStash");
        write(plugin, "src/main/java/com/porter/qwertestash/Own.java", "class Own {}\n");
        return plugin;
    }

    @Test
    void aSyncMakesTheCopyEqualAndTheCheckPasses() throws IOException {
        Path core = core(), plugin = plugin();
        CoreSync.Result before = CoreSync.check(core, plugin);
        assertFalse(before.ok());
        assertEquals(3, before.problems().size());
        assertEquals(3, CoreSync.sync(core, plugin));
        CoreSync.Result after = CoreSync.check(core, plugin);
        assertTrue(after.ok(), after.problems().toString());
        assertFalse(after.skipped());
        // the json table is in the plugin's resources (so it lands in the shaded jar), the other package is not copied
        assertTrue(Files.exists(plugin.resolve("src/main/resources/com/porter/qwerte/core/enchant/enchant-1.21.4.json")));
        assertFalse(Files.exists(plugin.resolve("src/main/java/other/Skip.java")));
        // a second sync changes nothing, and the plugin's own code was never touched
        assertEquals(0, CoreSync.sync(core, plugin));
        assertTrue(Files.exists(plugin.resolve("src/main/java/com/porter/qwertestash/Own.java")));
    }

    @Test
    void aChangedFileIsDriftTheBuildFailsOn() throws IOException {
        Path core = core(), plugin = plugin();
        CoreSync.sync(core, plugin);
        write(plugin, "src/main/java/com/porter/qwerte/core/enchant/Planner.java", "package a;\nclass Planner { int x; }\n");
        CoreSync.Result r = CoreSync.check(core, plugin);
        assertFalse(r.ok());
        assertEquals(1, r.problems().size());
        assertTrue(r.problems().get(0).startsWith("differs: "), r.problems().get(0));
        // the library changing is drift just as well
        CoreSync.sync(core, plugin);
        write(core, "src/main/resources/com/porter/qwerte/core/enchant/enchant-1.21.4.json", "{\"mc\":\"1.21.4\",\"x\":1}\n");
        assertFalse(CoreSync.check(core, plugin).ok());
        assertEquals(1, CoreSync.sync(core, plugin));
        assertTrue(CoreSync.check(core, plugin).ok());
    }

    @Test
    void aMissingFileAndAStaleFileAreDrift() throws IOException {
        Path core = core(), plugin = plugin();
        CoreSync.sync(core, plugin);
        Files.delete(plugin.resolve("src/main/java/com/porter/qwerte/core/enchant/Anvil.java"));
        write(plugin, "src/main/java/com/porter/qwerte/core/enchant/Old.java", "class Old {}\n");
        CoreSync.Result r = CoreSync.check(core, plugin);
        assertFalse(r.ok());
        assertTrue(r.problems().stream().anyMatch(p -> p.startsWith("missing in the plugin: ")), r.problems().toString());
        assertTrue(r.problems().stream().anyMatch(p -> p.startsWith("not in the library any more: ")), r.problems().toString());
        // a sync removes the stale file and restores the missing one
        assertEquals(2, CoreSync.sync(core, plugin));
        assertFalse(Files.exists(plugin.resolve("src/main/java/com/porter/qwerte/core/enchant/Old.java")));
        assertTrue(CoreSync.check(core, plugin).ok());
    }

    @Test
    void lineEndingsAreNotDrift() throws IOException {
        Path core = core(), plugin = plugin();
        CoreSync.sync(core, plugin);
        write(plugin, "src/main/java/com/porter/qwerte/core/enchant/Anvil.java", "package a;\r\nclass Anvil {}\r\n");
        assertTrue(CoreSync.check(core, plugin).ok());
    }

    @Test
    void anAbsentLibraryFolderIsASkipNotAFailure() throws IOException {
        Path plugin = plugin();
        CoreSync.Result r = CoreSync.check(tmp.resolve("Qwertze/no-such-folder"), plugin);
        assertTrue(r.skipped());
        assertTrue(r.ok());
        assertTrue(r.problems().isEmpty());
        assertTrue(CoreSync.check(null, plugin).skipped());
        // a sync needs the folder
        assertThrows(IOException.class, () -> CoreSync.sync(tmp.resolve("Qwertze/no-such-folder"), plugin));
    }

    @Test
    void aSyncFromAFolderWithoutThePackageRefusesAndLeavesTheCopyAlone() throws IOException {
        Path core = core(), plugin = plugin();
        CoreSync.sync(core, plugin);
        Path broken = tmp.resolve("broken/qwerte-core");
        write(broken, "src/main/java/other/X.java", "class X {}");
        assertThrows(IOException.class, () -> CoreSync.sync(broken, plugin));
        assertTrue(CoreSync.check(core, plugin).ok());
        assertTrue(Files.exists(plugin.resolve("src/main/java/com/porter/qwerte/core/enchant/Anvil.java")));
    }

    @Test
    void theCopyInThisRepositoryMatchesTheLibraryBesideIt() throws IOException {
        Path plugin = Path.of("").toAbsolutePath();
        Path core = plugin.resolve("../Qwertze/qwerte-core").normalize();
        CoreSync.Result r = CoreSync.check(core, plugin);
        if (r.skipped()) return; // a clone of the plugin alone: the drift check is skipped with a warning at build time
        assertTrue(r.ok(), "qwerte-core has drifted: run tools/sync-qwerte-core.ps1\n" + String.join("\n", r.problems()));
    }

    @Test
    void theShadedJarsResourcesAreOnTheClasspath() {
        // the enchant tables are resources of the plugin itself, so the shaded jar carries them
        for (String mc : new String[] {"1.21.4", "1.21.11"}) {
            assertTrue(CoreDriftTest.class.getClassLoader().getResource("com/porter/qwerte/core/enchant/enchant-" + mc + ".json") != null, mc);
        }
        assertEquals("1.21.4", com.porter.qwerte.core.enchant.McData.load("1.21.4").mc());
    }
}
