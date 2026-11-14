package com.porter.qwerte.core.farm;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encryption at rest for the farmer (contract A9): AES-256-GCM, a fresh 12-byte nonce from {@link SecureRandom} on every
 * write, a 128-bit tag.
 *
 * <p>File: {@code "QSE1" | u8 kind | 4-byte keyId | 12-byte nonce | ciphertext + tag}. Kinds: 1 program, 2 location
 * store. {@code keyId} is the first 4 bytes of SHA-256(key), so a wrong key is told apart from a damaged file without
 * decrypting.
 *
 * <p>AAD (length-prefixed): {@code "QSE1" | u8 kind | u8 format | u8 idLength | id (ASCII)} plus the raw 32-byte
 * {@code programHash} for kind 1 (id = the farm id or {@code draft}); for kind 2 the id is the fixed {@code locations}.
 * The expected program hash comes from the sealed location store, which stops swapping or rolling back a program file.
 *
 * <p>Nothing here throws on bad input and no failure carries key material, plaintext or input text: results carry only a
 * closed code ({@code key_invalid}, {@code key_mismatch}, {@code seal_failed}). Files are never touched by this class.
 */
public final class Seal {
    public static final int KIND_PROGRAM = 1;
    public static final int KIND_LOCATIONS = 2;
    public static final int FORMAT = 1;
    public static final int KEY_LENGTH = 32;
    static final int NONCE_LENGTH = 12;
    static final int TAG_BITS = 128;
    private static final byte[] MAGIC = {'Q', 'S', 'E', '1'};
    private static final int HEADER = 4 + 1 + 4 + NONCE_LENGTH;
    private static final Pattern FARM_ID = Pattern.compile("^(f-[0-9a-f]{8}|draft)$");
    private static final String LOCATIONS_ID = "locations";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Seal() {
    }

    /** Outcome: {@code code} is null on success and {@code data} then holds the file (seal) or the plaintext (open). */
    public record Result(String code, byte[] data) {
        public boolean ok() {
            return code == null;
        }

        @Override
        public String toString() {
            return code == null ? "Result[ok]" : "Result[" + code + "]";
        }
    }

    private static Result fail(String code) {
        return new Result(code, null);
    }

    /** The first 4 bytes of SHA-256(key). */
    public static byte[] keyId(byte[] key) {
        return Arrays.copyOf(FarmRecording.sha256(key), 4);
    }

    /** The keyId stored in a sealed file, or null when the bytes are not a QSE1 file. */
    public static byte[] fileKeyId(byte[] file) {
        if (file == null || file.length < HEADER || !Arrays.equals(Arrays.copyOf(file, 4), MAGIC)) {
            return null;
        }
        return Arrays.copyOfRange(file, 5, 9);
    }

    public static Result sealProgram(byte[] key, String farmId, byte[] plain) {
        return seal0(key, KIND_PROGRAM, FORMAT, farmId, plain == null ? null : FarmRecording.sha256(plain), plain, null);
    }

    public static Result sealLocations(byte[] key, byte[] plain) {
        return seal0(key, KIND_LOCATIONS, FORMAT, LOCATIONS_ID, null, plain, null);
    }

    /** @param expectedProgramHash the raw 32-byte SHA-256 the location store holds for this farm */
    public static Result openProgram(byte[] key, String farmId, byte[] expectedProgramHash, byte[] file) {
        return open0(key, KIND_PROGRAM, FORMAT, farmId, expectedProgramHash, file);
    }

    public static Result openLocations(byte[] key, byte[] file) {
        return open0(key, KIND_LOCATIONS, FORMAT, LOCATIONS_ID, null, file);
    }

    // package-private so the known-answer test can fix the nonce and flip the format byte; production code cannot
    static Result seal0(byte[] key, int kind, int format, String id, byte[] programHash, byte[] plain, byte[] nonce) {
        try {
            if (key == null || key.length != KEY_LENGTH) {
                return fail("key_invalid");
            }
            if (plain == null || !validId(kind, id) || (kind == KIND_PROGRAM && (programHash == null || programHash.length != 32))) {
                return fail("seal_failed");
            }
            byte[] n = nonce;
            if (n == null) {
                n = new byte[NONCE_LENGTH];
                RANDOM.nextBytes(n);
            }
            byte[] kid = keyId(key);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, n));
            c.updateAAD(aad(kind, format, id, programHash));
            byte[] ct = c.doFinal(plain);
            byte[] out = new byte[HEADER + ct.length];
            System.arraycopy(MAGIC, 0, out, 0, 4);
            out[4] = (byte) kind;
            System.arraycopy(kid, 0, out, 5, 4);
            System.arraycopy(n, 0, out, 9, NONCE_LENGTH);
            System.arraycopy(ct, 0, out, HEADER, ct.length);
            return new Result(null, out);
        } catch (GeneralSecurityException | RuntimeException e) {
            return fail("seal_failed");
        }
    }

    static Result open0(byte[] key, int kind, int format, String id, byte[] expectedProgramHash, byte[] file) {
        try {
            if (key == null || key.length != KEY_LENGTH) {
                return fail("key_invalid");
            }
            if (file == null || file.length < HEADER + TAG_BITS / 8 || !Arrays.equals(Arrays.copyOf(file, 4), MAGIC)
                    || (file[4] & 0xFF) != kind || !validId(kind, id)
                    || (kind == KIND_PROGRAM && (expectedProgramHash == null || expectedProgramHash.length != 32))) {
                return fail("seal_failed");
            }
            if (!Arrays.equals(Arrays.copyOfRange(file, 5, 9), keyId(key))) {
                return fail("key_mismatch");
            }
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, Arrays.copyOfRange(file, 9, HEADER)));
            c.updateAAD(aad(kind, format, id, expectedProgramHash));
            byte[] plain = c.doFinal(file, HEADER, file.length - HEADER);
            if (kind == KIND_PROGRAM && !MessageDigest.isEqual(FarmRecording.sha256(plain), expectedProgramHash)) {
                return fail("seal_failed");
            }
            return new Result(null, plain);
        } catch (GeneralSecurityException | RuntimeException e) {
            return fail("seal_failed");
        }
    }

    private static boolean validId(int kind, String id) {
        if (kind == KIND_PROGRAM) {
            return id != null && FARM_ID.matcher(id).matches();
        }
        return kind == KIND_LOCATIONS && LOCATIONS_ID.equals(id);
    }

    static byte[] aad(int kind, int format, String id, byte[] programHash) {
        byte[] idb = id.getBytes(StandardCharsets.US_ASCII);
        int extra = kind == KIND_PROGRAM ? 32 : 0;
        byte[] a = new byte[4 + 1 + 1 + 1 + idb.length + extra];
        System.arraycopy(MAGIC, 0, a, 0, 4);
        a[4] = (byte) kind;
        a[5] = (byte) format;
        a[6] = (byte) idb.length;
        System.arraycopy(idb, 0, a, 7, idb.length);
        if (kind == KIND_PROGRAM) {
            System.arraycopy(programHash, 0, a, 7 + idb.length, 32);
        }
        return a;
    }
}
