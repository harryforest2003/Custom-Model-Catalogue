package dev.custommodels.cmv.explorer.scan;

import java.util.ArrayList;
import java.util.List;

/**
 * Live progress and final statistics for one scan.
 *
 * <p>Written by the scan thread and read by the render thread every frame, so
 * the mutable fields are {@code volatile} and the warning list is guarded. The
 * alternative — posting an event per file — would cost more in cross-thread
 * traffic than the whole scan does in work.</p>
 */
public final class ScanReport {

    /** Definitions skipped because their pack is not scanned. */
    private volatile int ignoredDefinitions;

    public void setIgnoredDefinitions(int count) {
        this.ignoredDefinitions = count;
    }

    public int ignoredDefinitions() {
        return ignoredDefinitions;
    }


    /** The stages a scan moves through, in order. */
    public enum Phase {
        IDLE("Idle"),
        READING("Reading files"),
        DEFINITIONS("Resolving item definitions"),
        MODELS("Resolving models"),
        TEXTURES("Resolving textures"),
        GROUPING("Grouping model states"),
        NAMING("Reading names and sets"),
        DEDUPLICATING("Removing duplicates"),
        DONE("Done"),
        CANCELLED("Cancelled"),
        FAILED("Failed");

        private final String label;

        Phase(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private volatile Phase phase = Phase.IDLE;
    private volatile int progressCurrent;
    private volatile int progressTotal;

    private volatile int filesScanned;
    private volatile int definitionsRead;
    private volatile int rawModelsFound;
    private volatile int modelsResolved;
    private volatile int modelFilesParsed;
    private volatile int texturesResolved;
    private volatile int logicalModels;
    private volatile int duplicatesRemoved;
    private volatile int groupedStates;
    private volatile int heuristicGroups;
    private volatile int vanillaSkipped;
    private volatile int truncatedFrom;
    private volatile int truncatedTo;
    private volatile int translationEntries;
    private volatile int languageFiles;
    private volatile long durationMillis;
    private volatile long texturePixels;
    private volatile boolean fromCache;
    private volatile String failure = "";

    private final List<String> warnings = new ArrayList<>();

    public Phase phase() {
        return phase;
    }

    void setPhase(Phase newPhase) {
        this.phase = newPhase;
    }

    /** 0..1, or -1 when the current phase has no measurable total. */
    public float progress() {
        int total = progressTotal;
        return total <= 0 ? -1.0F : Math.min(1.0F, progressCurrent / (float) total);
    }

    void setProgress(int current, int total) {
        this.progressCurrent = current;
        this.progressTotal = total;
    }

    public boolean isRunning() {
        return phase != Phase.IDLE && phase != Phase.DONE
                && phase != Phase.CANCELLED && phase != Phase.FAILED;
    }

    public boolean isDone() {
        return phase == Phase.DONE;
    }

    public int filesScanned() {
        return filesScanned;
    }

    public int definitionsRead() {
        return definitionsRead;
    }

    public int rawModelsFound() {
        return rawModelsFound;
    }

    public int modelsResolved() {
        return modelsResolved;
    }

    public int modelFilesParsed() {
        return modelFilesParsed;
    }

    public int texturesResolved() {
        return texturesResolved;
    }

    public int logicalModels() {
        return logicalModels;
    }

    public int duplicatesRemoved() {
        return duplicatesRemoved;
    }

    public int groupedStates() {
        return groupedStates;
    }

    public int heuristicGroups() {
        return heuristicGroups;
    }

    public int vanillaSkipped() {
        return vanillaSkipped;
    }

    public long durationMillis() {
        return durationMillis;
    }

    /** Total decoded texture pixels; four bytes each in the shared cache. */
    public long texturePixels() {
        return texturePixels;
    }

    /** True when the results were reused rather than rebuilt. */
    public boolean fromCache() {
        return fromCache;
    }

    public String failure() {
        return failure;
    }

    public synchronized List<String> warnings() {
        return List.copyOf(warnings);
    }

    public synchronized int warningCount() {
        return warnings.size();
    }

    synchronized void addWarning(String warning) {
        // A pack with a thousand broken files should not turn the report into
        // the largest object in the heap.
        if (warnings.size() < 500) {
            warnings.add(warning);
        } else if (warnings.size() == 500) {
            warnings.add("... further warnings suppressed");
        }
    }

    void setCounts(int filesScanned, int definitionsRead, int rawModelsFound) {
        this.filesScanned = filesScanned;
        this.definitionsRead = definitionsRead;
        this.rawModelsFound = rawModelsFound;
    }

    void setResolution(int modelsResolved, int modelFilesParsed, int texturesResolved,
                       long texturePixels) {
        this.modelsResolved = modelsResolved;
        this.modelFilesParsed = modelFilesParsed;
        this.texturesResolved = texturesResolved;
        this.texturePixels = texturePixels;
    }

    /** How many models the list was cut down to, or 0 when it was not cut. */
    public int truncatedTo() {
        return truncatedTo;
    }

    /** How many models there were before truncation. */
    public int truncatedFrom() {
        return truncatedFrom;
    }

    void setTruncated(int from, int to) {
        this.truncatedFrom = from;
        this.truncatedTo = to;
    }

    void setGrouping(int logicalModels, int duplicatesRemoved, int groupedStates,
                     int heuristicGroups, int vanillaSkipped) {
        this.logicalModels = logicalModels;
        this.duplicatesRemoved = duplicatesRemoved;
        this.groupedStates = groupedStates;
        this.heuristicGroups = heuristicGroups;
        this.vanillaSkipped = vanillaSkipped;
    }

    public int translationEntries() {
        return translationEntries;
    }


    void setNaming(int entries, int files) {
        this.translationEntries = entries;
        this.languageFiles = files;
    }

    void setDuration(long millis) {
        this.durationMillis = millis;
    }

    void setFromCache(boolean cached) {
        this.fromCache = cached;
    }

    void setFailure(String message) {
        this.failure = message == null ? "" : message;
    }

    /** The summary lines shown after a scan finishes. */
    public List<String> summaryLines() {
        List<String> lines = new ArrayList<>(9);
        lines.add("Files scanned: " + format(filesScanned));
        lines.add("Item definitions read: " + format(definitionsRead));
        lines.add("Model references found: " + format(rawModelsFound));
        lines.add("Model files parsed: " + format(modelFilesParsed));
        lines.add("Models resolved: " + format(modelsResolved));
        lines.add("Textures resolved: " + format(texturesResolved));
        lines.add("Logical models: " + format(logicalModels));
        lines.add("Duplicates removed: " + format(duplicatesRemoved));
        lines.add("Grouped states: " + format(groupedStates));
        lines.add("Vanilla entries skipped: " + format(vanillaSkipped));
        lines.add("Translation entries: " + format(translationEntries)
                + " from " + languageFiles + " language files");
        lines.add("Warnings: " + format(warningCount()));
        lines.add("Time: " + durationMillis + " ms" + (fromCache ? " (cached)" : ""));
        return lines;
    }

    private static String format(int value) {
        return String.format("%,d", value);
    }
}
