package qwertecore.tasks;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import qwertecore.CoreSync;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Fails the build when the plugin's copy of qwerte-core differs from the library folder. When that folder is not there (a clone of
 * the plugin alone) it only warns: the copy that is committed is then built as it is.
 */
public abstract class CheckQwerteCore extends DefaultTask {
    /** The library folder (default {@code ../Qwertze/qwerte-core}); may not exist. */
    @Input
    public abstract Property<String> getCorePath();

    @Internal
    public abstract DirectoryProperty getPluginDir();

    /** Never up to date: the library folder is outside the project. */
    @Input
    public String getNow() {
        return Long.toString(System.nanoTime());
    }

    @TaskAction
    public void check() throws IOException {
        Path core = Path.of(getCorePath().get());
        CoreSync.Result r = CoreSync.check(core, getPluginDir().get().getAsFile().toPath());
        if (r.skipped()) {
            getLogger().warn("qwerte-core drift check SKIPPED: " + core + " is not there. Building the committed copy as it is.");
            return;
        }
        if (!r.ok()) {
            StringBuilder b = new StringBuilder("The plugin's copy of qwerte-core has drifted from " + core + ":\n");
            for (String p : r.problems()) b.append("  ").append(p).append('\n');
            b.append("Run tools/sync-qwerte-core.ps1 (or ./gradlew syncQwerteCore), review, and build again.");
            throw new GradleException(b.toString());
        }
    }
}
