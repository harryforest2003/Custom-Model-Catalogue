package dev.custommodels.cmv.explorer.scan;

import dev.custommodels.cmv.compat.McCompat;
import dev.custommodels.cmv.core.CmvMod;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.atlas.SpriteResourceLoader;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.client.renderer.texture.atlas.SpriteSourceList;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * How full the texture atlases are, and how much room is left.
 *
 * <p>This exists because of a silent failure with a loud consequence. Every item
 * sprite is stitched into one atlas whose side is a power of two, capped at
 * 16384. When the sprites do not fit, stitching throws, and the client's
 * response is {@code clearResourcePacksOnError}: it switches off <em>every</em>
 * selected pack and writes that to options.txt. The player watches their pack
 * list empty itself, and the one line in the log explaining why is buried under
 * thousands of unrelated warnings.
 *
 * <h2>Why this asks the game rather than working it out</h2>
 * <p>The first version of this counted the textures the indexed models referred
 * to, which was wrong in both directions at once. It missed sprites pulled in by
 * an atlas pointing at a whole directory, and it counted textures belonging to
 * the blocks atlas, models a higher-priority pack had overridden, and states the
 * game never bakes. On one real pack set it reported 440 million pixels and
 * declared the atlas unstitchable while the game was running perfectly with
 * those packs loaded.
 *
 * <p>So the sprite list now comes from {@link SpriteSourceList}, which is the
 * same code the client uses to decide what goes into an atlas, and the atlas
 * sizes come from the atlases themselves. Directory sources, filters, palette
 * permutations and pack precedence are all handled because none of them are
 * reimplemented here.
 *
 * <p>The one trick is measuring size without paying for it. Asking each sprite
 * to load would decode every texture in the pack — gigabytes — to learn two
 * numbers each, so a stand-in {@link SpriteResourceLoader} is passed instead,
 * which reads the twenty-four byte PNG header and hands back nothing.
 */
public final class AtlasBudget {

    /** The largest atlas the client will build. */
    public static final int MAX_SIDE = 16384;

    /** What one pack contributes to one atlas. */
    public record PackShare(String packId, long pixels, int sprites) {
    }

    /**
     * One atlas, measured.
     *
     * @param side     the side the sprites need, a power of two
     * @param liveSide the side the atlas actually has right now, or 0 when the
     *                 atlas has not been built yet
     */
    public record Atlas(String name, long pixels, int sprites, int side, int liveSide,
                        List<PackShare> byPack, List<PackShare> byNamespace) {

        /** The side to judge against: what the game built, or what is needed. */
        public int effectiveSide() {
            return liveSide > 0 ? liveSide : side;
        }

        /**
         * The pixels this atlas can hold.
         *
         * <p>Capped at the largest atlas the client will build, so an overflow
         * is measured against what is actually possible. Reporting it against
         * the imaginary next size up produced the reading that started this:
         * an unstitchable atlas described as "41% full", which sounds like
         * plenty of room and means the exact opposite.</p>
         */
        public long capacity() {
            long s = Math.min(effectiveSide(), MAX_SIDE);
            return s * s;
        }

        /** Goes above 1 when the sprites cannot fit at all. */
        public double fill() {
            long capacity = capacity();
            return capacity == 0 ? 0 : pixels / (double) capacity;
        }

        /** Pixels that could still be added before the atlas has to grow. */
        public long room() {
            return overflows() ? 0 : Math.max(0, capacity() - pixels);
        }

        /** True when the sprites cannot be stitched at any allowed size. */
        public boolean overflows() {
            return side > MAX_SIDE;
        }

        /** True when the next thing added would force the largest atlas. */
        public boolean tight() {
            return !overflows() && effectiveSide() >= MAX_SIDE && fill() > 0.6;
        }

        /** What the atlas costs in video memory, at the size it can be built. */
        public long vram() {
            long s = Math.min(effectiveSide(), MAX_SIDE);
            return s * s * 4L;
        }
    }

    public record Result(List<Atlas> atlases) {

        public Atlas items() {
            for (Atlas atlas : atlases) {
                if (atlas.name().equals("items")) {
                    return atlas;
                }
            }
            return null;
        }

        public boolean anyOverflows() {
            return atlases.stream().anyMatch(Atlas::overflows);
        }
    }

