package dev.custommodels.cmv.explorer.scan;

import com.mojang.blaze3d.platform.NativeImage;
import dev.custommodels.cmv.compat.McCompat;
import dev.custommodels.cmv.core.CmvMod;
import dev.custommodels.cmv.explorer.res.ResourceSource;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads models and textures out of the loaded resource packs.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Every method here runs on a worker thread. That is safe because a
 * {@code ResourceManager} is built once per resource reload and is only read
 * afterwards; a reload does not mutate the old one, it replaces it. The scanner
 * captures a reference at the start of a scan and keeps using that one, so a
 * reload part-way through produces a stale-but-consistent result which the
 * scanner then discards, rather than a torn one.</p>
 *
 * <h2>Textures</h2>
 *
 * <p>PNGs are decoded with {@link NativeImage}, which is Minecraft's own
 * decoder and therefore agrees with what the game will actually show. The
 * native buffer is copied into a plain {@code int[]} and released immediately:
 * holding {@code NativeImage} handles for a whole pack would put tens of
 * megabytes into off-heap memory that the garbage collector cannot see, and the
 * rasterizer only ever wants ARGB integers anyway.</p>
 */
public final class GameResourceSource implements ResourceSource {

    /**
     * Longest edge a decoded texture keeps.
     *
     * <p>Chosen against the largest preview the interface asks for. Nearest
     * sampling above this would be discarding the extra detail anyway, one
     * screen pixel at a time, every frame.</p>
     */
    private static final int MAX_TEXTURE_EDGE = 512;

    private final ResourceManager resources;

    /**
     * Remembers which pack won each lookup. Resolving a texture's source pack
     * is otherwise a full resource lookup, and the graph builder asks for the
     * same handful of paths repeatedly while hashing.
     */
    private final Map<String, String> packOfModel = new ConcurrentHashMap<>();
    private final Map<String, String> packOfTexture = new ConcurrentHashMap<>();

    private volatile long texturePixels;

    public GameResourceSource(ResourceManager resources) {
        this.resources = resources;
    }

