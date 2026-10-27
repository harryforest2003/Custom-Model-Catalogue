package dev.custommodels.cmv.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.custommodels.cmv.compat.McCompat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Where you have chosen to see each model on the figure, in a file of its own.
 *
 * <h2>Why it is not in config.json</h2>
 *
 * <p>Saving a setting there serialises the whole config and rewrites the whole
 * file. That is right for a setting you change now and then and wrong for this
 * one, which is changed while looking through models: writing a placement would
 * rewrite every other setting in the mod to record where one pickaxe goes.</p>
 *
 * <p>A file of its own is what makes that stop. It holds placements and nothing
 * else, so it is small — a few hundred bytes for the handful of models anyone
 * actually moves — and a write cannot disturb anything but placements. If it is
 * ever edited into nonsense, placements are what is lost and the rest of the
 * settings are untouched.</p>
 *
 * <p>Note what is <em>not</em> claimed: that one entry is edited where it lies.
 * It cannot be. The values are different lengths — {@code head} is four bytes
 * and {@code mainhand} is eight — so changing one moves every byte after it,
 * and a file cannot have bytes inserted into the middle of it. Any "edit one
 * line" rewrites from that line to the end. Keeping the file small is the part
 * that is actually available, and it is the part that matters.</p>
 *
 * <h2>Shape of the file</h2>
 *
 * <p>Model id to the game's own name for the slot:</p>
 *
 * <pre>{@code
 * {
 *   "examplepack:item/hats/templar_hat": "head",
 *   "examplepack:item/tools/ruby_pickaxe": "mainhand"
 * }
 * }</pre>
 *
 * <p>Sorted, so a model that arrives with a new pack lands in a predictable
 * place instead of reshuffling everything. Only models that have been moved are
 * in it: defaults are worked out from the model and the item every time, which
 * is what lets a pack that changes a model's transforms change where it sits.
 * Writing the defaults down would pin them and quietly outvote the pack.</p>
 *
 * <h2>Why the write is not on the game thread</h2>
 *
 * <p>Measured rather than assumed, over 400 writes at each size. The median is
 * half a millisecond whatever the file holds, but the tail is not: a 64-byte
 * write reached 35 ms, while a 44 kB one never passed 12 ms. The spike is the
 * filesystem - journal, metadata, cache - and it is per write rather than per
 * byte, so it does not matter how little is being saved.</p>
 *
 * <p>That rules out two things. It rules out writing on each click, because
 * each write is another chance of hitting the tail rather than a smaller one.
 * And it rules out doing the write on the thread drawing frames, because
 * 35 ms there is two dropped frames for a file of 64 bytes.</p>
 *
 * <p>So the map is turned into text on the thread that owns it, which the same
 * run measured at 0.06 to 0.5 ms, and the text is handed to one background
 * thread that does the writing. Nothing mutable crosses between them: the
 * string is finished before it is handed over. One thread, so writes stay in
 * the order they were made.</p>
 */
