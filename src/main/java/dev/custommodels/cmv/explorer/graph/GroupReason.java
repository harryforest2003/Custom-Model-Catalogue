package dev.custommodels.cmv.explorer.graph;

/**
 * Why two models ended up in the same logical item, and how much that reason
 * should be trusted.
 *
 * <p>Recorded for every grouping and shown in debug mode, because "these four
 * bows are one item" is a claim, and a viewer that makes claims should be able
 * to say where they came from. The ordering below is also the precedence: a
 * stronger reason always wins over a weaker one, so a filename similarity can
 * never override what the item definition actually says.</p>
 */
public enum GroupReason {

    /**
     * The models are different states of one branch of a single item
     * definition. Minecraft itself will show one or the other depending on the
     * item's condition, so they are unambiguously one item.
     *
     * <p>Example: {@code bow.json} dispatching on {@code using_item} and
     * {@code use_duration} to four models.</p>
     */
    SAME_ITEM_DISPATCH("Same item model dispatch", Confidence.HIGH),

    /**
     * The models share a custom model data selection within one definition:
     * the same string on an item picks between them.
     */
    SAME_CUSTOM_MODEL_DATA("Same custom model data selection", Confidence.HIGH),

    /**
     * Two references resolve to the same model file. Not an inference at all,
     * just the same thing named twice.
     */
    DIRECT_REFERENCE("Same model reference", Confidence.HIGH),

    /**
     * Different model files that resolve, after parent inheritance, to
     * identical geometry and identical textures. Equivalent in every way that
     * shows on screen.
     */
    SAME_RESOLVED_MODEL("Identical resolved geometry and textures", Confidence.HIGH),

    /**
     * Related only by naming: one model's file name is the other's with a
     * recognised state suffix such as {@code _pulling_1} or {@code -2}, in the
     * same folder and on the same base item.
     *
     * <p>This is the only reason that is a guess, and it is applied last and
     * marked as such.</p>
     */
    FILENAME_HEURISTIC("Filename relationship only", Confidence.LOW),

    /** The model stands alone. */
    SINGLE("Single model", Confidence.HIGH);

    /** How much the reason should be trusted. */
    public enum Confidence {
        HIGH("High"),
        MEDIUM("Medium"),
        LOW("Low");

        private final String label;

        Confidence(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final String description;
    private final Confidence confidence;

    GroupReason(String description, Confidence confidence) {
        this.description = description;
        this.confidence = confidence;
    }

    public String description() {
        return description;
    }

    public Confidence confidence() {
        return confidence;
    }

    /** True when this reason came from Minecraft's own relationships. */
    public boolean isStructural() {
        return this != FILENAME_HEURISTIC;
    }
}