    /**
     * True while the game is still using the resources this was built from.
     *
     * <p>Compared by identity: a reload produces a new manager, and the old
     * one's packs are closed behind it.</p>
     */
    @Override
    public boolean isCurrent() {
        try {
            return dev.custommodels.cmv.compat.McCompat.resources() == resources;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Only the client thread may read the packs.
     *
     * <p>Scans are the exception and run off it deliberately, but they do so
     * during a phase where nothing else is drawing; everything after a scan —
     * building a mesh, decoding a texture for a preview — has to come back to
     * the client thread to touch a resource.</p>
     */
    @Override
    public boolean canLoadHere() {
        try {
            return McCompat.mc() == null || McCompat.mc().isSameThread() || scanning;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** True while a scan owns this source and is reading it on its own threads. */
    private volatile boolean scanning = true;

    /** Called when the scan finishes; after this only the client thread reads. */
    public void endScan() {
        this.scanning = false;
    }

    /** Total decoded pixels, so the scan report can state the real memory cost. */
    public long texturePixels() {
        return texturePixels;
    }

    @Override
    public Reader openModel(String modelId) throws IOException {
        Optional<Resource> resource = find(modelPath(modelId));
        if (resource.isEmpty()) {
            packOfModel.put(modelId, "");
            return null;
        }
        packOfModel.put(modelId, safePackId(resource.get()));
        return resource.get().openAsReader();
    }

    @Override
    public String modelSourcePack(String modelId) {
        String cached = packOfModel.get(modelId);
        if (cached != null) {
            return cached;
        }
        String packId = find(modelPath(modelId)).map(GameResourceSource::safePackId).orElse("");
        packOfModel.put(modelId, packId);
        return packId;
    }

    @Override
    public TextureBytes readTextureBytes(String textureId) throws IOException {
        Optional<Resource> resource = find(texturePath(textureId));
        if (resource.isEmpty()) {
            packOfTexture.put(textureId, "");
            return null;
        }
        packOfTexture.put(textureId, safePackId(resource.get()));
        if (tooLargeToDecode(resource.get(), textureId)) {
            return null;
        }
        try (InputStream stream = resource.get().open()) {
            // Frame height is read here too, while the pack is open, and at
            // source scale: the decode has not happened yet so nothing knows
            // how far the image will be sampled down.
            int[] meta = sidecar(textureId);
            return new TextureBytes(stream.readAllBytes(), meta[0], meta[1]);
        }
    }

    @Override
    public Image decodeTexture(TextureBytes raw) {
        if (raw == null || raw.bytes().length == 0) {
            return null;
        }
        try (NativeImage image = NativeImage.read(raw.bytes())) {
            return sample(image, raw.frameHeight(), raw.frameTime());
        } catch (IOException | RuntimeException e) {
            // A pack can ship something that is not a PNG. That is the pack's
            // problem to fix, not a reason to abandon anything.
            return null;
        }
    }

    /**
     * Reads a texture in one go. Only used where the caller is already on a
     * thread allowed to read the pack; the preview path splits the two halves.
     */
    @Override
    public Image loadTexture(String textureId) throws IOException {
        TextureBytes raw = readTextureBytes(textureId);
        return raw == null ? null : decodeTexture(raw);
    }

    /**
     * Samples a decoded image down to something a preview can use.
     *
     * <p>Previews are drawn at a couple of hundred pixels, so a 2048-square
     * texture is detail nothing will ever see — but converting all of it costs
     * real time. One model in the pack examined here carries 6.3 million
     * texture pixels and took 180 ms to load, which was the single slowest
     * thing in drawing a grid.</p>
     *
     * @param sourceFrameHeight animation frame height in source pixels
     */
    private Image sample(NativeImage image, int sourceFrameHeight, int frameTime) {
        int width = image.getWidth();
        int height = image.getHeight();
        int scale = 1;
        while (Math.max(width, height) / scale > MAX_TEXTURE_EDGE) {
            scale *= 2;
        }
        // -1 from the sidecar means "square frames", which needs the width the
        // sampler can see; MAX_VALUE means there was no animation at all.
        int sourceHeight = sourceFrameHeight < 0 ? Math.min(width, height)
                : Math.min(sourceFrameHeight, height);
        int frameHeight = Math.max(1, sourceHeight / scale);
        if (scale > 1) {
            int outWidth = Math.max(1, width / scale);
            int outHeight = Math.max(1, height / scale);
            int[] pixels = new int[outWidth * outHeight];
            for (int y = 0; y < outHeight; y++) {
                int row = y * outWidth;
                int sourceY = y * scale;
                for (int x = 0; x < outWidth; x++) {
                    pixels[row + x] = image.getPixel(x * scale, sourceY);
                }
            }
            texturePixels += (long) outWidth * outHeight;
            return new Image(outWidth, outHeight, pixels, scale,
                    Math.max(1, Math.min(frameHeight, outHeight)), frameTime);
        }
        // getPixels hands back ARGB in row-major order, which is exactly the
        // rasterizer's format. Verified against the 26.2 jar: it reads the
        // native ABGR buffer and converts each pixel via ARGB.fromABGR.
        // (makePixelArray does the same thing but is deprecated in 26.2.)
        int[] pixels = image.getPixels();
        texturePixels += (long) width * height;
        return new Image(width, height, pixels, 1,
                Math.max(1, Math.min(frameHeight, height)), frameTime);
    }

    @Override
    public String textureSourcePack(String textureId) {
        String cached = packOfTexture.get(textureId);
        if (cached != null) {
            return cached;
        }
        String packId = find(texturePath(textureId)).map(GameResourceSource::safePackId).orElse("");
        packOfTexture.put(textureId, packId);
        return packId;
    }

    @Override
    public java.util.Set<String> namespaces() {
        try {
            return resources.getNamespaces();
        } catch (RuntimeException e) {
            return java.util.Set.of();
        }
    }

    @Override
    public Reader openLang(String namespace, String code) throws IOException {
        Identifier id;
        try {
            id = Identifier.fromNamespaceAndPath(namespace, "lang/" + code + ".json");
        } catch (RuntimeException e) {
            return null;
        }
        Optional<Resource> resource = find(id);
        return resource.isPresent() ? resource.get().openAsReader() : null;
    }

    @Override
    public Reader openTextureMeta(String textureId) throws IOException {
        Identifier id = texturePath(textureId);
        if (id == null) {
            return null;
        }
        Optional<Resource> resource = find(id.withPath(id.getPath() + ".mcmeta"));
        return resource.isPresent() ? resource.get().openAsReader() : null;
    }

    /**
     * Largest image this will decode, in pixels.
     *
     * <p>The downsampling below happens *after* the PNG is decoded, so a
     * pathological texture is a full-size native allocation before anything can
     * shrink it: a 8192-square PNG is 268 MB, which on the four-gigabyte
     * instance this was tuned against is the end of the session. Sixteen
     * megapixels is 4096 square, far beyond anything a 512-pixel preview can
     * show, so nothing legitimate is refused.</p>
     */
    private static final long MAX_DECODE_PIXELS = 4096L * 4096L;

    /**
     * Reads a PNG's dimensions from its header without decoding it.
     *
     * <p>The IHDR chunk is always first and always at a fixed offset, so the
     * size is knowable from the first 24 bytes. Anything that is not a PNG
     * falls through and is decoded as before, which is where a pack shipping
     * something else gets its warning.</p>
     */
    /**
     * A texture's size on disk, without decoding it.
     *
     * <p>Reads the twenty-four byte PNG header and stops. The atlas estimate
     * needs the native dimensions of tens of thousands of textures, and
     * decoding them all would cost hundreds of megabytes to learn two numbers
     * each. Returns null when the file is missing or not a PNG.</p>
     */
    public int[] textureSize(String textureId) {
        Optional<Resource> resource = find(texturePath(textureId));
        if (resource.isEmpty()) {
            return null;
        }
        try (InputStream stream = resource.get().open()) {
            byte[] header = stream.readNBytes(24);
            if (header.length < 24 || (header[0] & 0xFF) != 0x89
                    || header[1] != 'P' || header[2] != 'N' || header[3] != 'G') {
                return null;
            }
            long width = readInt(header, 16);
            long height = readInt(header, 20);
            if (width <= 0 || height <= 0) {
                return null;
            }
            return new int[] {(int) width, (int) height};
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private boolean tooLargeToDecode(Resource resource, String textureId) {
        try (InputStream stream = resource.open()) {
            byte[] header = stream.readNBytes(24);
            if (header.length < 24
                    || (header[0] & 0xFF) != 0x89 || header[1] != 'P'
                    || header[2] != 'N' || header[3] != 'G') {
                return false;
            }
            long width = readInt(header, 16);
            long height = readInt(header, 20);
            if (width <= 0 || height <= 0 || width * height <= MAX_DECODE_PIXELS) {
                return false;
            }
            CmvMod.LOGGER.warn("Skipping {}: {}x{} is larger than this will decode",
                    textureId, width, height);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static long readInt(byte[] bytes, int at) {
        return ((long) (bytes[at] & 0xFF) << 24) | ((bytes[at + 1] & 0xFF) << 16)
                | ((bytes[at + 2] & 0xFF) << 8) | (bytes[at + 3] & 0xFF);
    }

    /**
     * Height of one animation frame, from the {@code .mcmeta} sidecar.
     *
     * <p>Read here, while the texture is being loaded, because loading is the
     * moment that happens on a thread allowed to read the pack. It used to be
     * read while building a mesh, on a preview worker, which is one of the two
     * places this mod was reaching into the game's resources from the wrong
     * thread.</p>
     */
    private int rawFrameHeight(String textureId) {
        return sidecar(textureId)[0];
    }

    /** How long each frame is held, in ticks. One when the sidecar is silent. */
    private int rawFrameTime(String textureId) {
        return sidecar(textureId)[1];
    }

    /**
     * The frame height and frame time from a texture's sidecar.
     *
     * <p>{@code frametime} may be written as a decimal — a handful in the packs
     * this was built against are — so it is read as a double and rounded rather
     * than being refused for not being an integer.</p>
     *
     * <p>An explicit {@code frames} list, which can reorder frames or give one
     * of them its own duration, is not read: it appears on 304 of the 8,613
     * animated textures here, and the difference it makes to a preview is the
     * order a few of them cycle in. The frames themselves are all played
     * either way.</p>
     *
     * @return {@code {frameHeight, frameTime}}
     */
    private int[] sidecar(String textureId) {
        try (Reader reader = openTextureMeta(textureId)) {
            if (reader == null) {
                return new int[]{Integer.MAX_VALUE, 1};
            }
            com.google.gson.JsonElement root = com.google.gson.JsonParser.parseReader(reader);
            if (root == null || !root.isJsonObject()
                    || !root.getAsJsonObject().has("animation")) {
                return new int[]{Integer.MAX_VALUE, 1};
            }
            com.google.gson.JsonObject animation =
                    root.getAsJsonObject().getAsJsonObject("animation");
            int frameTime = 1;
            if (animation.has("frametime")) {
                frameTime = Math.max(1,
                        (int) Math.round(animation.get("frametime").getAsDouble()));
            }
            if (animation.has("height")) {
                return new int[]{Math.max(1, animation.get("height").getAsInt()), frameTime};
            }
            // Square frames are the default when height is not stated. Signalled
            // as "unknown" so the sampler can use the width it can see.
            return new int[]{-1, frameTime};
        } catch (IOException | RuntimeException e) {
            return new int[]{Integer.MAX_VALUE, 1};
        }
    }

    // -------------------------------------------------------------- helpers

    private Optional<Resource> find(Identifier id) {
        if (id == null) {
            return Optional.empty();
        }
        try {
            return resources.getResource(id);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Identifier modelPath(String modelId) {
        return toIdentifier(modelId, "models/", ".json");
    }

    private static Identifier texturePath(String textureId) {
        return toIdentifier(textureId, "textures/", ".png");
    }

    private static Identifier toIdentifier(String raw, String prefix, String suffix) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        int colon = raw.indexOf(':');
        String namespace = colon >= 0 ? raw.substring(0, colon) : "minecraft";
        String path = colon >= 0 ? raw.substring(colon + 1) : raw;
        try {
            return Identifier.fromNamespaceAndPath(namespace, prefix + path + suffix);
        } catch (RuntimeException e) {
            // Illegal characters in an id are a pack bug; treat as not found.
            return null;
        }
    }

    private static String safePackId(Resource resource) {
        try {
            return resource.sourcePackId();
        } catch (RuntimeException e) {
            return "unknown";
        }
    }
}
