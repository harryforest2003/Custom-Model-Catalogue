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
 *       promoted to a moving one only after they have stayed visible, and only
 *       when their textures actually animate. Scrolling quickly past a hundred
 *       cards therefore costs a hundred still frames.</li>
 *   <li>An animation is a strip of pre-rendered moments in a single texture.
 *       Once built, playing a card costs one blit per frame and no rendering
 *       at all.</li>
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
     * The most moments a card's animation strip holds.
     *
     * <p>A strip costs this many renders where a still costs one, and previews
     * are rendered at display resolution rather than at 48 pixels, so a high
     * count means one card can ask for more work than the whole grid used to.
     * A texture whose cycle is longer than this is sampled across it, so a long
     * animation skips moments rather than playing slowly \u2014 which is the
     * right way round for a flame.</p>
     */
    public static final int CARD_FRAMES = 24;

    /** How long a card must stay visible before it earns an animation strip. */
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
    /**
     * One shared animation clock, counted in Minecraft ticks.
     *
     * <p>Ticks rather than wall-clock frames because a texture's
     * {@code frameTime} is written in ticks: this is the unit that makes a
     * four-tick flame flicker at the speed its author chose.</p>
     */
    private int animTick;
    private long lastAnimAdvance;
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
     * Call once per frame before drawing. Advances the shared animation clock
     * and spends the frame's upload budget.
     */
    public void beginFrame() {
        long now = System.currentTimeMillis();
        // One shared clock for every animated preview on screen, rather than a
        // timer per card. Cards stay in phase, which also looks better.
        if (now - lastAnimAdvance >= 50) {
            lastAnimAdvance = now;
            animTick++;
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

        // Only a model whose textures actually move earns a strip. The card
        // used to turn instead, which cost a dozen renders for every model in
        // the grid whether or not there was anything to see, and turning is not
        // what the pack drew. This costs renders only where something moves,
        // and what it shows is the flame flickering.
        int period = animate ? animationPeriod(model, model.defaultStateIndex()) : 1;
        if (period > 1) {
            String stripKey = key(model, model.defaultStateIndex(), size, true);
            Identifier strip = textures.get(stripKey);
            if (strip != null) {
                seenThisFrame.add(stripKey);
                int frames = Math.min(period, CARD_FRAMES);
                int frame = (int) ((long) animTick * frames / period) % frames;
                float slice = 1.0F / frames;
                return new CardPreview(strip, frame * slice, (frame + 1) * slice);
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
        // was on screen - forever, at a steady ten renders a second, for
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

    /** The eight positions an item frame will hold an item in. */
    public static final int FRAME_POSITIONS = 8;

    /**
     * The camera angle for a frame on a wall: straight at it.
     *
     * <p>A frame on a wall is looked at from in front, so there is nothing to
     * choose here and the number is written down only to be the other half of
     * a pair.</p>
     */
    public static final float WALL_PITCH = 0.0F;

    /**
     * How far above a frame on the floor the viewer stands, in degrees.
     *
     * <p>Ten, which is somebody looking across a room at something on the
     * ground rather than standing over it. A frame on a wall is looked at
     * straight on, so this applies to the floor alone.</p>
     */
    public static final float FLOOR_ELEVATION = 10.0F;

    /**
     * The camera angle for a frame lying flat, on the floor or on a table.
     *
     * <p>A frame hung flat is the same frame rotated a quarter turn onto its
     * back, so the camera's pitch and the frame's mounting are one rotation
     * about one axis and one number does both.</p>
     *
     * <p>It is a quarter turn <em>plus</em> the viewer's own elevation, which
     * is the half of this that cannot be reasoned out and was wrong twice.
     * The quarter turn alone lays the frame on its back and leaves the camera
     * exactly in its plane, looking along the table at an edge. Going round
     * by the elevation as well lifts the camera {@value #FLOOR_ELEVATION}
     * degrees past that plane, onto the side the opening faces, which is
     * somebody looking across a room at something on the ground. Stopping
     * short of the quarter turn instead — ninety less ten — lowers the camera
     * by the same ten the other way, under the table, where the frame is a
     * lid seen from beneath and nothing can be seen inside it. Both signs of
     * eighty are that picture; the number has to pass ninety, not approach
     * it.</p>
     *
     * <p>Not the quarter turn itself either, which looks straight down the
     * frame's own plane and draws an edge rather than a scene.</p>
     *
     * <p>Paired with {@link #mountYaw}: this reads as an elevation only from
     * a camera at yaw zero, so the two are chosen together.</p>
     */
    public static final float FLOOR_PITCH = 90.0F + FLOOR_ELEVATION;

    /** The camera angle for one of the two mountings. */
    public static float mountPitch(boolean onFloor) {
        return onFloor ? FLOOR_PITCH : WALL_PITCH;
    }

    /**
     * Which side of the frame the camera stands on, for one of the two
     * mountings.
     *
     * <p>Always the side the frame opens towards; it is only the mounting
     * that decides where that is. A frame on a wall opens at the viewer, and
     * because {@code block/item_frame} is modelled against the back of its
     * cell — plate at z 15.5 to 16, border standing proud of it down to 15 —
     * the opening faces -Z and the camera has to go round to {@link
     * #FRAME_YAW} to be in front of it rather than behind. A frame lying flat
     * opens at the sky, so the camera is beside it either way, and the half
     * turn is spent instead on the side the model faces: a pack stands its
     * models up for a floor frame with a {@code fixed} rotation of -90 about
     * X, which lays the model's front onto the item's +Y, and the camera at
     * yaw zero is the one looking at it.</p>
     */
    public static float mountYaw(boolean onFloor) {
        return onFloor ? 0.0F : FRAME_YAW;
    }

    /**
     * How much smaller than its model an item is drawn inside a frame.
     *
     * <p>A half, which is what the game does: a sixteen-unit model is drawn
     * eight units across in a frame, which is why an ordinary item sits
     * comfortably inside one.</p>
     */
    public static final float FRAMED_ITEM_SCALE = 0.5F;

    /**
     * The turn that puts the camera in front of a frame on a wall rather than
     * behind it.
     *
     * <p>{@code block/item_frame} is modelled against the back of its cell:
     * the plate spans z 15.5 to 16 and the border stands proud of it down to
     * 15, so the frame opens towards -Z and the camera, which looks down -Z,
     * starts out behind it. A view from behind is a mirror, which is why the
     * eight positions ran anticlockwise: not because the spin was computed
     * the wrong way round, but because it was being watched from the back of
     * the wall.</p>
     */
    public static final float FRAME_YAW = 180.0F;

    /**
     * How a frame and the item in it are drawn as one scene.
     *
     * @param extent  how much of the world the picture covers, in model units
     * @param frameAt where the frame's own render is centred
     * @param itemAt  where the item's render is centred, in the item's units
     */
    public record Placement(float extent, Rasterizer.View.Anchor frameAt,
                            Rasterizer.View.Anchor itemAt) {

        /** What the item's render asks for, it being drawn half the size. */
        public float itemExtent() {
            return extent / FRAMED_ITEM_SCALE;
        }
    }

    /**
     * Where to put a frame and the item in it so they read as one thing.
     *
     * <p>Two things have to be true at once, and each was got wrong on its
     * own first.</p>
     *
     * <p><b>One scale.</b> Every other preview is fitted to its own image, so
     * a model the size of a house and a model the size of a pea both fill the
     * card — right when the picture is of one thing and a lie when it is
     * of two. Fitted together, a model that would swallow an item frame is
     * shown swallowing one.</p>
     *
     * <p><b>One place.</b> Scale alone is not enough: centred on themselves,
     * the two are drawn through each other, and the frame comes out as a bar
     * across the middle of the model rather than the thing it sits in. So the
     * item is laid against the frame's face, on the frame's axis and on the
     * side it opens towards — which for a frame on a wall puts the model in
     * front of it, and for one lying flat puts the frame underneath, like an
     * ornament on a table.</p>
     *
     * <p>The scene is then fitted as a whole, which is why this returns the
     * extent rather than taking it. Fitting to the frame alone was enough
     * while the two shared a centre; once the item stands on the frame
     * instead of inside it, the pair is half as tall again and a tall model
     * ran off the top of the picture.</p>
     *
     * <p>The item's reach is taken as the sweep of its box about the frame's
     * axis, because it turns about that axis through the eight positions and
     * a fit that ignored the turn would let a long model swing out of view at
     * forty-five degrees.</p>
     *
     * @param frameBox the frame's box in item units, from
     *                 {@link Rasterizer#boundsOf}
     * @param itemBox  the item's box, under its own {@code fixed} transform
     */
    public static Placement place(float[] frameBox, float[] itemBox) {
        float scale = FRAMED_ITEM_SCALE;
        // The item's back against the frame's face, on the side the frame
        // opens towards. A frame's plate is at the back of its block cell and
        // its border stands proud of it towards -Z, so the opening faces -Z
        // and the item goes below the plate's smallest z, not above its
        // largest. Getting this the wrong way round put the camera behind the
        // frame, which is a mirror: the eight positions ran backwards and a
        // model the pack had stood up came out head first into the plate.
        float lift = frameBox[2] - itemBox[5] * scale;

        float reachX = Math.max(Math.abs(itemBox[0]), Math.abs(itemBox[3]));
        float reachY = Math.max(Math.abs(itemBox[1]), Math.abs(itemBox[4]));
        float sweep = (float) Math.hypot(reachX, reachY) * scale;
        float halfX = Math.max(
                Math.max(Math.abs(frameBox[0]), Math.abs(frameBox[3])), sweep);
        float halfY = Math.max(
                Math.max(Math.abs(frameBox[1]), Math.abs(frameBox[4])), sweep);
        float lowZ = Math.min(frameBox[2], lift + itemBox[2] * scale);
        float highZ = Math.max(frameBox[5], lift + itemBox[5] * scale);
        float middleZ = (lowZ + highZ) * 0.5F;
        float halfZ = (highZ - lowZ) * 0.5F;

        float extent = Math.max(0.001F, (float) Math.sqrt(
                halfX * halfX + halfY * halfY + halfZ * halfZ));
        return new Placement(extent,
                new Rasterizer.View.Anchor(0.0F, 0.0F, middleZ),
                new Rasterizer.View.Anchor(0.0F, 0.0F, (middleZ - lift) / scale));
    }

    /**
     * The item alone, for a pack whose frame cannot be drawn.
     *
     * <p>Fitted and centred on itself, which is what every other preview
     * does. The pair's shared scale is the better picture, and a blank panel
     * is not a price worth paying for missing it.</p>
     */
    public static Placement alone(float[] itemBox) {
        float reach = 0.0F;
        for (int axis = 0; axis < 3; axis++) {
            float half = (itemBox[axis + 3] - itemBox[axis]) * 0.5F;
            float middle = (itemBox[axis + 3] + itemBox[axis]) * 0.5F;
            reach += (half + Math.abs(middle)) * (half + Math.abs(middle));
        }
        return new Placement(
                Math.max(0.001F, (float) Math.sqrt(reach)) * FRAMED_ITEM_SCALE,
                null, null);
    }

    /**
     * A model's box as a frame would hold it, or null while it is measured.
     *
     * <p>Measured from the built mesh, which is the only thing that knows:
     * a resolved model keeps the count of its cuboids and not the cuboids
     * themselves, deliberately, because keeping them cost hundreds of
     * megabytes on a large pack. So the first ask for a model that has never
     * been drawn queues an ordinary render to get the mesh built, returns
     * nothing, and the view asks again next frame.</p>
     *
     * <p>A box rather than a radius, because the pair needs both halves of
     * what a box says: how big to draw the model, and where its back is. A
     * sphere has no back.</p>
     */
    private float[] framedBox(LogicalModel model, int stateIndex, int size,
                              ResolvedModel resolved) {
        float[] known = itemBoxes.get(resolved.modelId());
        if (known != null) {
            return known;
        }
        Mesh mesh;
        synchronized (meshes) {
            mesh = meshes.get(resolved.modelId());
        }
        if (mesh == null) {
            // Nothing measures a model that has not been built, so ask for the
            // picture that builds it. The ordinary still is the cheapest one
            // and is wanted anyway: it is what the card behind this view
            // shows.
            request(model, stateIndex, size, false, 0.0F, 0.0F, 1.0F, 0, Shot.GUI);
            return null;
        }
        float[] measured = Rasterizer.boundsOf(mesh, resolved.transform("fixed"));
        itemBoxes.put(resolved.modelId(), measured);
        return measured;
    }

    /**
     * The frame model's box, measured once.
     *
     * <p>This asks for its own measurement rather than waiting for one, and
     * that is the whole of it. Waiting was a deadlock: the frame is not drawn
     * until the shared scale is known, the scale needs this measurement,
     * it is read off the frame's built mesh, and the only thing that built
     * that mesh was the render that was waiting for the scale. Every model's
     * framed view sat on "Rendering..." for ever, and nothing in the log said
     * why, because nothing had failed — three correct steps in a ring.</p>
     *
     * <p>The item half of the pair never had the problem: its mesh is built by
     * the ordinary card still, which is wanted anyway. The frame model is in no
     * pack's index and no card asks for it, so it has to be asked for here.</p>
     */
    private float[] frameBounds() {
        if (frameBox != null) {
            return frameBox;
        }
        Mesh mesh = null;
        ResolvedModel frame = frameModel;
        if (frame != null) {
            synchronized (meshes) {
                mesh = meshes.get(frame.modelId());
            }
        }
        if (mesh == null) {
            measureFrame();
            return null;
        }
        frameBox = Rasterizer.boundsOf(mesh, ModelJson.Transform.IDENTITY);
        return frameBox;
    }

    /**
     * Builds the frame's mesh so it can be measured, once.
     *
     * <p>On the worker pool, because building a mesh walks the model and reads
     * its textures and that is not work for the frame the player is looking
     * at. Counted against the same budget the drawn frame uses: a pack whose
     * item frame cannot be read gives up rather than asking again for the rest
     * of the session.</p>
     */
    private void measureFrame() {
        if (frameGaveUp() || measuringFrame) {
            return;
        }
        ResourceSource resources = source;
        dev.custommodels.cmv.explorer.res.ModelResolver resolver = meshResolver;
        if (resources == null || resolver == null) {
            return;
        }
        if (frameModel == null) {
            frameModel = resolver.resolve(ITEM_FRAME_MODEL);
        }
        ResolvedModel frame = frameModel;
        if (frame == null || frame.isEmpty()) {
            frameTries = FRAME_TRIES + 1;
            return;
        }
        if (!ensureTextures(frame)) {
            // Its textures are being read. Counted, so "ask again next frame"
            // runs out rather than going round for ever.
            frameTries++;
            return;
        }
        measuringFrame = true;
        pool().execute(() -> {
            try {
                Mesh mesh = meshFor(frame, resources);
                if (mesh != null && !mesh.isEmpty()) {
                    frameBox = Rasterizer.boundsOf(mesh, ModelJson.Transform.IDENTITY);
                }
            } catch (RuntimeException | OutOfMemoryError e) {
                CmvMod.LOGGER.warn("Item frame measurement failed", e);
            } finally {
                measuringFrame = false;
            }
        });
    }

    /**
     * Whether the measurement is already on the pool.
     *
     * <p>Its own flag rather than a key in the in-flight set, because that set
     * holds the keys of pictures being drawn and this draws nothing. Asked for
     * only from the render thread, so one flag is enough; volatile for the
     * worker that clears it.</p>
     */
    private volatile boolean measuringFrame;

    /** What each model measured, so it is measured once rather than per frame. */
    private final java.util.Map<String, float[]> itemBoxes =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** The frame's own box in item units, measured once. */
    private volatile float[] frameBox;

    /** The model the game draws an item frame with. */
    private static final String ITEM_FRAME_MODEL = "minecraft:block/item_frame";

    /** That model, resolved once, or null until it has been asked for. */
    private volatile ResolvedModel frameModel;

    /**
     * How many frames the backdrop has been waited on.
     *
     * <p>Texture reads are spread over frames by design, so a first answer of
     * "not yet" is normal and has to be retried. A hundredth answer of "not
     * yet" is a pack that cannot draw a frame, and retrying that for the rest
     * of the session is the per-frame work this counter exists to stop.</p>
     */
    private int frameTries;

    /** How many frames to wait for the backdrop before leaving it out. */
    private static final int FRAME_TRIES = 100;

    /**
     * A model as an item frame would hold it, in one of the frame's eight
     * positions.
     *
     * <p>Two things make this a different picture from the ordinary preview
     * rather than the same one turned round. The transform is {@code fixed},
     * which is the one Minecraft uses for a framed item and which a pack is
     * free to set to something quite unlike its {@code gui} entry — so this
     * is the view that answers "does my model sit right in a frame", a
     * question the inventory render cannot. And the turn is a roll about the
     * axis pointing at the viewer, because the frame hangs on a wall facing
     * you and its positions spin the item in the plane of that wall.</p>
     *
     * <p>The camera does not move: every position is the same head-on view of
     * a frame on a wall.</p>
     *
     * @param slot which of the eight positions, counted the way the frame
     *             counts them, 45 degrees apart
     * @return the texture, or the last position drawn at this size while this
     *         one renders, or null when nothing has been drawn yet
     */
    public Identifier framed(LogicalModel model, int stateIndex, int size, int slot,
                             int tick, boolean onFloor) {
        ResolvedModel resolved = model.states()
                .get(Math.min(stateIndex, model.states().size() - 1)).resolved();
        int period = animationPeriod(resolved);
        int animTick = period > 1 ? Math.floorMod(tick, period) : 0;
        Placement placed = placementFor(model, stateIndex, size, resolved);
        if (placed == null) {
            // Still measuring. The measurement queues the render that builds
            // the mesh, so this resolves itself within a frame or two.
            return mostRecentWith(shotPrefix(model, stateIndex, size) + mounting(onFloor));
        }
        Shot shot = new Shot("fixed",
                Math.floorMod(slot, FRAME_POSITIONS) * (360.0F / FRAME_POSITIONS),
                mountPitch(onFloor), placed.itemExtent(), placed.itemAt());
        String key = shotKey(model, stateIndex, size, shot, animTick);
        seenThisFrame.add(key);
        Identifier existing = textures.get(key);
        if (existing != null) {
            return existing;
        }
        request(model, stateIndex, size, false, mountYaw(onFloor), 0.0F, 1.0F,
                animTick, shot);
        // Eight positions at one size is a small enough set to hold, so
        // stepping through them is instant after the first pass round. Until
        // then the position before it is shown rather than a message: the
        // cycle moves on its own, and a word appearing for one position in
        // eight reads as a fault rather than as work in progress.
        return mostRecentWith(shotPrefix(model, stateIndex, size) + mounting(onFloor));
    }

    /**
     * The item frame itself, drawn face-on, to put the model inside.
     *
     * <p>The game's own {@code block/item_frame} rather than a drawing of one,
     * so it is birch planks and the frame's own backing texture, and so a pack
     * that reskins item frames shows its own frame here. Resolved on demand
     * through the mesh resolver, which is the same path the models themselves
     * take; a pack with no frame texture simply means this returns null and
     * the view draws the model without it.</p>
     */
    public Identifier itemFrame(LogicalModel model, int stateIndex, int size,
                                boolean onFloor) {
        ResolvedModel resolved = model.states()
                .get(Math.min(stateIndex, model.states().size() - 1)).resolved();
        Placement placed = placementFor(model, stateIndex, size, resolved);
        if (placed == null || placed.frameAt() == null) {
            return null;
        }
        // The frame's picture depends on the item now, because the two are
        // drawn to one scale: a frame beside a model that dwarfs it is a
        // smaller frame. So the item's measurement is part of the key.
        String key = "itemframe/" + size + "/" + mounting(onFloor)
                + "/x" + Math.round(placed.extent() * 16.0F);
        seenThisFrame.add(key);
        Identifier existing = textures.get(key);
        if (existing != null) {
            return existing;
        }
        if (frameGaveUp()) {
            // Given up on. Without this the view would resolve a model and
            // walk its textures on every frame, for ever, for a pack whose
            // frame cannot be drawn at all - which is the one case where the
            // work is both useless and unending.
            return null;
        }
        ResourceSource resources = source;
        dev.custommodels.cmv.explorer.res.ModelResolver resolver = meshResolver;
        if (resources == null || resolver == null || !inFlight.add(key)) {
            return null;
        }
        // Resolved once and kept. Resolving reads the model and walks its
        // parents, which is not work to repeat per frame while the first
        // render is in flight.
        if (frameModel == null) {
            frameModel = resolver.resolve(ITEM_FRAME_MODEL);
        }
        ResolvedModel frame = frameModel;
        if (frame == null || frame.isEmpty()) {
            frameTries = FRAME_TRIES + 1;
            inFlight.remove(key);
            return null;
        }
        if (!ensureTextures(frame)) {
            // Its textures are being read; ask again next frame. Counted,
            // because "next frame" has to run out eventually.
            frameTries++;
            inFlight.remove(key);
            return null;
        }
        rendersRequested++;
        pool().execute(() -> {
            try {
                Mesh mesh = meshFor(frame, resources);
                if (mesh == null || mesh.isEmpty()) {
                    return;
                }
                // Face-on and unturned: the frame is the backdrop and only the
                // item inside it moves.
                // Unturned, and hung the way the item inside it is: a
                // tilted item in an untilted frame is two pictures of two
                // different frames.
                Pixels rendered = renderStill(mesh, frame, size, mountYaw(onFloor),
                        0.0F, 1.0F, 0,
                        new Shot("fixed", 0.0F, mountPitch(onFloor),
                                placed.extent(), placed.frameAt()));
                synchronized (uploadQueue) {
                    uploadQueue.add(new Ready(key, rendered));
                }
            } catch (RuntimeException | OutOfMemoryError e) {
                rendersFailed++;
                CmvMod.LOGGER.warn("Item frame render failed", e);
            } finally {
                inFlight.remove(key);
            }
        });
        return null;
    }

    /**
     * The transform Minecraft would use to draw this model in an inventory
     * slot, so a still preview matches what the game shows.
     *
     * <p>Still the right answer for the animation strip and for an exported
     * image, which are both the inventory view in motion. The framed view
     * asks for {@code fixed} instead, through its own shot.</p>
     */
    private static ModelJson.Transform guiTransform(ResolvedModel resolved) {
        return resolved.transform("gui");
    }

    private static String shotPrefix(LogicalModel model, int stateIndex, int size) {
        return model.canonicalId() + "/" + stateIndex + "/" + size + "/r";
    }

    /**
     * How this model and a frame are drawn together, or null while either of
     * them is still being measured.
     */
    private Placement placementFor(LogicalModel model, int stateIndex, int size,
                                   ResolvedModel resolved) {
        float[] item = framedBox(model, stateIndex, size, resolved);
        if (item == null) {
            return null;
        }
        float[] frame = frameBounds();
        if (frame != null) {
            return place(frame, item);
        }
        return frameGaveUp() ? alone(item) : null;
    }

    /** Whether the frame has been waited on as long as it is going to be. */
    private boolean frameGaveUp() {
        return frameTries > FRAME_TRIES;
    }

    private static String shotKey(LogicalModel model, int stateIndex, int size,
                                  Shot shot, int tick) {
        // The mounting is in the prefix rather than after the spin, so that the
        // fallback while a position renders can ask for "any position of this
        // model at this size on this mounting" and not be handed the other
        // mounting's picture.
        return shotPrefix(model, stateIndex, size) + mounting(shot.pitch() != WALL_PITCH)
                + shot.context() + (int) shot.spin()
                + "x" + Math.round(shot.extent() * 16.0F)
                + (tick == 0 ? "" : "_t" + tick);
    }

    private static String mounting(boolean onFloor) {
        return onFloor ? "floor" : "wall";
    }

    private Identifier mostRecentFor(LogicalModel model, int stateIndex, int size) {
        return mostRecentWith(detailPrefix(model, stateIndex, size));
    }

    private Identifier mostRecentWith(String prefix) {
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

    private void request(LogicalModel model, int stateIndex, int size, boolean strip,
                         float yaw, float pitch, float zoom) {
        request(model, stateIndex, size, strip, yaw, pitch, zoom, 0);
    }

    private void request(LogicalModel model, int stateIndex, int size, boolean strip,
                         float yaw, float pitch, float zoom, int tick) {
        request(model, stateIndex, size, strip, yaw, pitch, zoom, tick, Shot.GUI);
    }

    /**
     * Which display transform a render uses, and how far it is rolled.
     *
     * <p>Two renders of one model at one size and one angle are still
     * different pictures when these differ, so they are part of the cache key.
     * Without that, a frame render at the default angle and the plain still at
     * the default angle have the same key and whichever arrived first is what
     * both views show.</p>
     */
    private record Shot(String context, float spin, float pitch, float extent,
                        Rasterizer.View.Anchor anchor) {

        Shot(String context, float spin, float pitch, float extent) {
            this(context, spin, pitch, extent, null);
        }

        static final Shot GUI = new Shot("gui", 0.0F, 0.0F, 0.0F);

        boolean isGui() {
            return spin == 0.0F && pitch == 0.0F && extent == 0.0F
                    && "gui".equals(context);
        }
    }

    private void request(LogicalModel model, int stateIndex, int size, boolean strip,
                         float yaw, float pitch, float zoom, int tick, Shot shot) {
        String key = strip
                ? key(model, stateIndex, size, true)
                : !shot.isGui()
                ? shotKey(model, stateIndex, size, shot, tick)
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
                Pixels rendered = strip
                        ? renderAnimation(mesh, resolved, size)
                        : renderStill(mesh, resolved, size, yaw, pitch, zoom, tick, shot);
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
                               float yaw, float pitch, float zoom, int tick, Shot shot) {
        // Supersampling costs its square, and it only helps when the render is
        // coarser than the screen. Previews are now sized from the GUI scale, so
        // at anything from a normal card upwards they are already at the
        // display's own pixel density and a second sample is 3.7x the work for
        // nothing: measured at 132 pixels, 3.02 ms with it against 0.81 ms
        // without. It is kept only for the genuinely small renders.
        int supersample = size >= 96 ? 1 : 2;
        Rasterizer.View view = new Rasterizer.View(size, size, supersample,
                yaw, pitch + shot.pitch(), zoom, resolved.transform(shot.context()),
                resolved.guiLightFront(), 0, tick, shot.spin(), shot.extent(),
                shot.anchor());
        return new Pixels(size, size, Rasterizer.render(mesh, view));
    }

    /**
     * Renders one cycle of a model's animated textures into one tall image.
     *
     * <p>One texture per animated model rather than one per moment: twenty
     * flickering cards then cost twenty textures and twenty blits, instead of
     * hundreds of textures and a stream of GPU allocations.</p>
     *
     * <p>The strip covers the whole cycle. Where the cycle is longer than the
     * cap, the moments are sampled evenly across it, so the animation keeps its
     * own speed and skips rather than slowing down.</p>
     */
    private Pixels renderAnimation(Mesh mesh, ResolvedModel resolved, int size) {
        int period = Math.max(1, animationPeriod(resolved));
        int frames = Math.min(period, CARD_FRAMES);
        int[] pixels = new int[size * size * frames];
        // The same rule the still uses, and for the same reason: above a card's
        // size the render is already at the display's pixel density and a
        // second sample is 3.7x the work for nothing. The strip inherited a
        // hard-coded 2 from the rotation it replaced, which paid that 3.7x on
        // every one of up to twenty-four frames -- and made a promoted card
        // visibly sharper than the still it replaced, which is a jump.
        int supersample = size >= 96 ? 1 : 2;
        Rasterizer.View view = new Rasterizer.View(size, size, supersample,
                0, 0, 1.0F, guiTransform(resolved), resolved.guiLightFront(), 0);
        for (int frame = 0; frame < frames; frame++) {
            int tick = (int) ((long) frame * period / frames);
            int[] rendered = Rasterizer.render(mesh, view.withTick(tick));
            System.arraycopy(rendered, 0, pixels, frame * size * size, size * size);
        }
        return new Pixels(size, size * frames, pixels);
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

    private static String key(LogicalModel model, int stateIndex, int size, boolean strip) {
        return model.canonicalId() + "/" + stateIndex + "/" + size + (strip ? "/a" : "/f");
    }

    private static String detailKey(LogicalModel model, int stateIndex, int size,
                                    float yaw, float pitch, float zoom, int tick) {
        return model.canonicalId() + "/" + stateIndex + "/" + size + "/d"
                + (int) yaw + "_" + (int) pitch + "_" + (int) (zoom * 20)
                + (tick == 0 ? "" : "_t" + tick);
    }

    /**
     * Whether any of a model's textures is a strip of frames.
     *
     * <p>Answered from what has already been read rather than by opening
     * anything: an animated texture declares itself in its {@code .mcmeta}
     * sidecar, which is read with the bytes, so the answer is a map lookup per
     * texture. Before the first read it is "no", which costs one extra render
     * when the answer turns out to be yes.</p>
     */
    /**
     * The tick count a model's textures repeat on, or 1 when none animates.
     *
     * <p>The least common multiple of each animated texture's own period, so a
     * model wearing a four-frame texture and a six-frame one comes back round
     * on twelve rather than appearing to drift.</p>
     *
     * <p>Capped. Two textures whose periods share no factor multiply out, and
     * a pair of unlucky primes would ask for a cycle thousands of frames long
     * that would never finish caching and would fill the budget trying. Past
     * the cap the cycle is allowed to be imperfect, which costs a visible jump
     * once every thirty seconds on a model nobody has yet shipped.</p>
     */
    private static final int LONGEST_CYCLE = 600;

    int animationPeriod(ResolvedModel resolved) {
        int period = 1;
        // The slot map's own values, not distinctTextures(), which builds a
        // stream, a sorted list and a result list every call. A card asks this
        // once a frame to decide whether it has anything to animate, so with a
        // full grid that was forty pipelines a frame for an answer that is
        // nearly always 1. Duplicates and order do not matter here: lcm(a, a)
        // is a, and lcm does not care what order it is given its arguments.
        for (String textureId : resolved.textureSlots().values()) {
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
        long low = Math.min(a, b);
        long high = Math.max(a, b);
        long gcd = low;
        long other = high;
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
                // something blurrier than the animation would be worse than not
                // sharpening at all.
                cap = Math.max(movingCap, Math.min(cap, fits));
            }
        }
        return Math.max(16, Math.min(cap, want));
    }

    private boolean hasAnimatedTexture(ResolvedModel resolved) {
        for (String textureId : resolved.distinctTextures()) {
            if (textureId == null) {
                continue;
            }
            ResourceSource.Image decoded = textureCache.get(textureId);
            if (decoded != null && decoded.frames() > 1) {
                return true;
            }
            ResourceSource.TextureBytes raw = rawTextures.get(textureId);
            if (raw != null && raw.frameHeight() != Integer.MAX_VALUE) {
                return true;
            }
        }
        return false;
    }
}
