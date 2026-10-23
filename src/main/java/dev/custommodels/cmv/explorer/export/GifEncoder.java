package dev.custommodels.cmv.explorer.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * A GIF89a encoder, written here because nothing on the classpath can produce
 * an animated GIF with transparency.
 *
 * <p>Minecraft ships no image encoder beyond PNG, and pulling in ImageIO's GIF
 * writer would mean initialising AWT inside a GLFW application, which on macOS
 * is a good way to deadlock the window. Two hundred lines of well-understood
 * format is the cheaper option.</p>
 *
 * <h2>Colour</h2>
 *
 * <p>GIF allows 256 colours per image, so the frames have to be quantised. One
 * palette is built across every frame rather than one per frame: a shared
 * palette is what stops a rotating model from shimmering as its colours are
 * re-chosen each frame, and Minecraft textures use few enough distinct colours
 * that the loss is usually invisible.</p>
 *
 * <p>Index 0 is reserved for transparency, which is what lets a preview sit on
 * whatever background it is pasted onto instead of carrying a box of grey with
 * it. GIF transparency is one bit, so partly-transparent edge pixels have to
 * become either fully opaque or fully clear; they are composited against the
 * supplied matte colour first so that antialiased edges do not turn into a
 * jagged fringe.</p>
 */
public final class GifEncoder {

    /** Alpha at or above this survives; below it becomes transparent. */
    private static final int ALPHA_THRESHOLD = 128;

    private static final int MAX_COLOURS = 255;   // index 0 is transparency

    private GifEncoder() {
    }

