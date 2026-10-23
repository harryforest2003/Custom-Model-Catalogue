package dev.custommodels.cmv.explorer.res;

import java.io.IOException;
import java.io.Reader;

/**
 * The narrow slice of resource-pack access the resolver needs.
 *
 * <p>Keeping this an interface is what lets {@link ModelResolver} and the
 * rasterizer stay free of Minecraft imports: the game supplies a resource
 * manager backed implementation, and tests supply a directory backed one.</p>
 *
 * <p>Implementations must be safe to call from a worker thread.</p>
 */
public interface ResourceSource {

    /**
     * An ARGB image plus its dimensions, as loaded from a pack PNG.
     *
     * @param scale how many source pixels each stored pixel covers. 1 means the
     *              image is at its original size; larger means it was sampled
     *              down on load, and anything measured in source pixels — an
     *              animation's frame height, for instance — has to be divided
     *              by this before it means anything here
     * @param frameHeight height of one animation frame, already in stored
     *              pixels. Equal to {@code height} for the great majority of
     *              textures, which are a single frame. Read from the
     *              {@code .mcmeta} sidecar at load time rather than when the
     *              mesh is built, because loading is the moment that happens on
     *              a thread allowed to read the pack
     * @param frameTime how many game ticks each frame is held for, from the
     *              same sidecar. One tick is 50ms. Meaningless unless the
     *              texture has more than one frame, and 1 when the sidecar does
     *              not say
     */
    record Image(int width, int height, int[] argb, int scale, int frameHeight,
                 int frameTime) {

        public Image(int width, int height, int[] argb, int scale, int frameHeight) {
            this(width, height, argb, scale, frameHeight, 1);
        }

        public Image(int width, int height, int[] argb, int scale) {
            this(width, height, argb, scale, height, 1);
        }

        public Image(int width, int height, int[] argb) {
            this(width, height, argb, 1, height, 1);
        }

        /** How many frames are stacked in this texture. */
        public int frames() {
            return frameHeight > 0 && frameHeight < height ? height / frameHeight : 1;
        }

        /**
         * The row this texture's animation starts at, at a given tick.
         *
         * <p>Zero for the great majority of textures, which have one frame.
         * Worked out once per face rather than per pixel: an animated texture
         * is a vertical strip, so playing it is a matter of which row the
         * face's own UVs are read from.</p>
         */
        public int rowOffset(int tick) {
            int frames = frames();
            if (frames <= 1) {
                return 0;
            }
            int hold = Math.max(1, frameTime);
            return Math.floorMod(tick / hold, frames) * frameHeight;
        }

        public int pixel(int x, int y) {
            if (x < 0 || y < 0 || x >= width || y >= height) {
                return 0;
            }
            return argb[y * width + x];
        }
    }

    /**
     * The raw bytes of a texture file, plus what its sidecar says.
     *
     * <p>Split from decoding on purpose. Reading has to happen on a thread
     * allowed to touch the pack; decoding a PNG is pure computation and is by
     * far the expensive half — reading a megabyte out of a zip is a couple of
     * milliseconds, decoding a 2048-square image is tens. Doing both on the
     * client thread would trade a correctness bug for a stutter.</p>
     *
     * @param frameHeight height of one animation frame in source pixels, or the
     *                    image height when there is no animation
     * @param frameTime how many ticks each frame is held for; 1 when unstated
     */
    record TextureBytes(byte[] bytes, int frameHeight, int frameTime) {

        public TextureBytes(byte[] bytes, int frameHeight) {
            this(bytes, frameHeight, 1);
        }
    }

    /** Reads a texture's bytes. Only where {@link #canLoadHere()} is true. */
    default TextureBytes readTextureBytes(String textureId) throws IOException {
        return null;
    }

    /** Decodes bytes read earlier. Pure, and safe on any thread. */
    default Image decodeTexture(TextureBytes raw) {
        return null;
    }

    /**
     * Opens {@code assets/<ns>/models/<path>.json}.
     *
     * @param modelId namespaced model id, e.g. {@code example:item/bow}
     * @return a reader the caller closes, or null when the file does not exist
     */
    Reader openModel(String modelId) throws IOException;

    /**
     * Whether this source still refers to the resources the game is using.
     *
     * <p>A scan captures the resource manager it started with. Once the game
     * reloads, that manager's packs may be closed, and reading from them is at
     * best useless and at worst reaches into a closed file. Anything that reads
     * outside the scan has to ask first.</p>
     *
     * <p>Sources not backed by the game — the in-memory one used by the tests —
     * are always current.</p>
     */
    default boolean isCurrent() {
        return true;
    }

