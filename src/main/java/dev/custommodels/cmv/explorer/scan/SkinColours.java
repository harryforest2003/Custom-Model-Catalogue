package dev.custommodels.cmv.explorer.scan;

import dev.custommodels.cmv.explorer.graph.LogicalModel;
import dev.custommodels.cmv.explorer.res.ResourceSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What colour each model mostly is, so the grid can be narrowed to one.
 *
 * <p>Nothing in the scan reads a single pixel. It resolves texture <em>ids</em>
 * and counts them; the pixels behind them are decoded when something is drawn,
 * which is why a pack of fifty thousand models scans in seconds. A colour
 * filter needs the opposite — one number from every texture whether or not it
 * has ever been on screen — so it gets its own pass, on a thread of its own, at
 * the lowest priority the JVM offers.</p>
 *
 * <p>It is deliberately <em>not</em> part of the scan. Adding a decode of every
 * texture to every rescan would make the thing people do constantly slower for
 * the sake of a filter they use occasionally. This way a scan costs what it
 * always did, and picking a colour costs a few seconds once.</p>
 *
 * <p>Distinct textures are the unit of work, not models: a shared template is
 * referenced hundreds of times in these packs, and decoding it once is the
 * difference between a few thousand decodes and tens of thousands.</p>
 */
public final class SkinColours {

    private SkinColours() {
    }

    /**
     * The colours worth offering as a filter.
     *
     * <p>Thirteen buckets rather than a wheel. "Show me the red ones" is the
     * question people actually have, and a hue slider answers a question
     * nobody asked while making the common one fiddly.</p>
     */
    public enum Family {
        ANY("Any"),
        WHITE("White"),
        GREY("Grey"),
        BLACK("Black"),
        RED("Red"),
        ORANGE("Orange"),
        BROWN("Brown"),
        YELLOW("Yellow"),
        GREEN("Green"),
        CYAN("Cyan"),
        BLUE("Blue"),
        PURPLE("Purple"),
        PINK("Pink");

        private final String label;

        Family(String label) {
            this.label = label;
        }

        public String displayName() {
            return label;
        }

        public Family next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    // ------------------------------------------------------------ the maths

    /**
     * Below this a pixel is not part of what the model looks like.
     *
     * <p>Item textures are mostly transparent — a sword is a diagonal line
     * across an otherwise empty square — and counting the empty pixels as
     * black would file every one of them under black.</p>
     */
    private static final int OPAQUE_ENOUGH = 24;

    /** Under this much chroma a colour is a neutral, whatever its hue says. */
    private static final int NEUTRAL_CHROMA = 28;

    /**
     * The average of a texture's visible pixels, or -1 if it has none.
     *
     * <p>Weighted by alpha, so a soft edge counts for less than the middle of
     * a blade. -1 rather than black for "nothing visible", because the two
     * want different answers: an empty texture should fall out of a colour
     * filter, not join the black ones.</p>
     */
    public static int averageOf(int[] argb) {
        if (argb == null || argb.length == 0) {
            return -1;
        }
        long red = 0;
        long green = 0;
        long blue = 0;
        long weight = 0;
        for (int pixel : argb) {
            int alpha = (pixel >>> 24) & 0xFF;
            if (alpha < OPAQUE_ENOUGH) {
                continue;
            }
            red += (long) ((pixel >> 16) & 0xFF) * alpha;
            green += (long) ((pixel >> 8) & 0xFF) * alpha;
            blue += (long) (pixel & 0xFF) * alpha;
            weight += alpha;
        }
        if (weight == 0) {
            return -1;
        }
        int r = (int) (red / weight);
        int g = (int) (green / weight);
        int b = (int) (blue / weight);
        return (r << 16) | (g << 8) | b;
    }

    /**
     * Which family a colour belongs to.
     *
     * <p>Neutrals are decided first and on chroma alone. A grey with a faint
     * warm cast is grey; calling it red because red leads by three is the kind
     * of answer that makes a filter useless. Orange and brown share a hue and
     * are split on how dark and how dull they are, because otherwise every
     * leather, wood and copper texture in a pack files under orange.</p>
     */
    public static Family familyOf(int rgb) {
        if (rgb < 0) {
            return Family.ANY;
        }
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        int chroma = max - min;
        int light = (max + min) / 2;
        if (chroma <= NEUTRAL_CHROMA) {
            if (light >= 200) {
                return Family.WHITE;
            }
            return light <= 55 ? Family.BLACK : Family.GREY;
        }
        float hue = hueOf(r, g, b, max, chroma);
        if (hue < 15.0F || hue >= 330.0F) {
            return Family.RED;
        }
        if (hue < 45.0F) {
            return light < 110 || chroma < 90 ? Family.BROWN : Family.ORANGE;
        }
        if (hue < 70.0F) {
            return Family.YELLOW;
        }
        if (hue < 160.0F) {
            return Family.GREEN;
        }
        if (hue < 200.0F) {
            return Family.CYAN;
        }
        if (hue < 260.0F) {
            return Family.BLUE;
        }
        if (hue < 290.0F) {
            return Family.PURPLE;
        }
        return Family.PINK;
    }

    /** Hue in degrees, by the usual formula; chroma is known to be non-zero. */
    private static float hueOf(int r, int g, int b, int max, int chroma) {
        float hue;
        if (max == r) {
            hue = (g - b) / (float) chroma;
        } else if (max == g) {
            hue = (b - r) / (float) chroma + 2.0F;
        } else {
            hue = (r - g) / (float) chroma + 4.0F;
        }
        hue *= 60.0F;
        return hue < 0.0F ? hue + 360.0F : hue;
    }

