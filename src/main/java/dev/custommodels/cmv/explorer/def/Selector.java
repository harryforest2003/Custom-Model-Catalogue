package dev.custommodels.cmv.explorer.def;

import java.util.List;

/**
 * One decision taken while walking a Minecraft 26.2 item definition tree.
 *
 * <p>An item definition is a tree of model nodes. Reaching a concrete model
 * means passing through zero or more branching nodes, and each branch taken is
 * recorded here. The resulting path is what lets the explorer say, truthfully,
 * <em>why</em> a model is shown and which other models are siblings of it.</p>
 *
 * <p>The five branching kinds are exactly the ones 26.2 defines. Verified by
 * reading the registered type ids out of {@code ItemModels} in the 26.2 jar:
 * {@code model, composite, condition, range_dispatch, select, special, empty,
 * bundle/selected_item}. Of those, {@code condition}, {@code range_dispatch},
 * {@code select} and {@code composite} branch; the rest are leaves.</p>
 *
 * @param kind      which branching node produced this step
 * @param property  the namespaced property id driving the branch, e.g.
 *                  {@code minecraft:custom_model_data}. Empty for composite.
 * @param values    for {@link Kind#SELECT}, the {@code when} values of the case
 *                  taken. A case may list several values, and all of them are
 *                  kept because any one of them reaches this model.
 * @param threshold for {@link Kind#RANGE_DISPATCH}, the entry threshold, or
 *                  {@link Float#NaN} when the fallback branch was taken.
 * @param scale     for {@link Kind#RANGE_DISPATCH}, the {@code scale} the
 *                  property value is multiplied by before comparison.
 * @param branch    for {@link Kind#CONDITION}, whether {@code on_true} or
 *                  {@code on_false} was taken; for range dispatch, whether an
 *                  entry or the fallback was taken; for select likewise.
 * @param index     ordinal of this branch among its siblings, preserved so that
 *                  states sort in the order the pack author wrote them.
 * @param dataIndex the node's own {@code index} field, which for a custom model
 *                  data property says which entry of the component's list is
 *                  read. Confirmed from the 26.2 jar: the numeric property
 *                  reads {@code floats.get(index)} and the select property
 *                  reads {@code strings.get(index)}. Defaults to 0.
 */
