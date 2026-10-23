package dev.custommodels.cmv.explorer.graph;

/**
 * A display name together with where it came from.
 *
 * <p>The point of carrying the source is honesty. "Ancient Dragonblade" read
 * out of a pack's language file is a fact; the same string derived from the
 * path {@code item/ancient/dragonblade} is a guess that happens to read well.
 * The UI shows both the same way, so the difference has to be recorded
 * somewhere, and this is it.</p>
 *
 * @param value      the name to show
 * @param source     where it was found
 * @param evidence   the key, file or path it came from, for the debug panel
 */
public record ResolvedName(String value, Source source, String evidence) {

    /** Ordered best first. {@link #TRANSLATION} is the only one that is not a guess. */
    public enum Source {
        /** A language file entry in the pack. The author's own words. */
        TRANSLATION("Resource-pack translation", Confidence.HIGH),
        /** An {@code item_name} or {@code custom_name} in the item definition. */
        ITEM_NAME("Item definition name", Confidence.HIGH),
        /** The custom model data value the pack author chose. */
        CUSTOM_MODEL_DATA("Custom model data value", Confidence.MEDIUM),
        /** Derived from the model path or file name. */
        PATH("Filename inference", Confidence.LOW),
        /** Nothing better than the base item id was available. */
        FALLBACK("Base item id", Confidence.LOW);

        private final String label;
        private final Confidence confidence;

        Source(String label, Confidence confidence) {
            this.label = label;
            this.confidence = confidence;
        }

        public String label() {
            return label;
        }

        public Confidence confidence() {
            return confidence;
        }
    }

    /** How much the name should be trusted. */
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

    public Confidence confidence() {
        return source.confidence();
    }

    /** True when the pack actually said this, rather than it being derived. */
    public boolean isStated() {
        return source == Source.TRANSLATION || source == Source.ITEM_NAME;
    }

    public static ResolvedName of(String value, Source source, String evidence) {
        return new ResolvedName(value, source, evidence == null ? "" : evidence);
    }
}
