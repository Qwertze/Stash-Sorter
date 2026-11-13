package qwertecore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The synced copy of the shared qwerte-core library (user decision 2026-11-12): copies {@code qwerte-core/src/main/java} and
 * {@code src/main/resources} into this project and tells whether the copy has drifted. Plain java.nio only, so the build tasks and
 * the unit tests use the same code. Texts are compared with line endings normalised (a checkout may convert them).
 *
 * <p>Only the files under {@link #PREFIX} of the plugin are the copy's: nothing else in {@code src/main} is touched or compared.
 */
public final class CoreSync {
    private CoreSync() {
    }

    /** The package path of the library, the same in the library and in the plugin. */
    public static final String PREFIX = "com/porter/qwerte/core";
    /** The source roots that are copied, relative to the library and to the plugin. */
    public static final List<String> ROOTS = List.of("src/main/java", "src/main/resources");

    /** What a check found. {@code skipped} is set when the library folder is not there (a clone of the plugin alone). */
    public record Result(boolean skipped, List<String> problems) {
        public boolean ok() {
            return skipped || problems.isEmpty();
        }
    }

    /** Compares the library folder with the plugin. A missing library folder is a skip, not a failure. */
    public static Result check(Path core, Path plugin) throws IOException {
        if (core == null || !Files.isDirectory(core.resolve("src/main"))) return new Result(true, List.of());
        List<String> problems = new ArrayList<>();
        for (String root : ROOTS) {
            Path from = core.resolve(root).resolve(PREFIX), to = plugin.resolve(root).resolve(PREFIX);
            TreeSet<String> names = new TreeSet<>(list(from));
            TreeSet<String> have = new TreeSet<>(list(to));
            for (String n : names) {
                if (!have.contains(n)) problems.add("missing in the plugin: " + root + "/" + PREFIX + "/" + n);
                else if (!sameFile(from.resolve(n), to.resolve(n))) problems.add("differs: " + root + "/" + PREFIX + "/" + n);
            }
            for (String n : have) if (!names.contains(n)) problems.add("not in the library any more: " + root + "/" + PREFIX + "/" + n);
        }
        return new Result(false, problems);
    }

    /** Makes the plugin's copy equal to the library (files are copied; stale files of the copy are deleted). @return how many files changed */
    public static int sync(Path core, Path plugin) throws IOException {
        if (core == null || !Files.isDirectory(core.resolve("src/main"))) throw new IOException("the library folder is not there: " + core);
        // Never wipes the copy: a library folder without the package (a half-moved folder, a wrong path) is refused before anything is touched
        for (String root : ROOTS) {
            Path from = core.resolve(root).resolve(PREFIX);
            if (!Files.isDirectory(from)) throw new IOException("the library has no " + root + "/" + PREFIX + " folder: " + from);
        }
        int changed = 0;
        for (String root : ROOTS) {
            Path from = core.resolve(root).resolve(PREFIX), to = plugin.resolve(root).resolve(PREFIX);
            TreeSet<String> names = new TreeSet<>(list(from));
            for (String n : list(to)) {
                if (!names.contains(n)) {
                    Files.delete(to.resolve(n));
                    changed++;
                }
            }
            for (String n : names) {
                Path src = from.resolve(n), dst = to.resolve(n);
                if (Files.exists(dst) && sameFile(src, dst)) continue;
                Files.createDirectories(dst.getParent());
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                changed++;
            }
        }
        return changed;
    }

    /** Relative file names (forward slashes) under a folder; empty when it is not there. */
    static List<String> list(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).map(p -> dir.relativize(p).toString().replace('\\', '/')).sorted().toList();
        }
    }

    static boolean sameFile(Path a, Path b) throws IOException {
        byte[] x = Files.readAllBytes(a), y = Files.readAllBytes(b);
        if (java.util.Arrays.equals(x, y)) return true;
        // Text files differ only by line ending when a checkout converted them
        return normalise(x).equals(normalise(y));
    }

    private static String normalise(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