public final class FigurePlacements {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * The one thread that writes the file.
     *
     * <p>Daemon and below normal priority, as the preview workers are: this is
     * never work the game should be waiting on or staying alive for.</p>
     */
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "CMV-Placements");
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY - 2);
                return thread;
            });

    private static Map<String, String> entries;
    private static boolean dirty;

    /**
     * The exact text the file holds, as this mod would write it.
     *
     * <p>Kept so that a session which ends up where it started writes nothing.
     * Comparing each change against the one before it is not enough: cycling a
     * model from head through both hands and back to head is three changes, and
     * the third is only a change when measured against the second. Measured
     * against the file, it is not a change at all.</p>
     *
     * <p>Set from what was read rather than from the file's own bytes, so it is
     * this mod's spelling of those entries being compared with this mod's
     * spelling of the current ones. A file hand-edited into a different
     * key order or indentation is not mistaken for a change.</p>
     *
     * <p>Volatile and cleared by a failed write: the write happens on another
     * thread, and if it did not land then the file does not hold this after
     * all, so the next change must not skip itself on the strength of it.</p>
     */
    private static volatile String onDisk;

    private FigurePlacements() {
    }

    private static Path file() {
        return McCompat.configDir().resolve("placements.json");
    }

    /** Read once, on the first card that asks. */
    private static Map<String, String> entries() {
        if (entries == null) {
            entries = read();
        }
        return entries;
    }

    private static Map<String, String> read() {
        Map<String, String> found = new TreeMap<>();
        Path path = file();
        if (!Files.exists(path)) {
            // No file is the same as an empty one for this purpose: a session
            // that changes something and changes it back writes nothing, and
            // does not leave an empty file behind to say so.
            onDisk = GSON.toJson(found);
            return found;
        }
        try {
            Map<String, String> saved = GSON.fromJson(Files.readString(path),
                    new TypeToken<Map<String, String>>() { }.getType());
            if (saved != null) {
                // Nulls on either side are dropped rather than carried: a null
                // key cannot be looked up and a null value is not a placement.
                saved.forEach((key, value) -> {
                    if (key != null && value != null) {
                        found.put(key, value);
                    }
                });
            }
        } catch (IOException | RuntimeException e) {
            CmvMod.LOGGER.warn("Could not read figure placements", e);
        }
        onDisk = GSON.toJson(found);
        return found;
    }

    /**
     * The text to write, or null when the file already says this.
     *
     * <p>Pure, and public so it can be checked without a game directory behind
     * it: this is the decision that stops a pointless write, and the case it
     * exists for — ending a session on the placement you started it with — is
     * not one that shows up as any single change being a no-op.</p>
     *
     * @param entries what the placements now are
     * @param onDisk  what the file holds, in this mod's own spelling, or null
     *                when it has not been read or written
     */
    public static String pending(Map<String, String> entries, String onDisk) {
        String json = GSON.toJson(new TreeMap<>(entries));
        return json.equals(onDisk) ? null : json;
    }

    /** The saved slot name for a model, or null when it has never been moved. */
    public static String get(String modelId) {
        return modelId == null ? null : entries().get(modelId);
    }

    /**
     * Remembers a placement, to be written when the card closes.
     *
     * <p>A placement that is already what is saved is not a change, so cycling
     * all the way round to where you started writes nothing.</p>
     */
    public static void set(String modelId, String name) {
        if (modelId == null || modelId.isEmpty() || name == null || name.isEmpty()) {
            return;
        }
        if (name.equals(entries().get(modelId))) {
            return;
        }
        entries().put(modelId, name);
        // Only says a write may be needed. Whether one actually is, is settled
        // against the file when the viewer closes: see pending.
        dirty = true;
    }

    /**
     * Writes the file if anything changed, and says whether it wrote.
     *
     * <p>Called when the last viewer screen closes, not when a card does, so a
     * whole session of moving things around is one write. Looking for the right
     * placement on one model is not a write per click; moving six models across
     * six cards is not six writes; and going from a card back to the grid and
     * into another card is not a write at all. Leaving the viewer is the one.</p>
     */
    public static boolean flush() {
        if (!dirty) {
            return false;
        }
        dirty = false;
        // Turned into text here, where the map is known not to be changing, so
        // that nothing mutable is handed to the writing thread.
        String json = pending(entries(), onDisk);
        if (json == null) {
            // Cycled round and landed back on what was already saved. There is
            // nothing to write, so nothing is written.
            return false;
        }
        onDisk = json;
        try {
            WRITER.execute(() -> write(json));
        } catch (RejectedExecutionException closing) {
            // The writer has been shut down, which happens once, as the game
            // closes. Better to take the write here than to lose it.
            write(json);
        }
        return true;
    }

    private static void write(String json) {
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, json);
        } catch (IOException e) {
            // The file does not hold this after all, so the next change must
            // not skip its own write having compared against it.
            onDisk = null;
            CmvMod.LOGGER.warn("Could not save figure placements", e);
        }
    }

    /**
     * Writes anything outstanding and waits for it, as the game closes.
     *
     * <p>A card taken off the screen writes on its own, so this is only for a
     * placement changed and then left there while the game was shut down -
     * which the client does not always route through a screen being removed.
     * The wait is bounded, because a background write that has gone wrong must
     * not be able to hold the game open.</p>
     */
    public static void close() {
        flush();
        WRITER.shutdown();
        try {
            if (!WRITER.awaitTermination(2, TimeUnit.SECONDS)) {
                CmvMod.LOGGER.warn("Gave up waiting for figure placements to save");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** How many models have been moved, for the harness and for debug output. */
    public static int count() {
        return entries().size();
    }
}
