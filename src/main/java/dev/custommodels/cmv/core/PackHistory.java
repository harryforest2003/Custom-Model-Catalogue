package dev.custommodels.cmv.core;

import dev.custommodels.cmv.compat.McCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Remembers which resource packs were on, so a wipe can be undone.
 *
 * <p>The client has a failure mode that empties the pack list without asking.
 * When a resource reload throws — a font it cannot build, an atlas it cannot
 * stitch — {@code clearResourcePacksOnError} switches off <em>every</em>
 * selected pack and writes that to options.txt. The packs are still on disk and
 * nothing is corrupt, but the list of what was enabled is gone, and rebuilding
 * it means remembering an order nobody memorised.
 *
 * <p>So it is written down beforehand. A snapshot is kept whenever the selection
 * looks healthy, and restoring puts back the most recent one that had more packs
 * than are on now.
 *
 * <h2>What counts as healthy</h2>
 * <p>A selection of one entry is not recorded. After a wipe the list is exactly
 * {@code ["vanilla"]}, and a history that faithfully recorded that would have
 * overwritten the answer with the problem.
 */
public final class PackHistory {

    /** How many selections to keep. Enough to step back past a bad session. */
    private static final int KEEP = 5;

    /** Ticks between checks. The selection changes rarely and cheaply. */
    private static final int EVERY = 100;

    private static int countdown;

    private PackHistory() {
    }

    /** Called every tick; does almost nothing almost always. */
    public static void tick() {
        if (--countdown > 0) {
            return;
        }
        countdown = EVERY;
        record();
    }

    /**
     * Notes the current selection, when it is worth noting.
     *
     * <p>Skipped when it matches the newest snapshot, so a session spent not
     * changing packs writes nothing.</p>
     */
    public static void record() {
        List<String> current = McCompat.selectedPackIds();
        CmvConfig config = CmvConfig.get();
        if (config.packHistory == null) {
            config.packHistory = new ArrayList<>();
        }
        if (!worthRecording(current, config.packHistory)) {
            return;
        }
        config.packHistory.add(0, List.copyOf(current));
        while (config.packHistory.size() > KEEP) {
            config.packHistory.remove(config.packHistory.size() - 1);
        }
        config.save();
    }

    /**
     * The selection to go back to, or null when there is nothing better.
     *
     * <p>"Better" means more packs than are on now. Anything else would offer
     * to restore a state identical to the current one.</p>
     */
    public static List<String> candidate() {
        return candidateFrom(CmvConfig.get().packHistory,
                McCompat.selectedPackIds().size());
    }

    /**
     * The decision itself, with nothing of the game in it.
     *
     * @param history newest first
     * @param now     how many packs are on at the moment
     */
    public static List<String> candidateFrom(List<List<String>> history, int now) {
        if (history == null) {
            return null;
        }
        for (List<String> snapshot : history) {
            if (snapshot != null && snapshot.size() > now) {
                return snapshot;
            }
        }
        return null;
    }

    /** Whether a selection is worth writing down. Pure, for the same reason. */
    public static boolean worthRecording(List<String> current, List<List<String>> history) {
        if (current == null || current.size() < 2) {
            return false;
        }
        return history == null || history.isEmpty() || !history.get(0).equals(current);
    }

    public static boolean canRestore() {
        return candidate() != null;
    }

    /**
     * Puts a remembered selection back.
     *
     * @return how many packs were turned on, or -1 when there was nothing to do
     */
    public static int restore() {
        List<String> snapshot = candidate();
        if (snapshot == null) {
            return -1;
        }
        // A pack in the snapshot that is no longer on disk is left out rather
        // than refused: the point is to recover as much as still exists.
        int applied = McCompat.applySelection(snapshot);
        CmvMod.LOGGER.info("Restored {} of {} remembered packs", applied, snapshot.size());
        return applied;
    }

    /** How many packs the offered snapshot holds, for the button's label. */
    public static int candidateSize() {
        List<String> snapshot = candidate();
        return snapshot == null ? 0 : snapshot.size();
    }
}