    /** The atlases worth reporting. Others hold a handful of sprites. */
    private static final Map<String, Identifier> WATCHED = new LinkedHashMap<>();

    static {
        WATCHED.put("items", AtlasIds.ITEMS);
        WATCHED.put("blocks", AtlasIds.BLOCKS);
        WATCHED.put("particles", AtlasIds.PARTICLES);
        WATCHED.put("gui", AtlasIds.GUI);
        WATCHED.put("armor trims", AtlasIds.ARMOR_TRIMS);
    }

    private static final AtomicBoolean running = new AtomicBoolean();
    private static volatile Result latest;
    private static volatile boolean stale = true;

    private AtlasBudget() {
    }

    public static Result latest() {
        return latest;
    }

    public static boolean measuring() {
        return running.get();
    }

    public static void invalidate() {
        stale = true;
    }

    /** Measures in the background when the figure is missing or out of date. */
    public static void request(ExplorerIndex index) {
        if (!stale || running.get()) {
            return;
        }
        ResourceManager resources = McCompat.resources();
        if (resources == null) {
            return;
        }
        // Read on the client thread, where the atlases live, and handed to the
        // worker as plain numbers.
        Map<String, Integer> live = liveSides();
        if (!running.compareAndSet(false, true)) {
            return;
        }
        stale = false;
        Thread worker = new Thread(() -> {
            try {
                latest = measure(resources, live);
            } catch (RuntimeException | LinkageError e) {
                CmvMod.LOGGER.warn("Could not measure the atlases", e);
                stale = true;
            } finally {
                running.set(false);
            }
        }, "cmv-atlas-budget");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * The size each atlas actually has, straight from the texture the client
     * built. Reflective because the handle is protected, and entirely optional:
     * without it the needed size is reported instead of the real one.
     */
    private static Map<String, Integer> liveSides() {
        Map<String, Integer> sides = new HashMap<>();
        try {
            var manager = McCompat.mc().getAtlasManager();
            if (manager == null) {
                return sides;
            }
            for (Map.Entry<String, Identifier> watched : WATCHED.entrySet()) {
                try {
                    TextureAtlas atlas = manager.getAtlasOrThrow(watched.getValue());
                    int side = sideOf(atlas);
                    if (side > 0) {
                        sides.put(watched.getKey(), side);
                    }
                } catch (RuntimeException e) {
                    // An atlas that has not been stitched yet simply has no size.
                }
            }
        } catch (RuntimeException | LinkageError e) {
            CmvMod.LOGGER.debug("No atlas sizes available", e);
        }
        return sides;
    }

    // Looked up once. setAccessible and getDeclaredField are not free, and
    // this is asked for five atlases every time a measurement starts.
    private static java.lang.reflect.Field textureField;
    private static java.lang.reflect.Method widthMethod;
    private static boolean reflectionFailed;

    private static int sideOf(TextureAtlas atlas) {
        if (reflectionFailed) {
            return 0;
        }
        try {
            if (textureField == null) {
                textureField = Class.forName(
                        "net.minecraft.client.renderer.texture.AbstractTexture")
                        .getDeclaredField("texture");
                textureField.setAccessible(true);
            }
            Object texture = textureField.get(atlas);
            if (texture == null) {
                return 0;
            }
            if (widthMethod == null || !widthMethod.getDeclaringClass()
                    .isInstance(texture)) {
                widthMethod = texture.getClass().getMethod("getWidth", int.class);
            }
            return (int) widthMethod.invoke(texture, 0);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // One failure means the shape has changed; stop paying for it.
            reflectionFailed = true;
            return 0;
        }
    }

    private static Result measure(ResourceManager resources, Map<String, Integer> live) {
        List<Atlas> out = new ArrayList<>();
        for (Map.Entry<String, Identifier> watched : WATCHED.entrySet()) {
            Sizer sizer = new Sizer();
            try {
                SpriteSourceList list = SpriteSourceList.load(resources, watched.getValue());
                for (SpriteSource.Loader loader : list.list(resources)) {
                    try {
                        loader.get(sizer);
                    } catch (RuntimeException | LinkageError e) {
                        // One unreadable sprite is not worth losing the total.
                        sizer.skipped++;
                    }
                }
            } catch (RuntimeException | LinkageError e) {
                CmvMod.LOGGER.debug("Could not read the {} atlas", watched.getKey(), e);
                continue;
            }
            out.add(new Atlas(watched.getKey(), sizer.pixels, sizer.sprites,
                    sideFor(sizer.pixels), live.getOrDefault(watched.getKey(), 0),
                    shares(sizer.byPack), shares(sizer.byNamespace)));
        }
        return new Result(List.copyOf(out));
    }

    private static List<PackShare> shares(Map<String, long[]> tally) {
        List<PackShare> out = new ArrayList<>(tally.size());
        for (Map.Entry<String, long[]> entry : tally.entrySet()) {
            out.add(new PackShare(entry.getKey(), entry.getValue()[0],
                    (int) entry.getValue()[1]));
        }
        out.sort(Comparator.comparingLong(PackShare::pixels).reversed());
        return List.copyOf(out);
    }

    /**
     * Stands in for the sprite loader and measures instead of decoding.
     *
     * <p>Returning null is safe because the loaders this is handed to pass the
     * result straight back to the caller, which is this class.</p>
     */
    private static final class Sizer implements SpriteResourceLoader {

        long pixels;
        int sprites;
        int skipped;
        final Map<String, long[]> byPack = new HashMap<>();
        // Keyed by the sprite's own namespace, which is what the strip removes,
        // so the figure on screen is the figure that would go away.
        final Map<String, long[]> byNamespace = new HashMap<>();
        private final Set<Identifier> seen = new HashSet<>();

        @Override
        public SpriteContents loadSprite(Identifier id, Resource resource) {
            // The same sprite id can be produced twice by overlapping sources;
            // the atlas holds one of them.
            if (!seen.add(id)) {
                return null;
            }
            try (InputStream stream = resource.open()) {
                byte[] header = stream.readNBytes(24);
                if (header.length < 24 || (header[0] & 0xFF) != 0x89
                        || header[1] != 'P' || header[2] != 'N' || header[3] != 'G') {
                    return null;
                }
                int width = (int) readInt(header, 16);
                int height = (int) readInt(header, 20);
                if (width <= 0 || height <= 0) {
                    return null;
                }
                // An animated texture is a column of frames, and only one frame
                // is ever in the atlas: the rest are swapped into that same
                // space as it plays. Measuring the whole strip counted a wing's
                // eleven frames eleven times over, which on one real pack added
                // 243 million pixels that were never there and turned an atlas
                // sitting at 80% into one reported as impossible.
                //
                // The frame size is worked out by the game's own code rather
                // than by guessing from the aspect ratio, because a tall texture
                // with no animation beside it is simply a tall texture.
                long area = (long) width * height;
                try {
                    var animation = resource.metadata()
                            .getSection(AnimationMetadataSection.TYPE);
                    if (animation.isPresent()) {
                        FrameSize frame = animation.get().calculateFrameSize(width, height);
                        area = (long) frame.width() * frame.height();
                    }
                } catch (Exception e) {
                    // No readable sidecar: the whole image is one frame.
                }
                pixels += area;
                sprites++;
                String pack;
                try {
                    pack = resource.sourcePackId();
                } catch (RuntimeException e) {
                    pack = "unknown";
                }
                long[] tally = byPack.computeIfAbsent(pack, k -> new long[2]);
                tally[0] += area;
                tally[1]++;
                long[] ns = byNamespace.computeIfAbsent(id.getNamespace(),
                        k -> new long[2]);
                ns[0] += area;
                ns[1]++;
            } catch (Exception e) {
                skipped++;
            }
            return null;
        }

        private static long readInt(byte[] bytes, int at) {
            return ((long) (bytes[at] & 0xFF) << 24) | ((bytes[at + 1] & 0xFF) << 16)
                    | ((bytes[at + 2] & 0xFF) << 8) | (bytes[at + 3] & 0xFF);
        }
    }

    /**
     * The atlas side these pixels need.
     *
     * <p>Doubles until the area fits, which is what the stitcher does, so the
     * answer steps 4096, 8192, 16384 rather than growing smoothly. Returns a
     * value above {@link #MAX_SIDE} when even the largest atlas is too small,
     * because how far over is more useful than a clamp.</p>
     */
    public static int sideFor(long pixels) {
        int side = 64;
        while ((long) side * side < pixels && side < MAX_SIDE * 2) {
            side *= 2;
        }
        return side;
    }
}