    /**
     * The family a model belongs to, from the textures its default state wears.
     *
     * <p>The default state rather than every state: a bow has three states and
     * what the card shows is the first of them. Slots are averaged together
     * unweighted — the mod does not know which slot covers more of the model,
     * and guessing from slot names would be guessing.</p>
     */
    public static Family familyOfTextures(List<String> textureIds,
                                          Map<String, Integer> colours) {
        if (textureIds == null || textureIds.isEmpty() || colours == null) {
            return Family.ANY;
        }
        long red = 0;
        long green = 0;
        long blue = 0;
        int counted = 0;
        for (String id : textureIds) {
            Integer rgb = colours.get(id);
            if (rgb == null || rgb < 0) {
                continue;
            }
            red += (rgb >> 16) & 0xFF;
            green += (rgb >> 8) & 0xFF;
            blue += rgb & 0xFF;
            counted++;
        }
        if (counted == 0) {
            return Family.ANY;
        }
        int rgb = (int) ((red / counted) << 16 | (green / counted) << 8 | (blue / counted));
        return familyOf(rgb);
    }

    // ------------------------------------------------------------ the index

    private static final Map<String, Family> BY_MODEL = new ConcurrentHashMap<>();
    private static final Map<String, Integer> BY_TEXTURE = new ConcurrentHashMap<>();

    /**
     * Which list of models the index was built from.
     *
     * <p>The same trick the screen uses to know whether to rebuild: the index
     * swaps the whole list when a scan finishes and never edits it in place,
     * so its identity changes exactly when these answers stop being true.</p>
     */
    private static volatile int builtFrom;
    private static volatile int done;
    private static volatile int total;
    private static volatile boolean running;

    /** Starts the pass if it has not been run for this set of models. */
    public static synchronized void begin(List<LogicalModel> models, ResourceSource source) {
        if (models == null || models.isEmpty() || source == null || running) {
            return;
        }
        int stamp = System.identityHashCode(models);
        if (stamp == builtFrom) {
            return;
        }
        BY_MODEL.clear();
        BY_TEXTURE.clear();
        done = 0;
        total = 0;
        running = true;
        Thread thread = new Thread(() -> {
            try {
                work(models, source, stamp);
            } finally {
                running = false;
            }
        }, "cmv-skin-colours");
        thread.setDaemon(true);
        // Lowest priority on purpose: the previews are what somebody is
        // waiting for, and this is for a filter they may never touch.
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private static void work(List<LogicalModel> models, ResourceSource source, int stamp) {
        List<String> wanted = new ArrayList<>();
        for (LogicalModel model : models) {
            for (String id : texturesOf(model)) {
                if (!BY_TEXTURE.containsKey(id)) {
                    BY_TEXTURE.put(id, Integer.MIN_VALUE);
                    wanted.add(id);
                }
            }
        }
        total = wanted.size();
        for (String id : wanted) {
            int average = -1;
            try {
                ResourceSource.Image image = source.loadTexture(id);
                if (image != null) {
                    average = averageOf(image.argb());
                }
            } catch (Exception e) {
                // A pack can ship something that is not a PNG. One texture
                // that will not read is not a reason to abandon the pass.
                average = -1;
            }
            BY_TEXTURE.put(id, average);
            done++;
        }
        for (LogicalModel model : models) {
            BY_MODEL.put(model.canonicalId(),
                    familyOfTextures(texturesOf(model), BY_TEXTURE));
        }
        builtFrom = stamp;
    }

    /** The textures the card actually shows, with every state as the fallback. */
    private static List<String> texturesOf(LogicalModel model) {
        List<String> slots = List.copyOf(
                model.defaultState().resolved().textureSlots().values());
        return slots.isEmpty() ? model.allTextures() : slots;
    }

    /** The family a model is in, or {@code ANY} when it is not known yet. */
    public static Family of(LogicalModel model) {
        if (model == null) {
            return Family.ANY;
        }
        return BY_MODEL.getOrDefault(model.canonicalId(), Family.ANY);
    }

    /** True when the pass has finished for this list of models. */
    public static boolean ready(List<LogicalModel> models) {
        return models != null && !running
                && System.identityHashCode(models) == builtFrom;
    }

    /** How far the pass has got, for the chip to say so rather than hang. */
    public static int percent() {
        int all = total;
        return all <= 0 ? 0 : Math.min(100, done * 100 / all);
    }

    /**
     * Narrows a list to one family, leaving it alone for {@code ANY}.
     *
     * <p>Applied where the open-set filter is applied rather than inside the
     * query, because that is what it is: a narrowing of what the grid shows,
     * not a property of the search. It also means the colour pass can finish
     * after the screen is open and the next rebuild simply sees more.</p>
     */
    public static <T> List<T> keep(List<T> all, Family want,
                                   java.util.function.Function<T, LogicalModel> modelOf) {
        if (want == null || want == Family.ANY || all == null || all.isEmpty()) {
            return all;
        }
        List<T> out = new ArrayList<>(Math.min(all.size(), 256));
        for (T item : all) {
            if (of(modelOf.apply(item)) == want) {
                out.add(item);
            }
        }
        return out;
    }

    /** Forgets everything, for a rescan or a pack change. */
    public static void forget() {
        BY_MODEL.clear();
        BY_TEXTURE.clear();
        builtFrom = 0;
        done = 0;
        total = 0;
    }
}
