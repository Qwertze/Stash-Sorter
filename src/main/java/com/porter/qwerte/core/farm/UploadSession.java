package com.porter.qwerte.core.farm;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * The pure parts of the upload protocol (contract C3, C5, C6): id patterns, the {@code begin} numbers, chunk lengths, the
 * 4-chunk window, duplicates, and the final hash, inflate and check pipeline. No I/O, no clock (the caller passes time),
 * no threads: the plugin's handler copies bytes on the network thread and drives one session from its worker.
 * Every failure is a closed code; no message carries input text.
 */
public final class UploadSession {
    public static final int MAX_PACKED = Packing.MAX_PACKED;
    public static final int MAX_CHUNK = 24_576;
    public static final int MAX_CHUNKS = 86;
    public static final int WINDOW = 4;
    public static final int TIMEOUT_SECONDS = 60;

    public static final Pattern UPLOAD_ID = Pattern.compile("^[0-9a-f]{16}$");
    public static final Pattern FARM_ID = Pattern.compile("^(f-[0-9a-f]{8}|draft)$");
    public static final Pattern DIM = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]+$");
    public static final Pattern HASH = Pattern.compile("^[0-9a-f]{64}$");

    public static boolean uploadIdOk(String s) {
        return s != null && s.length() <= 16 && UPLOAD_ID.matcher(s).matches();
    }

    public static boolean farmIdOk(String s) {
        return s != null && s.length() <= 10 && FARM_ID.matcher(s).matches();
    }

    public static boolean dimOk(String s) {
        return s != null && s.length() <= 64 && DIM.matcher(s).matches();
    }

    public static boolean hashOk(String s) {
        return s != null && s.length() == 64 && HASH.matcher(s).matches();
    }

    /** The {@code begin} numbers: null when fine, else {@code too_big} or {@code bad_program}. Nothing is allocated. */
    public static String checkBegin(long totalBytes, long chunkSize, long chunks) {
        if (totalBytes > MAX_PACKED) {
            return "too_big";
        }
        if (totalBytes < 1 || chunkSize < 1 || chunkSize > MAX_CHUNK || chunks < 1 || chunks > MAX_CHUNKS
                || chunks != (totalBytes + chunkSize - 1) / chunkSize) {
            return "bad_program";
        }
        return null;
    }

    /** Expected length of chunk {@code index}: chunkSize, or the remainder for the last one. */
    public static int expectedLength(long totalBytes, long chunkSize, long chunks, int index) {
        if (checkBegin(totalBytes, chunkSize, chunks) != null || index < 0 || index >= chunks) {
            throw new FarmException("bad_program");
        }
        return index == chunks - 1 ? (int) (totalBytes - (chunks - 1) * chunkSize) : (int) chunkSize;
    }

    /** What the handler should answer to a chunk. */
    public enum Outcome {
        /** Accepted (or already held with the same bytes): ack the lowest missing index. */
        ACK,
        /** Outside the window: dropped, ack repeats the lowest missing index. */
        DROPPED
    }

    private final String uploadId;
    private final byte[] packed;
    private final int chunkSize;
    private final int chunks;
    private final boolean[] have;
    private final String sha256Packed;
    private final String programHash;
    private int next;
    private long lastActivityMs;

    /**
     * @throws FarmException with the code of {@link #checkBegin} or {@code bad_hash} for a malformed hash or id
     */
    public UploadSession(String uploadId, long totalBytes, long chunkSize, long chunks, String sha256Packed,
                         String programHash, long nowMs) {
        String c = checkBegin(totalBytes, chunkSize, chunks);
        if (c != null) {
            throw new FarmException(c);
        }
        if (!uploadIdOk(uploadId)) {
            throw new FarmException("bad_program");
        }
        if (!hashOk(sha256Packed) || !hashOk(programHash)) {
            throw new FarmException("bad_hash");
        }
        this.uploadId = uploadId;
        this.packed = new byte[(int) totalBytes]; // allocated only after every number passed
        this.chunkSize = (int) chunkSize;
        this.chunks = (int) chunks;
        this.have = new boolean[this.chunks];
        this.sha256Packed = sha256Packed;
        this.programHash = programHash;
        this.lastActivityMs = nowMs;
    }

    public String uploadId() {
        return uploadId;
    }

    /** The lowest missing chunk index. */
    public int next() {
        return next;
    }

    public boolean timedOut(long nowMs) {
        return nowMs - lastActivityMs > TIMEOUT_SECONDS * 1000L;
    }

    /**
     * @throws FarmException {@code bad_program} (index at or above the count, a wrong length), {@code bad_hash} (a repeat
     *                       of a held chunk with different bytes)
     */
    public Outcome chunk(int index, byte[] data, long nowMs) {
        if (index < 0 || index >= chunks || data == null) {
            throw new FarmException("bad_program");
        }
        int expected = expectedLength(packed.length, chunkSize, chunks, index);
        if (data.length != expected || data.length < 1) {
            throw new FarmException("bad_program");
        }
        int off = index * chunkSize;
        if (have[index]) {
            if (!Arrays.equals(Arrays.copyOfRange(packed, off, off + expected), data)) {
                throw new FarmException("bad_hash");
            }
            lastActivityMs = nowMs;
            return Outcome.ACK;
        }
        if (index >= next + WINDOW) {
            return Outcome.DROPPED;
        }
        System.arraycopy(data, 0, packed, off, expected);
        have[index] = true;
        while (next < chunks && have[next]) {
            next++;
        }
        lastActivityMs = nowMs;
        return Outcome.ACK;
    }

    public boolean complete() {
        return next == chunks;
    }

    /** The upload's verified, inflated and checked program. */
    public record Finished(byte[] plain, FarmRecording program) {
    }

    /**
     * {@code end}: every chunk must be here ({@code bad_program}); the SHA-256 of the packed bytes must equal
     * {@code sha256Packed} ({@code bad_hash}); inflate with the 8 MiB counter ({@code too_big}); the SHA-256 of the plain
     * bytes must equal {@code programHash} ({@code bad_hash}); then {@link ProgramChecker#checkPlain}.
     */
    public Finished finish() {
        if (!complete()) {
            throw new FarmException("bad_program");
        }
        if (!MessageDigest.isEqual(FarmRecording.sha256Hex(packed).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                sha256Packed.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            throw new FarmException("bad_hash");
        }
        byte[] plain = Packing.unpack(packed);
        if (!FarmRecording.sha256Hex(plain).equals(programHash)) {
            throw new FarmException("bad_hash");
        }
        return new Finished(plain, ProgramChecker.checkPlain(plain));
    }
}