public record Selector(Kind kind,
                       String property,
                       List<String> values,
                       float threshold,
                       float scale,
                       Branch branch,
                       int index,
                       int dataIndex,
                       String componentId) {

    /** Convenience for the many selectors that carry no data index. */
    public Selector(Kind kind, String property, List<String> values, float threshold,
                    float scale, Branch branch, int index) {
        this(kind, property, values, threshold, scale, branch, index, 0, "");
    }

    public Selector(Kind kind, String property, List<String> values, float threshold,
                    float scale, Branch branch, int index, int dataIndex) {
        this(kind, property, values, threshold, scale, branch, index, dataIndex, "");
    }

    public enum Kind {
        /** {@code minecraft:select} — switch on a string-valued property. */
        SELECT,
        /** {@code minecraft:range_dispatch} — switch on a numeric property. */
        RANGE_DISPATCH,
        /** {@code minecraft:condition} — switch on a boolean property. */
        CONDITION,
        /** {@code minecraft:composite} — every child is drawn together. */
        COMPOSITE
    }

    public enum Branch {
        /** A {@code cases} entry, a {@code entries} entry, or {@code on_true}. */
        MATCH,
        /** {@code on_false}. */
        NEGATIVE,
        /** The {@code fallback} of a select or range dispatch. */
        FALLBACK,
        /** A member of a composite; not really a branch at all. */
        MEMBER
    }

    /** True when this step switches on custom model data, in any of its forms. */
    public boolean isCustomModelData() {
        return "custom_model_data".equals(shortProperty());
    }

    /**
     * Components a {@code select} switches on to mean "which item is this",
     * rather than "what is it doing".
     */
    private static final List<String> IDENTITY_COMPONENTS =
            List.of("minecraft:custom_name", "minecraft:item_name",
                    "custom_name", "item_name");

    /**
     * True when this step chooses <em>which appearance</em> rather than which
     * state of one appearance.
     *
     * <p>The distinction is what separates two skins from two frames of the
     * same skin, and it decides where one logical model ends and the next
     * begins. Custom model data is the usual mechanism, but it is not the only
     * one: a pack can switch on {@code minecraft:component} reading
     * {@code custom_name}, so that renaming an item to "Abominable Blade" is
     * what summons the model. That is a different weapon, not a different state
     * of a weapon, and treating it as a state collapsed a hundred of them into
     * one entry.</p>
     *
     * <p>Everything else a select can switch on — display context, main hand,
     * charge type, trim material — really is a state, so the test is
     * deliberately a short allow-list rather than a guess.</p>
     */
    public boolean isIdentity() {
        if (isCustomModelData()) {
            return true;
        }
        if (kind != Kind.SELECT) {
            return false;
        }
        String property = shortProperty();
        if ("item_name".equals(property) || "custom_name".equals(property)) {
            return true;
        }
        return "component".equals(property) && IDENTITY_COMPONENTS.contains(componentId);
    }

    /** The property id with the {@code minecraft:} namespace stripped. */
    public String shortProperty() {
        int colon = property.indexOf(':');
        return colon >= 0 ? property.substring(colon + 1) : property;
    }

    /**
     * A stable token identifying this step, used to build grouping keys. Two
     * models whose paths produce the same discriminator were selected by the
     * same decisions and therefore belong to the same logical item.
     */
    public String token() {
        return switch (kind) {
            case SELECT -> "s:" + property + "=" + String.join("|", values)
                    + (branch == Branch.FALLBACK ? "#fallback" : "");
            case RANGE_DISPATCH -> "r:" + property + (branch == Branch.FALLBACK
                    ? "#fallback" : ">=" + threshold);
            case CONDITION -> "c:" + property + "=" + (branch == Branch.MATCH);
            case COMPOSITE -> "m:" + index;
        };
    }

    /**
     * How "default" this step is. A model reached only through negative or
     * fallback branches is the item's resting appearance; every positive branch
     * moves it further from default. Used to pick which state a card shows.
     */
    public int activationCost() {
        return switch (branch) {
            case MATCH -> kind == Kind.SELECT ? 1 : 2;
            case NEGATIVE, FALLBACK -> 0;
            case MEMBER -> 0;
        };
    }

    /** Human-readable description of the condition, e.g. {@code pull >= 0.65}. */
    public String describe() {
        String prop = shortProperty();
        return switch (kind) {
            case SELECT -> branch == Branch.FALLBACK
                    ? prop + " = (fallback)"
                    : prop + " = " + String.join(" or ", values);
            case RANGE_DISPATCH -> branch == Branch.FALLBACK
                    ? prop + " < first threshold"
                    : prop + " ≥ " + trim(threshold)
                            + (scale != 1.0F ? "  (scale " + trim(scale) + ")" : "");
            case CONDITION -> prop + " = " + (branch == Branch.MATCH ? "true" : "false");
            case COMPOSITE -> "composite layer " + (index + 1);
        };
    }

    /**
     * Formats a threshold without {@code String.format}.
     *
     * <p>This looks like premature micro-optimisation and is not: profiling the
     * scan of a large pack put {@code Formatter.parse} and the regex behind
     * {@code replaceAll("0+$", "")} among the hottest frames in the whole
     * pipeline. Thresholds are formatted for every state of every model, twice
     * over, and {@code String.format} compiles a format string each time while
     * {@code replaceAll} compiles a pattern each time.</p>
     */
    static String trim(float value) {
        if (Float.isNaN(value)) {
            return "";
        }
        if (value == (long) value) {
            return Long.toString((long) value);
        }
        boolean negative = value < 0;
        long scaled = Math.round(Math.abs(value) * 1000.0);
        long whole = scaled / 1000;
        long fraction = scaled % 1000;

        StringBuilder out = new StringBuilder(8);
        if (negative) {
            out.append('-');
        }
        out.append(whole);
        if (fraction != 0) {
            out.append('.');
            // Zero-pad to three digits, then drop trailing zeros.
            int digits = 3;
            while (digits > 1 && fraction % 10 == 0) {
                fraction /= 10;
                digits--;
            }
            for (int pad = digits - Long.toString(fraction).length(); pad > 0; pad--) {
                out.append('0');
            }
            out.append(fraction);
        }
        return out.toString();
    }
}