    /**
     * Encodes an animation.
     *
     * @param frames      ARGB pixels, one array per frame, all the same size
     * @param width       frame width
     * @param height      frame height
     * @param delayMillis delay between frames
     * @param matte       colour partly-transparent pixels are composited onto
     * @param out         stream written to; not closed
     */
    public static void encode(List<int[]> frames, int width, int height,
                              int delayMillis, int matte, OutputStream out)
            throws IOException {
        if (frames.isEmpty() || width <= 0 || height <= 0) {
            throw new IOException("Nothing to encode");
        }

        Palette palette = Palette.build(frames, matte);

        out.write("GIF89a".getBytes(java.nio.charset.StandardCharsets.US_ASCII));

        // Logical screen descriptor: global colour table, 8 bits per channel,
        // 256 entries.
        writeShort(out, width);
        writeShort(out, height);
        out.write(0xF7);
        out.write(0);        // background colour index
        out.write(0);        // pixel aspect ratio, 0 means unspecified

        palette.writeTable(out);

        // Netscape extension: loop forever. Without it a viewer plays once.
        out.write(0x21);
        out.write(0xFF);
        out.write(11);
        out.write("NETSCAPE2.0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        out.write(3);
        out.write(1);
        writeShort(out, 0);  // repeat count, 0 means forever
        out.write(0);

        int delayHundredths = Math.max(2, Math.round(delayMillis / 10.0F));
        for (int[] frame : frames) {
            // Graphic control extension: restore to background between frames,
            // transparency enabled, index 0 transparent.
            out.write(0x21);
            out.write(0xF9);
            out.write(4);
            out.write(0x09);          // disposal 2 (restore background) | transparent
            writeShort(out, delayHundredths);
            out.write(0);             // transparent colour index
            out.write(0);

            out.write(0x2C);          // image descriptor
            writeShort(out, 0);
            writeShort(out, 0);
            writeShort(out, width);
            writeShort(out, height);
            out.write(0);             // no local colour table, not interlaced

            byte[] indices = palette.map(frame);
            writeLzw(out, indices, 8);
        }
        out.write(0x3B);              // trailer
    }

    private static void writeShort(OutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    // ------------------------------------------------------------- palette

    /**
     * A shared colour table built by median cut over the frames' opaque pixels.
     *
     * <p>Median cut repeatedly splits the box of remaining colours along its
     * longest channel at the median, which spends palette entries where the
     * image actually varies. A uniform grid would waste most of its entries on
     * colours no frame contains.</p>
     */
    private static final class Palette {

        private final int[] colours;
        private final int[] lookup = new int[32768];   // 5 bits per channel

        private Palette(int[] colours) {
            this.colours = colours;
            java.util.Arrays.fill(lookup, -1);
        }

        static Palette build(List<int[]> frames, int matte) {
            // Histogram at 5 bits per channel: fine enough that Minecraft
            // textures keep their distinct colours, coarse enough that the
            // median cut runs over thousands of buckets rather than millions
            // of pixels.
            int[] counts = new int[32768];
            for (int[] frame : frames) {
                for (int argb : frame) {
                    if ((argb >>> 24) < ALPHA_THRESHOLD) {
                        continue;
                    }
                    counts[quantise(composite(argb, matte))]++;
                }
            }

            List<Integer> present = new ArrayList<>();
            for (int i = 0; i < counts.length; i++) {
                if (counts[i] > 0) {
                    present.add(i);
                }
            }
            if (present.isEmpty()) {
                return new Palette(new int[]{0xFF000000});
            }

            List<int[]> boxes = new ArrayList<>();
            boxes.add(toArray(present));
            while (boxes.size() < MAX_COLOURS) {
                int widest = -1;
                int widestSpan = 0;
                int widestChannel = 0;
                for (int i = 0; i < boxes.size(); i++) {
                    int[] span = spanOf(boxes.get(i));
                    if (span[0] > widestSpan && boxes.get(i).length > 1) {
                        widestSpan = span[0];
                        widest = i;
                        widestChannel = span[1];
                    }
                }
                if (widest < 0) {
                    break;
                }
                int[] box = boxes.remove(widest);
                int channel = widestChannel;
                Integer[] boxed = new Integer[box.length];
                for (int i = 0; i < box.length; i++) {
                    boxed[i] = box[i];
                }
                java.util.Arrays.sort(boxed,
                        (a, b) -> Integer.compare(channelOf(a, channel), channelOf(b, channel)));
                int middle = boxed.length / 2;
                int[] low = new int[middle];
                int[] high = new int[boxed.length - middle];
                for (int i = 0; i < middle; i++) {
                    low[i] = boxed[i];
                }
                for (int i = middle; i < boxed.length; i++) {
                    high[i - middle] = boxed[i];
                }
                boxes.add(low);
                boxes.add(high);
            }

            int[] colours = new int[boxes.size()];
            for (int i = 0; i < boxes.size(); i++) {
                colours[i] = averageOf(boxes.get(i), counts);
            }
            return new Palette(colours);
        }

        /** Composites over the matte, since GIF has no partial transparency. */
        private static int composite(int argb, int matte) {
            int alpha = argb >>> 24;
            if (alpha == 255) {
                return argb & 0xFFFFFF;
            }
            int r = ((argb >> 16) & 0xFF) * alpha + ((matte >> 16) & 0xFF) * (255 - alpha);
            int g = ((argb >> 8) & 0xFF) * alpha + ((matte >> 8) & 0xFF) * (255 - alpha);
            int b = (argb & 0xFF) * alpha + (matte & 0xFF) * (255 - alpha);
            return ((r / 255) << 16) | ((g / 255) << 8) | (b / 255);
        }

        private static int quantise(int rgb) {
            return (((rgb >> 19) & 0x1F) << 10)
                    | (((rgb >> 11) & 0x1F) << 5)
                    | ((rgb >> 3) & 0x1F);
        }

        private static int channelOf(int bucket, int channel) {
            return switch (channel) {
                case 0 -> (bucket >> 10) & 0x1F;
                case 1 -> (bucket >> 5) & 0x1F;
                default -> bucket & 0x1F;
            };
        }

        /** Longest channel span in a box, and which channel it was. */
        private static int[] spanOf(int[] box) {
            int[] min = {31, 31, 31};
            int[] max = {0, 0, 0};
            for (int bucket : box) {
                for (int channel = 0; channel < 3; channel++) {
                    int value = channelOf(bucket, channel);
                    min[channel] = Math.min(min[channel], value);
                    max[channel] = Math.max(max[channel], value);
                }
            }
            int best = 0;
            int bestSpan = max[0] - min[0];
            for (int channel = 1; channel < 3; channel++) {
                if (max[channel] - min[channel] > bestSpan) {
                    bestSpan = max[channel] - min[channel];
                    best = channel;
                }
            }
            return new int[]{bestSpan, best};
        }

        /** Population-weighted mean, so common colours sit where they belong. */
        private static int averageOf(int[] box, int[] counts) {
            long r = 0, g = 0, b = 0, total = 0;
            for (int bucket : box) {
                long weight = counts[bucket];
                r += (((bucket >> 10) & 0x1F) << 3) * weight;
                g += (((bucket >> 5) & 0x1F) << 3) * weight;
                b += ((bucket & 0x1F) << 3) * weight;
                total += weight;
            }
            if (total == 0) {
                return 0;
            }
            return (int) ((r / total) << 16 | (g / total) << 8 | (b / total));
        }

        private static int[] toArray(List<Integer> values) {
            int[] out = new int[values.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = values.get(i);
            }
            return out;
        }

        void writeTable(OutputStream out) throws IOException {
            // Index 0 is the transparent slot; its colour is never shown.
            out.write(0);
            out.write(0);
            out.write(0);
            for (int i = 0; i < 255; i++) {
                int colour = i < colours.length ? colours[i] : 0;
                out.write((colour >> 16) & 0xFF);
                out.write((colour >> 8) & 0xFF);
                out.write(colour & 0xFF);
            }
        }

        /** Maps a frame to palette indices, one byte per pixel. */
        byte[] map(int[] frame) {
            byte[] indices = new byte[frame.length];
            for (int i = 0; i < frame.length; i++) {
                int argb = frame[i];
                if ((argb >>> 24) < ALPHA_THRESHOLD) {
                    indices[i] = 0;
                    continue;
                }
                int bucket = quantise(argb & 0xFFFFFF);
                int nearest = lookup[bucket];
                if (nearest < 0) {
                    nearest = nearestTo(argb);
                    lookup[bucket] = nearest;
                }
                indices[i] = (byte) (nearest + 1);
            }
            return indices;
        }

        private int nearestTo(int argb) {
            int r = (argb >> 16) & 0xFF;
            int g = (argb >> 8) & 0xFF;
            int b = argb & 0xFF;
            int best = 0;
            int bestDistance = Integer.MAX_VALUE;
            for (int i = 0; i < colours.length; i++) {
                int dr = r - ((colours[i] >> 16) & 0xFF);
                int dg = g - ((colours[i] >> 8) & 0xFF);
                int db = b - (colours[i] & 0xFF);
                int distance = dr * dr + dg * dg + db * db;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = i;
                }
            }
            return best;
        }
    }

    // ------------------------------------------------------------------ LZW

    /**
     * GIF's variable-width LZW, written as the format specifies it.
     *
     * <p>Codes grow from {@code minimumCodeSize + 1} bits, the dictionary is
     * cleared when it fills, and the output is emitted in sub-blocks of at most
     * 255 bytes.</p>
     */
    private static void writeLzw(OutputStream out, byte[] indices, int minimumCodeSize)
            throws IOException {
        out.write(minimumCodeSize);

        int clearCode = 1 << minimumCodeSize;
        int endCode = clearCode + 1;
        int nextCode = endCode + 1;
        int codeSize = minimumCodeSize + 1;

        // Dictionary keyed by (prefix << 8) | suffix, which is exact because a
        // suffix is always a single byte.
        java.util.HashMap<Integer, Integer> dictionary = new java.util.HashMap<>(4096);

        BitWriter bits = new BitWriter();
        bits.write(clearCode, codeSize);

        int prefix = indices.length > 0 ? (indices[0] & 0xFF) : endCode;
        for (int i = 1; i < indices.length; i++) {
            int suffix = indices[i] & 0xFF;
            int key = (prefix << 8) | suffix;
            Integer existing = dictionary.get(key);
            if (existing != null) {
                prefix = existing;
                continue;
            }
            bits.write(prefix, codeSize);
            if (nextCode < 4096) {
                dictionary.put(key, nextCode++);
                if (nextCode > (1 << codeSize) && codeSize < 12) {
                    codeSize++;
                }
            } else {
                bits.write(clearCode, codeSize);
                dictionary.clear();
                nextCode = endCode + 1;
                codeSize = minimumCodeSize + 1;
            }
            prefix = suffix;
        }
        bits.write(prefix, codeSize);
        bits.write(endCode, codeSize);
        bits.flush();

        byte[] payload = bits.toByteArray();
        int offset = 0;
        while (offset < payload.length) {
            int length = Math.min(255, payload.length - offset);
            out.write(length);
            out.write(payload, offset, length);
            offset += length;
        }
        out.write(0);   // block terminator
    }

    /** Packs codes least-significant-bit first, as GIF requires. */
    private static final class BitWriter {

        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(4096);
        private int accumulator;
        private int bitCount;

        void write(int code, int size) {
            accumulator |= code << bitCount;
            bitCount += size;
            while (bitCount >= 8) {
                bytes.write(accumulator & 0xFF);
                accumulator >>>= 8;
                bitCount -= 8;
            }
        }

        void flush() {
            if (bitCount > 0) {
                bytes.write(accumulator & 0xFF);
                accumulator = 0;
                bitCount = 0;
            }
        }

        byte[] toByteArray() {
            return bytes.toByteArray();
        }
    }
}
