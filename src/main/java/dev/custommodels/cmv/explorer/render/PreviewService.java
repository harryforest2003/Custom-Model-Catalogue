package dev.custommodels.cmv.explorer.render;

import com.mojang.blaze3d.platform.NativeImage;
import dev.custommodels.cmv.compat.McCompat;
import dev.custommodels.cmv.core.CmvMod;
import dev.custommodels.cmv.explorer.graph.LogicalModel;
import dev.custommodels.cmv.explorer.graph.ModelState;
import dev.custommodels.cmv.explorer.res.ModelJson;
import dev.custommodels.cmv.explorer.res.ResolvedModel;
import dev.custommodels.cmv.explorer.res.ResourceSource;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Produces, caches and disposes of every rendered preview in the explorer.
 *
 * <h2>The shape of the problem</h2>
 *
 * <p>A pack can hold a thousand logical models; a screen shows about twenty of
 * them. Giving each card its own renderer and its own animation loop would be a
 * thousand times more work than the screen needs, and the fact that only twenty
 * are visible is the single most useful thing to know. So this class is built
 * around it:</p>
 *
 * <ul>
 *   <li>There is <b>one</b> worker pool and <b>no</b> per-card threads, timers
 *       or animation callbacks. The screen calls {@link #beginFrame()} once per
 *       frame and asks for what it is about to draw.</li>
 *   <li>Cards get a cheap still image the moment they scroll into view, and are
 *       promoted to a rotating preview only after they have stayed visible.
 *       Scrolling quickly past a hundred cards therefore costs a hundred still
 *       frames, not a hundred animations.</li>
 *   <li>Rotation is a strip of pre-rendered angles in a single texture. Once
 *       built, spinning a card costs one blit per frame and no rendering at
 *       all.</li>
 *   <li>Uploads to the GPU are budgeted per frame, because uploading twenty
 *       textures in one frame is exactly the kind of hitch this feature is not
 *       allowed to cause.</li>
 *   <li>Everything is released by {@link #releaseAll()} when the viewer closes.
 *       Pixel data is kept, GPU memory is not: re-uploading from a cached array
 *       is instant, whereas leaving textures resident is the cost the player
 *       would feel while playing.</li>
 * </ul>
 *
 * <h2>Why no disk cache</h2>
 *
 * <p>A still card preview takes a fraction of a millisecond to rasterize, which
 * is less than it takes to read and decode a PNG of the same image. Persisting
 * thumbnails would make opening the viewer slower, not faster, while filling
 * the config folder with thousands of files. The cache that pays for itself is
 * the in-memory one, and it deliberately survives closing the screen so that
 * reopening re-uploads rather than re-renders.</p>
 */
public final class PreviewService {

    /**
     * Angles in a rotation strip.
     *
     * <p>Reduced from twenty-four. A strip costs this many renders where a
     * still costs one, and previews are now rendered at display resolution
     * rather than at 48 pixels, so the old count meant a single card could ask
     * for more work than the whole grid used to.</p>
     */
    public static final int SPIN_FRAMES = 12;

    /** How long a card must stay visible before it earns a rotation strip. */
    private static final long PROMOTE_AFTER_MILLIS = 220;

    /**
     * Fraction of the heap this service is allowed to fill, and the floor and
     * ceiling it is held between.
     *
     * <p>Fixed byte budgets were the wrong shape. They were chosen on a machine
     * with headroom and came to 288 MB, which is affordable on an 8 GB heap and
     * is most of what is left on a 4 GB one shared with a hundred other mods —
     * the case that actually crashed. Sizing from the heap means a big machine
     * still gets big caches and a small one is not asked for memory it does not
     * have.</p>
     *
     * <p>The floor matters as much as the ceiling: below roughly 24 MB total
     * the caches thrash, re-rendering the same visible cards every frame, which
     * is slower than having no cache at all. The three together come to 48 MB
     * on a 1 GB heap and 98 MB on a 4 GB one.</p>
     */
    private static final int HEAP_DIVISOR = 96;

    private static long budget(long floorMegabytes, long ceilingMegabytes) {
        long share = Runtime.getRuntime().maxMemory() / HEAP_DIVISOR;
        return Math.clamp(share, floorMegabytes * 1024 * 1024, ceilingMegabytes * 1024 * 1024);
    }

    /**
     * GPU textures allowed to be resident at once.
     *
     * <p>A grid page is at most a hundred or so cards; anything beyond that is
     * scrolled past and will be asked for again if the player scrolls back.</p>
     */
    private static final int MAX_LIVE_TEXTURES = 128;

    /** Cached pixel data allowed, in bytes. Four bytes per pixel. */
    private static final long MAX_PIXEL_BYTES = budget(12, 32);

    /**
     * Bytes one model's cached cycle of animation frames may fill.
     *
     * <p>Half the pixel cache, and taken from it rather than chosen, which is
     * the only way this number can be right. A cycle has to be resident <em>all
     * at once</em> to replay for nothing; one that does not fit evicts its own
     * earlier frames and re-renders them next time round, which is the
     * thrashing this cache's floor exists to avoid. Half, not all, so a cycle
     * cannot push out the grid thumbnails and the meshes alongside it.</p>
     */
    public static long cycleBudget() {
        return MAX_PIXEL_BYTES / 2;
    }

    /**
     * Cached mesh geometry allowed, in bytes. Textures are not counted here;
     * they live in the shared texture cache and are bounded separately.
     */
    private static final long MAX_MESH_BYTES = budget(12, 24);

    /**
     * Decoded texture pixels held for mesh building, in bytes.
     *
     * <p>Gets the largest share of the three: a pack texture is capped at 512
     * pixels a side, so one is a megabyte, and re-decoding a PNG is far dearer
     * than re-blitting pixels that are already decoded.</p>
     */
    private static final long MAX_TEXTURE_BYTES = budget(24, 48);

    /**
     * Texture uploads permitted per frame.
     *
     * <p>Two was too cautious: a grid of forty cards took twenty frames to
     * fill, which read as "half of them never render". An upload is a memory
     * copy of a small image, not a stall.</p>
     */
    private static final int UPLOADS_PER_FRAME = 6;

    /** Render jobs allowed to be queued at once. */
    private static final int MAX_PENDING = 64;

    private static final ThreadFactory FACTORY = new ThreadFactory() {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "CMV-Preview-" + counter.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 2);
            return thread;
        }
    };

    /** A card preview: a texture plus the slice of it to draw. */
    public record CardPreview(Identifier texture, float v0, float v1) {
    }

    private record Pixels(int width, int height, int[] argb) {

        long bytes() {
            return (long) argb.length * 4;
        }
    }

    private final Map<String, Mesh> meshes = new LinkedHashMap<>(64, 0.75F, true);
    private final Map<String, Pixels> pixels = new LinkedHashMap<>(64, 0.75F, true);
    private final Map<String, Identifier> textures = new LinkedHashMap<>(64, 0.75F, true);
    /**
     * Decoded textures, shared by every mesh.
     *
     * <p>Access-ordered and bounded: an unbounded map would hold every texture
     * a large pack contains, which for one with high-resolution art is hundreds
     * of megabytes that nothing is looking at.</p>
     */
    private final Map<String, ResourceSource.Image> textureCache =
            java.util.Collections.synchronizedMap(new LinkedHashMap<>(128, 0.75F, true));
    private long textureBytes;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Deque<Ready> uploadQueue = new ArrayDeque<>();
    private final Map<String, Long> firstSeen = new java.util.HashMap<>();
    private final Set<String> seenThisFrame = new HashSet<>();

    private record Ready(String key, Pixels image) {
    }

    private volatile ExecutorService workers;
    private volatile ResourceSource source;
    /** Supplies the cuboids, which resolved models deliberately do not keep. */
    private volatile dev.custommodels.cmv.explorer.res.ModelResolver meshResolver;
    private volatile String fingerprint = "";

    private long meshBytes;
    private long pixelBytes;
    private int uploadsThisFrame;
    private int readsThisFrame;

    /**
     * Texture bytes read on the client thread, waiting to be decoded on a
     * worker. Small and short-lived: an entry exists only between the frame
     * that read it and the render that consumes it.
     */
    private final Map<String, ResourceSource.TextureBytes> rawTextures =
            new ConcurrentHashMap<>();

    /**
     * Texture ids a mesh build asked for and could not have. Read on the next
     * frame, on the client thread, before anything else is considered.
     */
    private final Set<String> wantedTextures = ConcurrentHashMap.newKeySet();
    /**
     * Texture files read per frame on the client thread.
     *
     * <p>Only the bytes are read here; decoding happens on a worker. Reading a
     * megabyte out of a zip is a couple of milliseconds, so this can afford to
     * be higher than it would if it were doing the decode as well — but it is
     * still bounded, because a grid of sixty cards arriving at once would
     * otherwise be sixty file reads in a single frame.</p>
     */
    private static final int READS_PER_FRAME = 8;
    private int spinFrame;
    private long lastSpinAdvance;
    private int rendersRequested;
    /**
     * Bumped whenever the visible set changes wholesale, as on a page turn.
     *
     * <p>A queued render captures the value it was submitted under and drops
     * out if it no longer matches. Without this, turning five pages quickly
     * leaves five pages of renders ahead of the one being looked at.</p>
     */
    private volatile int generation;

    private int rendersCompleted;
    private volatile int rendersFailed;

    // ------------------------------------------------------------- lifecycle

    /** Points the service at a scan's resources. Invalidates everything cached. */
    public void bind(ResourceSource newSource,
                     dev.custommodels.cmv.explorer.res.ModelResolver newResolver,
                     String newFingerprint) {
        this.meshResolver = newResolver;
        if (newFingerprint.equals(this.fingerprint) && newSource == this.source) {
            return;
        }
        releaseAll();
        this.source = newSource;
        this.fingerprint = newFingerprint;
    }

    /**
     * Releases every GPU texture and stops all pending work.
     *
     * <p>Called when the viewer closes and when the pack changes. After this
     * the feature owns no GPU memory and no running threads, which is the
     * state it has to be in while the player is just playing.</p>
     */
    public void releaseAll() {
        ExecutorService pool = workers;
        if (pool != null) {
            pool.shutdownNow();
            workers = null;
        }
        inFlight.clear();
        synchronized (uploadQueue) {
            uploadQueue.clear();
        }
        if (McCompat.mc() != null) {
            for (Identifier id : textures.values()) {
                try {
                    McCompat.mc().getTextureManager().release(id);
                } catch (RuntimeException e) {
                    CmvMod.LOGGER.debug("Could not release preview texture {}", id, e);
                }
            }
        }
        textures.clear();
        meshes.clear();
        pixels.clear();
        textureCache.clear();
        rawTextures.clear();
        wantedTextures.clear();
        firstSeen.clear();
        meshBytes = 0;
        pixelBytes = 0;
        textureBytes = 0;
    }

    /**
     * Abandons everything queued but not yet drawn.
     *
     * <p>Finished renders are kept: paging back to where you were should be
     * instant, and those images are already paid for. What goes is the queue,
     * which after a page turn is entirely work for cards nobody can see.</p>
     */
    public void discardPending() {
        generation++;
        inFlight.clear();
        synchronized (uploadQueue) {
            uploadQueue.clear();
        }
    }

    /** Live counters for the statistics panel. */
    public String statistics() {
        return String.format(
                "%d textures, %.1f MB pixels, %.1f MB meshes, %d/%d renders, "
                        + "%d failed, %d queued, %.1f MB textures",
                textures.size(), pixelBytes / 1048576.0, meshBytes / 1048576.0,
                rendersCompleted, rendersRequested, rendersFailed, inFlight.size(),
                textureBytes / 1048576.0);
    }

    public int liveTextureCount() {
        return textures.size();
    }

    // ----------------------------------------------------------- frame cycle

    /**
     * Call once per frame before drawing. Advances the shared spin clock and
     * spends the frame's upload budget.
     */
    public void beginFrame() {
        long now = System.currentTimeMillis();
        // One shared clock for every rotating preview on screen, rather than a
        // timer per card. Cards stay in phase, which also looks better.
        if (now - lastSpinAdvance >= 70) {
            lastSpinAdvance = now;
            spinFrame = (spinFrame + 1) % SPIN_FRAMES;
        }
        uploadsThisFrame = 0;
        readsThisFrame = 0;
        drainUploads();
        forgetUnseen();
        seenThisFrame.clear();
    }

    private void drainUploads() {
        while (uploadsThisFrame < UPLOADS_PER_FRAME) {
            Ready ready;
            synchronized (uploadQueue) {
                ready = uploadQueue.poll();
            }
            if (ready == null) {
                return;
            }
            upload(ready);
            uploadsThisFrame++;
        }
    }

    /** Drops promotion timers for cards that have scrolled out of view. */
    private void forgetUnseen() {
        if (firstSeen.size() <= seenThisFrame.size()) {
            return;
        }
        firstSeen.keySet().retainAll(seenThisFrame);
    }

    // --------------------------------------------------------------- cards

    /**
     * The preview for a grid card.
     *
     * @param animate whether this card may be promoted to a rotating preview
     * @return the texture and slice to draw, or null while it is still being
     *         prepared; callers draw a placeholder in that case rather than
     *         blocking
     */
    public CardPreview card(LogicalModel model, int size, boolean animate) {
        String stillKey = key(model, model.defaultStateIndex(), size, false);
        seenThisFrame.add(stillKey);

        if (animate) {
            String spinKey = key(model, model.defaultStateIndex(), size, true);
            Identifier spun = textures.get(spinKey);
            if (spun != null) {
                seenThisFrame.add(spinKey);
                float slice = 1.0F / SPIN_FRAMES;
                return new CardPreview(spun, spinFrame * slice, (spinFrame + 1) * slice);
            }
            // Ask for the strip only once the card has proved it is staying.
            long since = firstSeen.computeIfAbsent(stillKey, k -> System.currentTimeMillis());
            if (System.currentTimeMillis() - since >= PROMOTE_AFTER_MILLIS) {
                request(model, model.defaultStateIndex(), size, true, 0, 0, 1.0F);
            }
        }

        Identifier still = textures.get(stillKey);
        if (still != null) {
            return new CardPreview(still, 0.0F, 1.0F);
        }
        request(model, model.defaultStateIndex(), size, false, 0, 0, 1.0F);
        return null;
    }

    /**
     * The large preview in the detail screen, at an arbitrary angle.
     *
     * <p>Angles are quantised before they become a cache key, so dragging
     * produces a bounded number of distinct renders instead of one per pixel of
     * mouse travel, and slow rotation reuses frames it has already drawn.</p>
     *
     * @param tick which moment of the model's texture animations to draw. It
     *             reaches the cache key only for a model that actually has an
     *             animated texture — for everything else a moving clock would
     *             make every frame a fresh key and there would be no cache at
     *             all
     */
    public Identifier detail(LogicalModel model, int stateIndex, int size,
                             float yaw, float pitch, float zoom, int tick) {
        float quantYaw = Math.round(yaw / 3.0F) * 3.0F;
        float quantPitch = Math.round(pitch / 3.0F) * 3.0F;
        float quantZoom = Math.round(zoom * 20.0F) / 20.0F;
        ResolvedModel resolved = model.states()
                .get(Math.min(stateIndex, model.states().size() - 1)).resolved();
        // Reduced to where it is in the cycle, not how long the screen has
        // been open. The rasterizer already loops: rowOffset takes the tick
        // modulo the frame count, so tick 7 and tick 7 + a whole period draw
        // the same image. The key did not know that, so every step of the
        // clock was a miss and an animated model re-rendered for as long as it
        // was on screen — forever, at a steady ten renders a second, for
        // frames it had already drawn. Taken modulo the period, the first
        // cycle renders and every cycle after it is free.
        int period = animationPeriod(resolved);
        int animTick = period > 1 ? Math.floorMod(tick, period) : 0;

        String key = detailKey(model, stateIndex, size, quantYaw, quantPitch, quantZoom,
                animTick);
        // Marked as in use before anything else, so a full turn of the detail
        // view cannot evict the very frame it is showing: 120 quantised angles
        // is more than the resident texture cap.
        seenThisFrame.add(key);
        Identifier existing = textures.get(key);
        if (existing != null) {
            return existing;
        }

        // Only ever one angle in flight per model. The rotation produces a new
        // angle several times a second; without this the queue fills with
        // angles that are already stale by the time they finish, none of them
        // ever matches what is on screen, and the view sits on "Rendering"
        // indefinitely while working hard.
        String prefix = detailPrefix(model, stateIndex, size);
        boolean busy = false;
        for (String pending : inFlight) {
            if (pending.startsWith(prefix)) {
                busy = true;
                break;
            }
        }
        if (!busy) {
            request(model, stateIndex, size, false, quantYaw, quantPitch, quantZoom,
                    animTick);
        }
        // Fall back to any frame already rendered for this state, so rotating
        // shows the previous angle rather than an empty box.
        return mostRecentFor(model, stateIndex, size);
    }

    private static String detailPrefix(LogicalModel model, int stateIndex, int size) {
        return model.canonicalId() + "/" + stateIndex + "/" + size + "/d";
    }

    /**
     * A frame already rendered for a state, without asking for a new one.
     *
     * <p>For the crossfade between two states: the frame being faded out has
     * been on screen for seconds and is certainly rendered, and asking for it
     * again at the exact angle of this instant would double the render rate
     * for the length of the fade to correct an angle nobody can see through a
     * dissolve. Marked as in use, so it is not evicted half way through.</p>
     *
     * @return null when the state has never been rendered at this size
     */
    public Identifier lastRendered(LogicalModel model, int stateIndex, int size) {
        return mostRecentFor(model, stateIndex, size);
    }

    private Identifier mostRecentFor(LogicalModel model, int stateIndex, int size) {
        String prefix = detailPrefix(model, stateIndex, size);
        String bestKey = null;
        Identifier best = null;
        for (Map.Entry<String, Identifier> entry : textures.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                bestKey = entry.getKey();
                best = entry.getValue();
            }
        }
        if (bestKey != null) {
            seenThisFrame.add(bestKey);
        }
        return best;
    }

    // ------------------------------------------------------------ rendering

    private void request(LogicalModel model, int stateIndex, int size, boolean spin,
                         float yaw, float pitch, float zoom) {
        request(model, stateIndex, size, spin, yaw, pitch, zoom, 0);
    }

    private void request(LogicalModel model, int stateIndex, int size, boolean spin,
                         float yaw, float pitch, float zoom, int tick) {
        String key = spin
                ? key(model, stateIndex, size, true)
                : (yaw == 0 && pitch == 0 && zoom == 1.0F && tick == 0
                ? key(model, stateIndex, size, false)
                : detailKey(model, stateIndex, size, yaw, pitch, zoom, tick));
        if (textures.containsKey(key) || !inFlight.add(key)) {
            return;
        }
        if (inFlight.size() > MAX_PENDING) {
            // The queue is already as deep as it is useful for it to be; the
            // card will ask again next frame.
            inFlight.remove(key);
            return;
        }
        ResourceSource resources = source;
        if (resources == null) {
            inFlight.remove(key);
            return;
        }
        ModelState state = model.states().get(Math.min(stateIndex, model.states().size() - 1));
        ResolvedModel resolved = state.resolved();

        // Every texture this model needs has to be decoded here, on the client
        // thread, because reading a resource pack anywhere else is what put
        // missing-texture squares on the inventory and the block-breaking
        // overlay. If they are not all ready this frame the request is dropped
        // and the card asks again next frame.
        if (!ensureTextures(resolved)) {
            inFlight.remove(key);
            return;
        }

        rendersRequested++;
        int mine = generation;

        pool().execute(() -> {
            if (mine != generation) {
                // The page turned while this sat in the queue. Nothing is
                // waiting for it.
                inFlight.remove(key);
                return;
            }
            try {
                Mesh mesh = meshFor(resolved, resources);
                if (mesh == null || mesh.isEmpty()) {
                    return;
                }
                Pixels rendered = spin
                        ? renderSpin(mesh, resolved, size)
                        : renderStill(mesh, resolved, size, yaw, pitch, zoom, tick);
                synchronized (uploadQueue) {
                    uploadQueue.add(new Ready(key, rendered));
                }
            } catch (RuntimeException | OutOfMemoryError e) {
                rendersFailed++;
                CmvMod.LOGGER.warn("Preview render failed for {}", model.displayName(), e);
            } finally {
                inFlight.remove(key);
            }
        });
    }

    private Pixels renderStill(Mesh mesh, ResolvedModel resolved, int size,
                               float yaw, float pitch, float zoom, int tick) {
        // Supersampling costs its square, and it only helps when the render is
        // coarser than the screen. Previews are now sized from the GUI scale, so
        // at anything from a normal card upwards they are already at the
        // display's own pixel density and a second sample is 3.7x the work for
        // nothing: measured at 132 pixels, 3.02 ms with it against 0.81 ms
        // without. It is kept only for the genuinely small renders.
        int supersample = size >= 96 ? 1 : 2;
        Rasterizer.View view = new Rasterizer.View(size, size, supersample,
                yaw, pitch, zoom, guiTransform(resolved), resolved.guiLightFront(), 0, tick);
        return new Pixels(size, size, Rasterizer.render(mesh, view));
    }

    /**
     * Renders a full turn into one tall image.
     *
     * <p>One texture per rotating model rather than one per frame: twenty
     * spinning cards then cost twenty textures and twenty blits, instead of
     * nearly five hundred textures and a stream of GPU allocations.</p>
     */
    private Pixels renderSpin(Mesh mesh, ResolvedModel resolved, int size) {
        int[] strip = new int[size * size * SPIN_FRAMES];
        Rasterizer.View view = new Rasterizer.View(size, size, 2,
                0, 0, 1.0F, guiTransform(resolved), resolved.guiLightFront(), 0);
        for (int frame = 0; frame < SPIN_FRAMES; frame++) {
            float yaw = frame * (360.0F / SPIN_FRAMES);
            int[] rendered = Rasterizer.render(mesh, view.withAngle(yaw, 0));
            System.arraycopy(rendered, 0, strip, frame * size * size, size * size);
        }
        return new Pixels(size, size * SPIN_FRAMES, strip);
    }

    /**
     * The transform Minecraft would use to draw this model in an inventory
     * slot, so a still preview matches what the game shows.
     */
    private static ModelJson.Transform guiTransform(ResolvedModel resolved) {
        return resolved.transform("gui");
    }

    /**
     * Decodes a model's textures, on the calling (client) thread.
     *
     * <p>Budgeted per frame: decoding a 512-pixel PNG is a millisecond or two,
     * and a grid of sixty cards arriving at once would otherwise be sixty of
     * them in a single frame. Returns false when the model is not ready yet,
     * which simply defers it.</p>
     */
    private boolean ensureTextures(ResolvedModel resolved) {
        ResourceSource source0 = source;
        if (source0 == null) {
            return false;
        }
        // Anything a previous build found it needed comes first: those are
        // textures known to be wanted, rather than guessed at from the slots.
        List<String> wanted = new ArrayList<>(resolved.distinctTextures());
        if (!wantedTextures.isEmpty()) {
            wanted.addAll(wantedTextures);
        }
        for (String textureId : wanted) {
            if (textureId == null || textureCache.containsKey(textureId)
                    || rawTextures.containsKey(textureId)) {
                // Already decoded, or already read and waiting to be decoded.
                // Not checking the second of these re-read the same files every
                // frame, which spent the whole budget going nowhere and left
                // later cards stuck on their placeholder.
                continue;
            }
            if (readsThisFrame >= READS_PER_FRAME) {
                return false;
            }
            wantedTextures.remove(textureId);
            readsThisFrame++;
            ResourceSource.TextureBytes raw = readTexture(source0, textureId);
            if (raw == null) {
                // Nothing to decode. Recorded so the mesh builder does not ask
                // for it again every frame.
                textureCache.put(textureId, MeshBuilder.missing());
                continue;
            }
            // Only the bytes are taken here. Turning them into pixels is the
            // expensive half and is pure computation, so it happens on the
            // worker rather than stalling the frame.
            rawTextures.put(textureId, raw);
        }
        return true;
    }

    /**
     * Decodes a model's textures up front, ignoring the per-frame budget.
     *
     * <p>For exports, which render off the client thread and so cannot read a
     * resource pack themselves. Call this on the client thread first; a brief
     * stall is the right trade for a deliberate, one-off action, and everything
     * it decodes is cached for the preview to reuse.</p>
     */
    public void prepareTextures(LogicalModel model, int stateIndex) {
        if (model.states().isEmpty()) {
            return;
        }
        int saved = readsThisFrame;
        readsThisFrame = Integer.MIN_VALUE;
        try {
            ensureTextures(model.states()
                    .get(Math.min(stateIndex, model.states().size() - 1)).resolved());
        } finally {
            readsThisFrame = saved;
        }
    }

    /**
     * Turns bytes read on the client thread into images, here on the worker.
     *
     * <p>The pack is not touched: {@code decodeTexture} is given bytes and
     * hands back pixels.</p>
     */
    /**
     * Reads a texture's bytes, trying where else a pack may have filed it.
     *
     * <p>This has to know about {@link ResourceSource#alternativeTextureIds}
     * as well as the mesh builder does, and for a sharper reason. A miss here
     * is written into the shared texture cache as
     * {@link MeshBuilder#missing()}, and the mesh builder checks that cache
     * before it looks anything up — so a miss recorded here is final and the
     * builder's own fallback never runs. Reading the alternative here is
     * what makes the two agree.</p>
     *
     * <p>The bytes are filed under the id the model asked for, so everything
     * downstream — the decode, the cache, the mesh — is unchanged.</p>
     */
    private static ResourceSource.TextureBytes readTexture(ResourceSource source,
                                                           String textureId) {
        ResourceSource.TextureBytes raw = readExactly(source, textureId);
        if (raw != null) {
            return raw;
        }
        for (String alternative : ResourceSource.alternativeTextureIds(textureId)) {
            raw = readExactly(source, alternative);
            if (raw != null) {
                return raw;
            }
        }
        return null;
    }

    private static ResourceSource.TextureBytes readExactly(ResourceSource source,
                                                           String textureId) {
        try {
            return source.readTextureBytes(textureId);
        } catch (IOException | RuntimeException e) {
            CmvMod.LOGGER.debug("Could not read {}", textureId, e);
            return null;
        }
    }

    private void decodePending(ResolvedModel resolved, ResourceSource resources) {
        for (String textureId : resolved.distinctTextures()) {
            if (textureId == null) {
                continue;
            }
            ResourceSource.TextureBytes raw = rawTextures.remove(textureId);
            if (raw == null) {
                continue;
            }
            ResourceSource.Image image = resources.decodeTexture(raw);
            textureCache.put(textureId, image == null ? MeshBuilder.missing() : image);
        }
    }

    private Mesh meshFor(ResolvedModel resolved, ResourceSource resources) {
        decodePending(resolved, resources);
        synchronized (meshes) {
            Mesh cached = meshes.get(resolved.modelId());
            if (cached != null) {
                return cached;
            }
        }
        MeshBuilder builder = new MeshBuilder(resources, textureCache, meshResolver);
        Mesh built = builder.build(resolved);
        if (!builder.unavailableTextures().isEmpty()) {
            // Built without textures it needed, so it is wrong and must not be
            // remembered. They are queued for the client thread to read and the
            // card asks again next frame, one frame later than it hoped.
            wantedTextures.addAll(builder.unavailableTextures());
            return null;
        }
        synchronized (meshes) {
            if (!meshes.containsKey(resolved.modelId())) {
                meshes.put(resolved.modelId(), built);
                meshBytes += built.approximateBytes();
                evictMeshes();
            }
        }
        evictTextures0();
        return built;
    }

    /**
     * Renders a frame straight to pixels, for image and animation export.
     * Runs on the calling thread, which for exports is a worker.
     */
    public int[] renderExact(LogicalModel model, int stateIndex, int size,
                             float yaw, float pitch, float zoom, int background) {
        return renderExact(model, stateIndex, size, yaw, pitch, zoom, background, 0);
    }

    /**
     * As above, at one moment of the model's texture animations.
     *
     * @param tick which moment to draw, in game ticks; 0 is the first frame of
     *             every animation, which is what a still wants
     */
    public int[] renderExact(LogicalModel model, int stateIndex, int size,
                             float yaw, float pitch, float zoom, int background,
                             int tick) {
        ResourceSource resources = source;
        if (resources == null) {
            return null;
        }
        ModelState state = model.states().get(Math.min(stateIndex, model.states().size() - 1));
        ResolvedModel resolved = state.resolved();
        Mesh mesh = meshFor(resolved, resources);
        if (mesh == null || mesh.isEmpty()) {
            return null;
        }
        Rasterizer.View view = new Rasterizer.View(size, size, size >= 256 ? 2 : 3,
                yaw, pitch, zoom, guiTransform(resolved), resolved.guiLightFront(),
                background, tick);
        return Rasterizer.render(mesh, view);
    }

    /**
     * The tick count a model's textures repeat on, for a caller outside here.
     *
     * <p>So an export can be made to loop: an animation that stops part way
     * through a flicker jumps when it starts again, and the length it has to
     * be a whole number of is this.</p>
     *
     * @return 1 when nothing animates, which needs no accommodating
     */
    public int animationPeriod(LogicalModel model, int stateIndex) {
        if (model == null || model.states().isEmpty()) {
            return 1;
        }
        ModelState state = model.states().get(Math.min(stateIndex, model.states().size() - 1));
        return animationPeriod(state.resolved());
    }

    /**
     * The tick count several states' textures all repeat on, together.
     *
     * <p>For an export that walks a model's draw stages: each stage has its
     * own textures and so its own cycle, and a length that is a whole number
     * of every one of them is the only length on which all of them line up.
     * Capped like the single-state answer, since the least common multiple of
     * a few unrelated cycles grows quickly and a cap is better than a file
     * nobody waits for.</p>
     */
    public int animationPeriod(LogicalModel model, java.util.List<Integer> stateIndices) {
        if (stateIndices == null || stateIndices.isEmpty()) {
            return 1;
        }
        int period = 1;
        for (Integer index : stateIndices) {
            if (index == null) {
                continue;
            }
            period = lcm(period, animationPeriod(model, index));
            if (period >= LONGEST_CYCLE) {
                return LONGEST_CYCLE;
            }
        }
        return period;
    }

    private ExecutorService pool() {
        ExecutorService current = workers;
        if (current == null || current.isShutdown()) {
            synchronized (this) {
                if (workers == null || workers.isShutdown()) {
                    // Half the cores, at least two and at most four. Two was a
                    // bottleneck once previews grew to display resolution; the
                    // cap keeps a scan or the game itself from being starved.
                    int threads = Math.max(2, Math.min(4,
                            Runtime.getRuntime().availableProcessors() / 2));
                    workers = Executors.newFixedThreadPool(threads, FACTORY);
                }
                current = workers;
            }
        }
        return current;
    }

    // -------------------------------------------------------------- uploads

    private void upload(Ready ready) {
        Pixels image = ready.image();
        try {
            NativeImage native0 = new NativeImage(image.width(), image.height(), false);
            for (int y = 0; y < image.height(); y++) {
                int row = y * image.width();
                for (int x = 0; x < image.width(); x++) {
                    native0.setPixel(x, y, image.argb()[row + x]);
                }
            }
            Identifier id = McCompat.modId("preview/" + Integer.toHexString(
                    ready.key().hashCode()) + "_" + (uploadCounter++));
            DynamicTexture texture = new DynamicTexture(() -> "cmv-preview", native0);
            McCompat.mc().getTextureManager().register(id, texture);

            // A key can be evicted while its render is still in flight and then
            // requested again, so an entry may already exist. Release it rather
            // than overwriting the map and leaking the texture behind it.
            Identifier previous = textures.put(ready.key(), id);
            if (previous != null) {
                try {
                    McCompat.mc().getTextureManager().release(previous);
                } catch (RuntimeException e) {
                    CmvMod.LOGGER.debug("Could not release replaced preview texture", e);
                }
            }
            Pixels replaced = pixels.put(ready.key(), image);
            if (replaced != null) {
                pixelBytes -= replaced.bytes();
            }
            pixelBytes += image.bytes();
            rendersCompleted++;
            evictTextures();
            evictPixels();
        } catch (RuntimeException | OutOfMemoryError e) {
            CmvMod.LOGGER.debug("Could not upload preview", e);
        }
    }

    private int uploadCounter;

    // -------------------------------------------------------------- eviction

    /**
     * Drops the least recently used textures once too many are resident.
     *
     * <p>The maps are access-ordered, so their iteration order is oldest first
     * and eviction is a walk from the front. Anything drawn this frame is kept
     * regardless, because evicting a texture the screen is about to ask for
     * again would produce a flicker and an immediate re-render.</p>
     */
    private void evictTextures() {
        if (textures.size() <= MAX_LIVE_TEXTURES) {
            return;
        }
        Iterator<Map.Entry<String, Identifier>> iterator = textures.entrySet().iterator();
        while (iterator.hasNext() && textures.size() > MAX_LIVE_TEXTURES) {
            Map.Entry<String, Identifier> entry = iterator.next();
            if (seenThisFrame.contains(entry.getKey())) {
                continue;
            }
            try {
                McCompat.mc().getTextureManager().release(entry.getValue());
            } catch (RuntimeException e) {
                CmvMod.LOGGER.debug("Could not release preview texture", e);
            }
            iterator.remove();
        }
    }

    private void evictPixels() {
        Iterator<Map.Entry<String, Pixels>> iterator = pixels.entrySet().iterator();
        while (iterator.hasNext() && pixelBytes > MAX_PIXEL_BYTES) {
            Map.Entry<String, Pixels> entry = iterator.next();
            if (textures.containsKey(entry.getKey())) {
                continue;
            }
            pixelBytes -= entry.getValue().bytes();
            iterator.remove();
        }
    }

    /**
     * Keeps the shared texture cache within budget.
     *
     * <p>Recounts rather than tracking incrementally: the map is written from
     * several worker threads through a synchronised wrapper, and a running
     * total maintained across them would drift. A recount is a walk over a few
     * hundred entries and only happens after a mesh is built.</p>
     */
    private void evictTextures0() {
        synchronized (textureCache) {
            long total = 0;
            for (ResourceSource.Image image : textureCache.values()) {
                total += (long) image.argb().length * 4;
            }
            textureBytes = total;
            if (total <= MAX_TEXTURE_BYTES) {
                return;
            }
            Iterator<Map.Entry<String, ResourceSource.Image>> iterator =
                    textureCache.entrySet().iterator();
            while (iterator.hasNext() && total > MAX_TEXTURE_BYTES) {
                total -= (long) iterator.next().getValue().argb().length * 4;
                iterator.remove();
            }
            textureBytes = total;
        }
    }

    private void evictMeshes() {
        Iterator<Map.Entry<String, Mesh>> iterator = meshes.entrySet().iterator();
        while (iterator.hasNext() && meshBytes > MAX_MESH_BYTES) {
            Map.Entry<String, Mesh> entry = iterator.next();
            meshBytes -= entry.getValue().approximateBytes();
            iterator.remove();
        }
    }

    // ----------------------------------------------------------------- keys

    private static String key(LogicalModel model, int stateIndex, int size, boolean spin) {
        return model.canonicalId() + "/" + stateIndex + "/" + size + (spin ? "/s" : "/f");
    }

    private static String detailKey(LogicalModel model, int stateIndex, int size,
                                    float yaw, float pitch, float zoom, int tick) {
        return model.canonicalId() + "/" + stateIndex + "/" + size + "/d"
                + (int) yaw + "_" + (int) pitch + "_" + (int) (zoom * 20)
                + (tick == 0 ? "" : "_t" + tick);
    }

    /**
     * The longest cycle that will be cached whole.
     *
     * <p>Two textures whose periods share no factor multiply out, and a pair
     * of unlucky primes would ask for a cycle thousands of frames long that
     * would never finish caching and would fill the budget trying. Past the
     * cap the cycle is allowed to be imperfect, which costs a visible jump
     * once every thirty seconds on a model nobody has yet shipped.</p>
     */
    private static final int LONGEST_CYCLE = 600;

    /**
     * The tick count a model's textures repeat on, or 1 when none animates.
     *
     * <p>The least common multiple of each animated texture's own period, so a
     * model wearing a four-frame texture and a six-frame one comes back round
     * on twelve rather than appearing to drift.</p>
     */
    int animationPeriod(ResolvedModel resolved) {
        int period = 1;
        for (String textureId : resolved.distinctTextures()) {
            if (textureId == null) {
                continue;
            }
            ResourceSource.Image decoded = textureCache.get(textureId);
            if (decoded == null || decoded.frames() <= 1) {
                continue;
            }
            period = lcm(period, decoded.frames() * Math.max(1, decoded.frameTime()));
            if (period >= LONGEST_CYCLE) {
                return LONGEST_CYCLE;
            }
        }
        return evenPeriod(period);
    }

    /**
     * A cycle length the detail view's clock can land on exactly.
     *
     * <p>That clock steps two ticks at a time. Against an odd period it lands
     * on the odd residues one time round and the even ones the next, so the
     * set of cached frames is never complete and a view waiting for the set
     * would wait forever. Doubling is the only correction available, because
     * the answer has to stay a multiple of the true period — a texture's frame
     * is its tick modulo its frame count, so any multiple draws exactly the
     * same images and only evenness changes.</p>
     *
     * <p>It may come out past {@link #LONGEST_CYCLE}, which is allowed: a
     * longer cycle means more frames, and more frames shrink the size they are
     * drawn at, so the memory is bounded by {@link #detailPixels} either
     * way.</p>
     */
    public static int evenPeriod(int period) {
        return period > 1 && period % 2 != 0 ? period * 2 : period;
    }

    /**
     * Whether every frame this model needs at this angle and size is drawn.
     *
     * <p>What the detail view waits for before it swaps a sharp render in for
     * the small one it is already showing. Asking "is this one frame ready" is
     * not enough for a model whose textures animate: its frames arrive one at
     * a time, so the view would flicker between sharp and soft for a whole
     * cycle. It waits for the set.</p>
     */
    public boolean cycleReady(LogicalModel model, int stateIndex, int size,
                             float yaw, float pitch, float zoom) {
        float quantYaw = Math.round(yaw / 3.0F) * 3.0F;
        float quantPitch = Math.round(pitch / 3.0F) * 3.0F;
        float quantZoom = Math.round(zoom * 20.0F) / 20.0F;
        ResolvedModel resolved = model.states()
                .get(Math.min(stateIndex, model.states().size() - 1)).resolved();
        int period = animationPeriod(resolved);
        if (period <= 1) {
            return textures.containsKey(detailKey(model, stateIndex, size,
                    quantYaw, quantPitch, quantZoom, 0));
        }
        for (int tick = 0; tick < period; tick += 2) {
            if (!textures.containsKey(detailKey(model, stateIndex, size,
                    quantYaw, quantPitch, quantZoom, tick))) {
                return false;
            }
        }
        return true;
    }

    private static int lcm(int a, int b) {
        long gcd = Math.min(a, b);
        long other = Math.max(a, b);
        while (gcd != 0) {
            long rest = other % gcd;
            other = gcd;
            gcd = rest;
        }
        return (int) Math.min(LONGEST_CYCLE, (long) a / other * b);
    }

    /**
     * How many distinct frames one full cycle of a model's textures will cache.
     *
     * <p>Not the period: the detail view's clock steps two ticks at a time, so
     * a twenty-tick cycle produces ten distinct images and not twenty. The
     * at-rest render size is chosen from this, so that a whole cycle fits in
     * memory at the size it is drawn.</p>
     */
    public int animationSteps(LogicalModel model, int stateIndex) {
        ResolvedModel resolved = model.states()
                .get(Math.min(stateIndex, model.states().size() - 1)).resolved();
        return Math.max(1, animationPeriod(resolved) / 2);
    }

    /**
     * How large the detail view should be rendered.
     *
     * <p>Static and taking everything it needs, so a harness can walk it.</p>
     *
     * <p>A moving preview asks for a fresh render every three degrees, so it
     * has to stay small. A still one asks for one render and then nothing, so
     * it is rendered at the size it is displayed at and the detail in a
     * detailed model is finally visible. A still one whose textures animate
     * asks for a whole cycle instead of one frame, so its size comes down
     * until that cycle fits the budget — which is what makes an animated model
     * cost a single cycle of renders rather than renders forever.</p>
     *
     * @param box       the square it is drawn into, in GUI pixels
     * @param guiScale  physical pixels per GUI pixel
     * @param moving    true while turning, dragging or settling
     * @param frames    distinct frames one cycle caches; 1 when nothing animates
     * @param movingCap the most a moving preview may be
     * @param stillCap  the most a still one may be
     * @param budget    bytes one model's cached cycle may fill; see
     *                  {@link #cycleBudget()}
     */
    public static int detailPixels(int box, int guiScale, boolean moving, int frames,
                                   int movingCap, int stillCap, long budget) {
        int want = Math.max(1, box) * Math.max(1, guiScale);
        int cap = movingCap;
        if (!moving) {
            cap = stillCap;
            if (frames > 1) {
                int fits = (int) Math.sqrt(budget / (4.0 * frames));
                // Never below what it was while moving: sharpening into
                // something blurrier than the spin would be worse than not
                // sharpening at all.
                cap = Math.max(movingCap, Math.min(cap, fits));
            }
        }
        return Math.max(16, Math.min(cap, want));
    }
}
