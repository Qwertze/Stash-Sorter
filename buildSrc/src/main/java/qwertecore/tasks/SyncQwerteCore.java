package qwertecore.tasks;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import qwertecore.CoreSync;

import java.io.IOException;
import java.nio.file.Path;

/** Copies qwerte-core's src/main/java and src/main/resources into the plugin (the same as tools/sync-qwerte-core.ps1). */
public abstract class SyncQwerteCore extends DefaultTask {
    @Input
    public abstract Property<String> getCorePath();

    @Internal
    public abstract DirectoryProperty getPluginDir();

    @Input
    public String getNow() {
        return Long.toString(System.nanoTime());
    }

    @TaskAction
    public void sync() throws IOException {
        Path core = Path.of(getCorePath().get());
        int n = CoreSync.sync(core, getPluginDir().get().getAsFile().toPath());
        getLogger().lifecycle("qwerte-core synced from " + core + ": " + n + " file(s) changed.");
    }
}