    /**
     * Whether it is safe to read from this source on the calling thread.
     *
     * <p>The game's resource packs are not a thread-safe store to read from at
     * arbitrary moments. Reading them from preview workers while the game was
     * drawing left a run's log full of {@code ClosedChannelException} from the
     * vanilla jar's zip filesystem — and the casualties were Minecraft's own
     * lazily-loaded textures, the inventory background and the block-breaking
     * overlay among them, which is what a missing-texture square looks like
     * when a mod reaches somewhere it should not.</p>
     *
     * <p>Sources not backed by the game — the in-memory one used by the tests —
     * can be read anywhere.</p>
     */
    default boolean canLoadHere() {
        return true;
    }

    /** The archive path a model occupies, whether or not it exists. */
    default String modelArchivePath(String modelId) {
        int colon = modelId.indexOf(':');
        String namespace = colon >= 0 ? modelId.substring(0, colon) : "minecraft";
        String path = colon >= 0 ? modelId.substring(colon + 1) : modelId;
        return "assets/" + namespace + "/models/" + path + ".json";
    }

    /** The pack a model actually came from, or empty when it does not exist. */
    String modelSourcePack(String modelId);

    /**
     * Loads {@code assets/<ns>/textures/<path>.png} as ARGB pixels.
     *
     * @return null when the texture does not exist or cannot be decoded
     */
    Image loadTexture(String textureId) throws IOException;

    /** The archive path a texture occupies, whether or not it exists. */
    default String textureArchivePath(String textureId) {
        int colon = textureId.indexOf(':');
        String namespace = colon >= 0 ? textureId.substring(0, colon) : "minecraft";
        String path = colon >= 0 ? textureId.substring(colon + 1) : textureId;
        return "assets/" + namespace + "/textures/" + path + ".png";
    }

    /** The pack a texture came from, or empty when it does not exist. */
    String textureSourcePack(String textureId);

    /** Namespaces the loaded packs contribute, for language-file discovery. */
    default java.util.Set<String> namespaces() {
        return java.util.Set.of();
    }

    /**
     * Opens {@code assets/<ns>/lang/<code>.json}.
     *
     * <p>This is where a pack states what it calls things, and it is the only
     * place a display name is a fact rather than an inference. Most packs never
     * write one — the two examined during development had empty language files
     * — so callers must treat a null return as normal, not exceptional.</p>
     *
     * @return a reader the caller closes, or null when there is no such file
     */
    default Reader openLang(String namespace, String code) throws IOException {
        return null;
    }

    /**
     * The {@code .mcmeta} sidecar of a texture, if any. Used to detect animated
     * textures so the preview can show the first frame rather than a squashed
     * strip.
     *
     * @return null when there is no sidecar
     */
    Reader openTextureMeta(String textureId) throws IOException;

    /**
     * Where else a texture might be, when the id a model gives finds nothing.
     *
     * <h2>The packs this is for</h2>
     *
     * <p>ItemsAdder generates its models pointing at a namespace one or two
     * prefixes away from the one the texture is filed under:
     * {@code _medieval_crafting_materials:adamantine_ore} for a file that is
     * actually at {@code assets/medieval_crafting_materials/textures/
     * adamantine_ore.png}, and {@code _b_elitecreatures:...} for one under
     * {@code elitecreatures}. In the pack measured, 9,414 texture references
     * resolved to nothing, and 9,331 of them — 99.1% — were present under the
     * same name with the prefix taken off.</p>
     *
     * <h2>What this does not claim</h2>
     *
     * <p>Minecraft has no such rule. It reads the namespace literally, so a
     * pack that only files the texture under the short name draws the missing
     * texture in the world whatever this returns. This exists so the mod can
     * show what the model was drawn to look like, not to suggest the game
     * agrees — if a pack looks right here and wrong in hand, this is why.</p>
     *
     * <p>Only consulted after the id as written has already missed, so a pack
     * that files its textures where it says costs nothing for this.</p>
     *
     * @return the ids to try next, most likely first; empty when there is no
     *         prefix to take off
     */
    static java.util.List<String> alternativeTextureIds(String textureId) {
        int colon = textureId.indexOf(':');
        if (colon <= 1 || textureId.charAt(0) != '_') {
            return java.util.List.of();
        }
        String namespace = textureId.substring(0, colon);
        String rest = textureId.substring(colon);
        if (namespace.startsWith("_b_") && namespace.length() > 3) {
            // Both readings, because "_b_x" could be "x" behind two prefixes or
            // "b_x" behind one, and only the pack knows which.
            return java.util.List.of(namespace.substring(3) + rest,
                    namespace.substring(1) + rest);
        }
        return java.util.List.of(namespace.substring(1) + rest);
    }
}
