package com.porter.qwerte.core.farm;

import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * zlib packing of plain program bytes (contract A1, A8): {@link Deflater} default level with the zlib wrapper. The
 * compressed stream is not canonical, so hashes are always over the plain bytes. Inflating carries a byte counter
 * (the 8 MiB zip-bomb guard).
 */
public final class Packing {
    public static final int MAX_PACKED = 2 * 1024 * 1024;
    public static final int MAX_PLAIN = FarmRecording.MAX_PLAIN_BYTES;

    private Packing() {
    }

    public static byte[] pack(byte[] plain) {
        if (plain == null) {
            throw new FarmException("bad_program");
        }
        Deflater d = new Deflater();
        try {
            d.setInput(plain);
            d.finish();
            ByteArrayOutputStream o = new ByteArrayOutputStream(Math.max(64, plain.length / 2));
            byte[] buf = new byte[8192];
            while (!d.finished()) {
                int n = d.deflate(buf);
                o.write(buf, 0, n);
                if (o.size() > MAX_PACKED) {
                    throw new FarmException("too_big");
                }
            }
            return o.toByteArray();
        } finally {
            d.end();
        }
    }

    /**
     * Inflates with a counter: more than {@code maxPlain} output bytes is {@code too_big} (the inflater stops as soon as
     * the limit is crossed, it never allocates the full bomb); a corrupt or truncated stream, or trailing bytes after
     * the stream, is {@code bad_program}.
     */
    public static byte[] unpack(byte[] packed, int maxPlain) {
        if (packed == null || packed.length == 0 || maxPlain < 1) {
            throw new FarmException("bad_program");
        }
        if (packed.length > MAX_PACKED) {
            throw new FarmException("too_big");
        }
        Inflater inf = new Inflater();
        try {
            inf.setInput(packed);
            ByteArrayOutputStream o = new ByteArrayOutputStream(Math.max(16, Math.min(maxPlain, packed.length * 4)));
            byte[] buf = new byte[8192];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && !inf.finished()) {
                    throw new FarmException("bad_program"); // truncated stream or a dictionary request
                }
                if (o.size() + n > maxPlain) {
                    throw new FarmException("too_big");
                }
                o.write(buf, 0, n);
            }
            if (inf.getRemaining() != 0) {
                throw new FarmException("bad_program");
            }
            return o.toByteArray();
        } catch (DataFormatException e) {
            throw new FarmException("bad_program");
        } finally {
            inf.end();
        }
    }

    public static byte[] unpack(byte[] packed) {
        return unpack(packed, MAX_PLAIN);
    }
}
