package com.porter.qwertestash.farm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwerte.core.farm.FarmException;
import com.porter.qwerte.core.farm.FarmKey;
import com.porter.qwerte.core.farm.FarmRecording;
import com.porter.qwerte.core.farm.Seal;
import com.porter.qwerte.core.farm.UploadSession;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * The encrypted program and location stores (FARM A9, C4 to C6, D2): {@code farm/<farmId>.qse}, {@code farm/draft.qse} and
 * {@code farm/locations.qse}, sealed with the farm key by qwerte-core's {@link Seal}. A path is only ever built from a validated
 * id. The origin of a farm exists only inside the sealed location store and in memory.
 *
 * <p>Failures are {@link FarmException}s with a closed code ({@code key_*}, {@code seal_failed}, {@code store_full},
 * {@code program_missing}, {@code no_origin}). A file that cannot be opened is never deleted. Thread-safe (one lock); never call it
 * on the network thread (it touches the disk).
 */
public final class FarmStore {
    public static final int MAX_PROGRAMS = 32;
    public static final long MAX_BYTES = 64L * 1024 * 1024;
    public static final String DRAFT = "draft";
    private static final long STALE_TMP_MS = 10 * 60_000L;

    /** What the sealed location store keeps for a farm. The origin is absolute and never leaves this class's callers' memory. */
    public record Entry(String dim, int ox, int oy, int oz, long recordedAt, String programHash, String mode, int ticks, int loop,
                        int anchors, int unsupported) {
        @Override
        public String toString() {
            return "Entry[" + mode + " " + programHash.substring(0, 8) + "]"; // never the origin
        }
    }

    private final Path dir;
    private final String envKey;
    private final LongSupplier clock;
    private FarmKey.Loaded key;
    private Map<String, Entry> entries;

    public FarmStore(Path dir, String envKey, LongSupplier clock) {
        this.dir = dir;
        this.envKey = envKey;
        this.clock = clock;
    }

    public Path dir() {
        return dir;
    }

    // ---- key ---------------------------------------------------------------------------------------------------------

    /** The key code for the preflight: null when a usable key is loaded. Creates the key file on first use (never over existing data). */
    public synchronized String keyCode() {
        FarmKey.Loaded k = loadKey();
        return k.ok() ? null : k.code();
    }

    public synchronized String keySource() {
        FarmKey.Loaded k = loadKey();
        return k.ok() ? k.source() : null;
    }

    /** The 4-byte key id as hex (safe to print), or null. */
    public synchronized String keyIdHex() {
        FarmKey.Loaded k = loadKey();
        return k.ok() ? k.keyIdHex() : null;
    }

    private FarmKey.Loaded loadKey() {
        if (key != null && key.ok()) return key;
        key = FarmKey.load(dir, envKey);
        // The key file's creation makes the directory; with an environment key it is made here
        return key;
    }

    private byte[] needKey() {
        FarmKey.Loaded k = loadKey();
        if (!k.ok()) throw new FarmException(k.code());
        return k.key();
    }

    // ---- directory and files ---------------------------------------------------------------------------------------

    private static boolean posix(Path p) {
        return p.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    private void ensureDir() throws IOException {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            if (posix(dir)) Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            else Files.createDirectories(dir);
        }
        if (posix(dir)) {
            Set<PosixFilePermission> want = PosixFilePermissions.fromString("rwx------");
            if (!Files.getPosixFilePermissions(dir, LinkOption.NOFOLLOW_LINKS).equals(want)) Files.setPosixFilePermissions(dir, want);
            if (!Files.getPosixFilePermissions(dir, LinkOption.NOFOLLOW_LINKS).equals(want)) throw new FarmException("key_perms");
        }
    }

    static String name(String id) {
        if (id == null || !(id.equals(DRAFT) || UploadSession.farmIdOk(id))) throw new FarmException("bad_program");
        return id + ".qse";
    }

    private Path pathOf(String id) {
        return dir.resolve(name(id));
    }

