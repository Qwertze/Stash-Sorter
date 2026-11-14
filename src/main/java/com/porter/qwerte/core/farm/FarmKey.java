package com.porter.qwerte.core.farm;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Set;

/**
 * The farm key (contract A9, Q1): a random 32-byte key in {@code farm/farm.key}, or the environment override
 * {@code QWERTE_FARM_KEY}.
 *
 * <ul>
 *   <li>The override is base64, trimmed, and must decode to exactly 32 bytes, else {@code key_invalid}; it never falls
 *       back to the file. An empty or absent value means "not set".</li>
 *   <li>The file is created atomically with {@code CREATE_NEW} and mode 0600 set at creation; the directory is 0700.
 *       Both modes are read back; if either cannot be set or verified (a non-POSIX file system such as Windows, a mount
 *       that ignores modes, a group- or world-accessible file or directory, a symlink) the result is {@code key_perms}
 *       and a key file created by this call is removed again. Fail closed: on Windows only the override works.</li>
 *   <li>A key is never invented over existing data: when any {@code *.qse} file exists and no key is available the
 *       result is {@code key_missing}.</li>
 *   <li>A key file that is not exactly 32 bytes is {@code key_invalid}.</li>
 * </ul>
 * The file's owner is not checked (only the modes): a key file owned by another user with mode 0600 is accepted, as the
 * plugin runs as the owner of its own directory. Nothing here logs, and no result, exception or {@code toString} carries the key or the override text.
 */
public final class FarmKey {
    public static final String ENV_NAME = "QWERTE_FARM_KEY";
    public static final String FILE_NAME = "farm.key";
    private static final SecureRandom RANDOM = new SecureRandom();

    private FarmKey() {
    }

    /** {@code code} is null on success; then {@code key} (32 bytes) and {@code source} ("env" or "file") are set. */
    public record Loaded(String code, byte[] key, String source) {
        public boolean ok() {
            return code == null;
        }

        /** The 4-byte key id as 8 hex characters, or null. Safe to print. */
        public String keyIdHex() {
            return key == null ? null : HexFormat.of().formatHex(Seal.keyId(key));
        }

        @Override
        public String toString() {
            return code == null ? "Loaded[" + source + ", id " + keyIdHex() + "]" : "Loaded[" + code + "]";
        }
    }

    private static Loaded fail(String code) {
        return new Loaded(code, null, null);
    }

    /**
     * @param farmDir  {@code plugins/qwerte-stash/farm}
     * @param envValue the value of {@code QWERTE_FARM_KEY}, or null when the variable is not set
     */
    public static Loaded load(Path farmDir, String envValue) {
        if (envValue != null && !envValue.isEmpty()) {
            return fromEnv(envValue);
        }
        try {
            return fromFile(farmDir, true);
        } catch (RuntimeException e) {
            return fail("key_perms");
        }
    }

    static Loaded fromEnv(String envValue) {
        try {
            byte[] k = Base64.getDecoder().decode(envValue.trim());
            if (k.length != Seal.KEY_LENGTH) {
                return fail("key_invalid");
            }
            return new Loaded(null, k, "env");
        } catch (IllegalArgumentException e) {
            return fail("key_invalid");
        }
    }

    private static final Set<PosixFilePermission> OWNER_RW = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> OWNER_RWX = PosixFilePermissions.fromString("rwx------");

    private static Loaded fromFile(Path dir, boolean mayCreate) {
        if (!dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return fail("key_perms"); // no POSIX modes: cannot prove the file is private
        }
        Path file = dir.resolve(FILE_NAME);
        try {
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                return readExisting(dir, file);
            }
            if (hasQse(dir)) {
                return fail("key_missing");
            }
            if (!mayCreate) {
                return fail("key_missing");
            }
            if (!ensureDir(dir)) {
                return fail("key_perms");
            }
            byte[] key = new byte[Seal.KEY_LENGTH];
            RANDOM.nextBytes(key);
            boolean created = false;
            try (var ch = Files.newByteChannel(file, EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                    PosixFilePermissions.asFileAttribute(OWNER_RW))) {
                created = true;
                ByteBuffer buf = ByteBuffer.wrap(key);
                while (buf.hasRemaining()) {
                    ch.write(buf);
                }
                if (ch instanceof java.nio.channels.FileChannel fc) {
                    fc.force(true); // durable before anything is sealed with this key
                }
            } catch (FileAlreadyExistsException e) {
                return fromFile(dir, false); // another process won the race: use its key
            } catch (IOException e) {
                if (created) {
                    deleteQuietly(file);
                }
                return fail("key_perms");
            }
            if (!modesOk(dir, file)) {
                deleteQuietly(file);
                return fail("key_perms");
            }
            return new Loaded(null, key, "file");
        } catch (IOException | RuntimeException e) {
            return fail("key_perms");
        }
    }

    private static Loaded readExisting(Path dir, Path file) throws IOException {
        if (!modesOk(dir, file)) {
            return fail("key_perms");
        }
        // the creator of a key may not have finished writing: a short or empty file is retried briefly (the loser of a
        // creation race); if it stays short it is key_invalid and is never touched (an interrupted write is the owner's call)
        if (Files.size(file) > 1024) {
            return fail("key_invalid");
        }
        byte[] k = Files.readAllBytes(file);
        for (int i = 0; i < SHORT_FILE_RETRIES && k.length != Seal.KEY_LENGTH && k.length < Seal.KEY_LENGTH; i++) {
            try {
                Thread.sleep(SHORT_FILE_WAIT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            k = Files.readAllBytes(file);
        }
        if (k.length != Seal.KEY_LENGTH) {
            return fail("key_invalid");
        }
        return new Loaded(null, k, "file");
    }

    static final int SHORT_FILE_RETRIES = 20;
    static final long SHORT_FILE_WAIT_MS = 25;

    /** Creates the directory with 0700 and makes sure an existing one has it; true when the mode reads back. */
    private static boolean ensureDir(Path dir) throws IOException {
        if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return false; // a symlink (or a file) in place of the directory: never chmod through it
        }
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(OWNER_RWX));
        }
        Files.setPosixFilePermissions(dir, OWNER_RWX);
        return Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)
                && Files.getPosixFilePermissions(dir, LinkOption.NOFOLLOW_LINKS).equals(OWNER_RWX);
    }

    /** Reads both modes back: the file must be a regular file (no symlink) owner-only, the directory owner-only. */
    private static boolean modesOk(Path dir, Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        Set<PosixFilePermission> f = Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS);
        Set<PosixFilePermission> d = Files.getPosixFilePermissions(dir, LinkOption.NOFOLLOW_LINKS);
        return privateOwnerOnly(f) && privateOwnerOnly(d);
    }

    /** True when no group or other bit is set (0600, 0400, 0700 ... all pass; 0640 or 0644 do not). */
    private static boolean privateOwnerOnly(Set<PosixFilePermission> p) {
        for (PosixFilePermission x : p) {
            if (!x.name().startsWith("OWNER")) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasQse(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.qse")) {
            return ds.iterator().hasNext();
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // nothing more we can do; the caller reports key_perms
        }
    }
}
