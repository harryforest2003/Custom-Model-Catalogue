package dev.custommodels.cmv.explorer.res;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Deflate for the geometry cache.
 *
 * <p>Model JSON is highly repetitive — the same face and texture keys over and
 * over — so it compresses about seven to one. On the pack this was measured
 * against, every geometry file in the pack is 175 MB of text and 26 MB
 * deflated, which is the difference between a cache that can be kept and one
 * that cannot.</p>
 *
 * <p>{@link Deflater#BEST_SPEED} rather than the default: the size difference
 * is a few per cent and this runs once per model file during a scan that is
 * already the slowest thing the mod does.</p>
 */
final class Deflate {

    private Deflate() {
    }

    static byte[] deflate(String text) {
        byte[] input = text.getBytes(StandardCharsets.UTF_8);
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try {
            deflater.setInput(input);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, input.length / 4));
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    static String inflate(byte[] deflated) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(deflated);
            ByteArrayOutputStream out = new ByteArrayOutputStream(deflated.length * 4);
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int read = inflater.inflate(buffer);
                if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (DataFormatException e) {
            throw new IllegalStateException("Geometry cache entry is corrupt", e);
        } finally {
            inflater.end();
        }
    }
}