    /** Writes {@code data} to {@code target.tmp} (owner-only, a fresh file) and returns the temporary path. Nothing is replaced yet. */
    private Path stage(Path target, byte[] data) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        try {
            if (posix(dir)) {
                try (var ch = Files.newByteChannel(tmp, EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                    ch.write(java.nio.ByteBuffer.wrap(data));
                }
            } else {
                Files.write(tmp, data, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        return tmp;
    }

    /** Moves a staged file over its target (atomic where the file system can). */
    private static void commit(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void writeAtomic(Path target, byte[] data) throws IOException {
        commit(stage(target, data), target);
    }

    /**
     * Replaces a program file and the location store together: both are staged first (a failure there changes nothing), then the program is
     * moved, then the locations; when the second move fails the first is rolled back (the old program put back, or the new one removed).
     */
    private void commitPair(Path progTarget, byte[] progBytes, byte[] locBytes) throws IOException {
        Path locTarget = dir.resolve("locations.qse");
        Path progTmp = null;
        Path locTmp = null;
        byte[] old = null;
        boolean moved = false;
        try {
            progTmp = stage(progTarget, progBytes);
            locTmp = stage(locTarget, locBytes);
            if (Files.exists(progTarget)) old = Files.readAllBytes(progTarget);
            commit(progTmp, progTarget);
            moved = true;
            commit(locTmp, locTarget);
        } catch (IOException | RuntimeException e) {
            if (moved) {
                try {
                    if (old != null) writeAtomic(progTarget, old);
                    else Files.deleteIfExists(progTarget);
                } catch (IOException | RuntimeException ignored) {
                    // the caller reports seal_failed; the stale file fails its hash check and is never trusted
                }
            }
            throw e;
        } finally {
            if (progTmp != null) Files.deleteIfExists(progTmp);
            if (locTmp != null) Files.deleteIfExists(locTmp);
        }
    }

    /** Removes stale {@code *.tmp} files older than 10 minutes (C6.10). */
    public synchronized int cleanTmp() {
        int n = 0;
        if (!Files.isDirectory(dir)) return 0;
        long now = clock.getAsLong();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.tmp")) {
            for (Path p : ds) {
                FileTime t = Files.getLastModifiedTime(p);
                if (now - t.toMillis() > STALE_TMP_MS && Files.deleteIfExists(p)) n++;
            }
        } catch (IOException | RuntimeException e) {
            // best effort
        }
        return n;
    }

    // ---- location store -------------------------------------------------------------------------------------------

    private Map<String, Entry> load() {
        if (entries != null) return entries;
        byte[] k = needKey();
        Path f = dir.resolve("locations.qse");
        Map<String, Entry> m = new LinkedHashMap<>();
        if (Files.exists(f)) {
            byte[] file;
            try {
                file = Files.readAllBytes(f);
            } catch (IOException e) {
                throw new FarmException("seal_failed");
            }
            Seal.Result r = Seal.openLocations(k, file);
            if (!r.ok()) throw new FarmException(r.code());
            try {
                JsonObject farms = JsonParser.parseString(new String(r.data(), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("farms");
                for (var e : farms.entrySet()) {
                    JsonObject o = e.getValue().getAsJsonObject();
                    JsonArray org = o.getAsJsonArray("origin");
                    m.put(e.getKey(), new Entry(o.get("dim").getAsString(), org.get(0).getAsInt(), org.get(1).getAsInt(), org.get(2).getAsInt(),
                        o.get("recordedAt").getAsLong(), o.get("programHash").getAsString(), o.get("mode").getAsString(), o.get("ticks").getAsInt(),
                        o.get("loop").getAsInt(), o.get("anchors").getAsInt(), o.get("unsupported").getAsInt()));
                }
            } catch (RuntimeException e) {
                throw new FarmException("seal_failed");
            }
        }
        entries = m;
        return m;
    }

    private byte[] sealedLocations(Map<String, Entry> m) {
        JsonObject root = new JsonObject();
        root.addProperty("v", 1);
        JsonObject farms = new JsonObject();
        for (var e : m.entrySet()) {
            Entry x = e.getValue();
            JsonObject o = new JsonObject();
            o.addProperty("dim", x.dim());
            JsonArray org = new JsonArray();
            org.add(x.ox());
            org.add(x.oy());
            org.add(x.oz());
            o.add("origin", org);
            o.addProperty("recordedAt", x.recordedAt());
            o.addProperty("programHash", x.programHash());
            o.addProperty("mode", x.mode());
            o.addProperty("ticks", x.ticks());
            o.addProperty("loop", x.loop());
            o.addProperty("anchors", x.anchors());
            o.addProperty("unsupported", x.unsupported());
            farms.add(e.getKey(), o);
        }
        root.add("farms", farms);
        Seal.Result r = Seal.sealLocations(needKey(), root.toString().getBytes(StandardCharsets.UTF_8));
        if (!r.ok()) throw new FarmException(r.code());
        return r.data();
    }

    private void saveLocations(Map<String, Entry> m) throws IOException {
        writeAtomic(dir.resolve("locations.qse"), sealedLocations(m));
    }

    /** A copy of the location entries (farm id or draft to entry). Throws {@link FarmException}. */
    public synchronized Map<String, Entry> entries() {
        return new LinkedHashMap<>(load());
    }

    public synchronized Entry entry(String id) {
        name(id);
        return load().get(id);
    }

    // ---- programs ---------------------------------------------------------------------------------------------------

    private void checkQuota(String id, int newSize) throws IOException {
        long total = 0;
        int count = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.qse")) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (n.equals("locations.qse")) continue;
                if (n.equals(id + ".qse")) continue; // replaced
                count++;
                total += Files.size(p);
            }
        }
        if (count >= MAX_PROGRAMS || total + newSize > MAX_BYTES) throw new FarmException("store_full");
        if (Files.getFileStore(dir).getUsableSpace() < 2L * newSize + 4096) throw new FarmException("store_full");
    }

    /**
     * Seals {@code plain} under {@code id} (a farm id or {@code draft}), then records its origin in the location store. The program
     * file and the location store are staged under temporary names first and moved together; a failure rolls back (see {@code commitPair}).
     */
    public synchronized void put(String id, byte[] plain, FarmRecording program, String dim, int ox, int oy, int oz) {
        try {
            name(id);
            byte[] k = needKey();
            ensureDir();
            Map<String, Entry> m = load();
            Seal.Result sealed = Seal.sealProgram(k, id, plain);
            if (!sealed.ok()) throw new FarmException(sealed.code());
            checkQuota(id, sealed.data().length);
            String hash = FarmRecording.sha256Hex(plain);
            var h = program.header();
            Entry e = new Entry(dim, ox, oy, oz, clock.getAsLong(), hash, h.mode(), h.ticks(), h.loop().end() - h.loop().start(), h.anchors().size(),
                h.unsupported().size());
            Map<String, Entry> next = new LinkedHashMap<>(m);
            next.put(id, e);
            commitPair(pathOf(id), sealed.data(), sealedLocations(next));
            entries = next;
        } catch (IOException e) {
            throw new FarmException("seal_failed");
        }
    }

    /** The origin entry and the plain program bytes of one farm, read under one lock hold (one commit). */
    public record Opened(Entry entry, byte[] plain) {
    }

    /** The plain program bytes of a farm; {@code program_missing} when there is none, a seal code when it cannot be opened. */
    public synchronized byte[] open(String id) {
        return openWithEntry(id).plain();
    }

    /**
     * The entry (origin, hash) and the plain program bytes of a farm, atomically: both come from the same commit of {@link #put}, so the
     * origin and the program always belong together even if an upload replaces the farm while a session starts.
     * {@code program_missing} when there is none, a seal code when it cannot be opened.
     */
    public synchronized Opened openWithEntry(String id) {
        name(id);
        Entry e = load().get(id);
        Path f = pathOf(id);
        if (e == null || !Files.exists(f)) throw new FarmException("program_missing");
        byte[] file;
        try {
            file = Files.readAllBytes(f);
        } catch (IOException ex) {
            throw new FarmException("program_missing");
        }
        Seal.Result r = Seal.openProgram(needKey(), id, HexFormat.of().parseHex(e.programHash()), file);
        if (!r.ok()) throw new FarmException(r.code());
        return new Opened(e, r.data());
    }

    /** Binds the draft to a farm: decrypt, re-seal under the farm id (same hash), then delete the draft (D: attach re-seals). */
    public synchronized void attach(String farmId, String draftHash) {
        try {
            if (farmId == null || !UploadSession.farmIdOk(farmId) || farmId.equals(DRAFT)) throw new FarmException("bad_program");
            Map<String, Entry> m = load();
            Entry d = m.get(DRAFT);
            if (d == null) throw new FarmException("program_missing");
            if (draftHash != null && !draftHash.equals(d.programHash())) throw new FarmException("bad_hash");
            byte[] plain = open(DRAFT);
            byte[] k = needKey();
            Seal.Result sealed = Seal.sealProgram(k, farmId, plain);
            if (!sealed.ok()) throw new FarmException(sealed.code());
            checkQuota(farmId, sealed.data().length);
            Map<String, Entry> next = new LinkedHashMap<>(m);
            next.put(farmId, d);
            next.remove(DRAFT);
            commitPair(pathOf(farmId), sealed.data(), sealedLocations(next));
            entries = next;
            Files.deleteIfExists(pathOf(DRAFT)); // only after the new file and the new location store are written
        } catch (IOException e) {
            throw new FarmException("seal_failed");
        }
    }

    /** Deletes a farm's program and its location. Deleting what is not there is fine. */
    public synchronized void forget(String id) {
        try {
            name(id);
            Map<String, Entry> m = load();
            if (m.containsKey(id)) {
                Map<String, Entry> next = new LinkedHashMap<>(m);
                next.remove(id);
                saveLocations(next);
                entries = next;
            }
            Files.deleteIfExists(pathOf(id));
        } catch (IOException e) {
            throw new FarmException("seal_failed");
        }
    }

    /** True when a program file exists for the farm (without opening it). */
    public synchronized boolean hasFile(String id) {
        name(id);
        return Files.exists(pathOf(id));
    }
}
