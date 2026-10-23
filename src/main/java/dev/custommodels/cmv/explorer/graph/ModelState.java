package dev.custommodels.cmv.explorer.graph;

import dev.custommodels.cmv.explorer.def.DiscoveredModel;
import dev.custommodels.cmv.explorer.def.Selector;
import dev.custommodels.cmv.explorer.res.ResolvedModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One appearance a logical item can take: a bow at rest, a bow at full draw, a
 * shield raised, a crossbow loaded with a firework.
 *
 * @param label      a readable name such as {@code Pull 2}. Derived from the
 *                   real property and threshold, never invented.
 * @param labelIsDerived true when the label was shortened from the raw
 *                   condition for readability, so the UI can offer the raw form
 * @param source     the discovery record this state came from
 * @param resolved   the model after parent and texture resolution
 */
public record ModelState(String label,
                         boolean labelIsDerived,
                         DiscoveredModel source,
                         ResolvedModel resolved) {

    /** The model id, e.g. {@code example:item/bows/dragon_bow}. */
    public String modelId() {
        return source.modelId();
    }

    /** The conditions that distinguish this state from its siblings. */
    public List<Selector> steps() {
        return source.stateSteps();
    }

    /** The full condition, spelled out, e.g. {@code using_item = true, use_duration ≥ 0.9}. */
    public String conditionText() {
        List<Selector> steps = steps();
        if (steps.isEmpty()) {
            return "no conditions";
        }
        List<String> parts = new ArrayList<>(steps.size());
        for (Selector step : steps) {
            parts.add(step.describe());
        }
        return String.join(", ", parts);
    }

    // ------------------------------------------------------------- labelling

    /**
     * Builds a readable label from the conditions that lead to this state.
     *
     * <p>Every label is grounded in a real property. The friendly names below
     * rename known properties; they never assert a condition the definition did
     * not contain. Anything unrecognised falls through to the raw condition
     * text rather than being guessed at.</p>
     *
     * @param pullOrdinal position among sibling states that share a numeric
     *                    draw-style property, or -1 when not applicable
     */
    public static String label(List<Selector> steps, int pullOrdinal) {
        if (steps.isEmpty()) {
            return "Default";
        }
        // When one of the steps is a draw stage, the "in use" condition above it
        // is saying the same thing twice: "In use, Pull 2" is just "Pull 2".
        boolean hasDrawStage = false;
        for (Selector step : steps) {
            if (step.kind() == Selector.Kind.RANGE_DISPATCH
                    && isDrawProperty(step.shortProperty())) {
                hasDrawStage = true;
                break;
            }
        }

        List<String> parts = new ArrayList<>();
        for (Selector step : steps) {
            if (hasDrawStage && step.kind() == Selector.Kind.CONDITION
                    && "using_item".equals(step.shortProperty())) {
                continue;
            }
            String piece = describeStep(step, pullOrdinal);
            if (piece != null && !piece.isEmpty()) {
                parts.add(piece);
            }
        }
        if (parts.isEmpty()) {
            return "Default";
        }
        return String.join(" · ", parts);
    }

    private static String describeStep(Selector step, int pullOrdinal) {
        String property = step.shortProperty();
        boolean positive = step.branch() == Selector.Branch.MATCH;

        switch (step.kind()) {
            case CONDITION -> {
                return switch (property) {
                    case "using_item" -> positive ? "In use" : null;
                    case "broken" -> positive ? "Broken" : null;
                    case "damaged" -> positive ? "Damaged" : null;
                    case "fishing_rod/cast" -> positive ? "Cast" : null;
                    case "bundle/has_selected_item" -> positive ? "Item selected" : null;
                    case "selected" -> positive ? "Selected" : null;
                    case "carried" -> positive ? "Carried" : null;
                    case "keybind_down" -> positive ? "Key held" : null;
                    case "view_entity" -> positive ? "Own view" : null;
                    case "extended_view" -> positive ? "Extended view" : null;
                    default -> positive ? prettify(property) : null;
                };
            }
            case RANGE_DISPATCH -> {
                if (isDrawProperty(property)) {
                    return pullOrdinal >= 0 ? "Pull " + pullOrdinal : "Pull";
                }
                if (step.branch() == Selector.Branch.FALLBACK) {
                    return null;
                }
                return prettify(property) + " " + trim(step.threshold());
            }
            case SELECT -> {
                if (step.branch() == Selector.Branch.FALLBACK) {
                    return null;
                }
                String value = String.join("/", step.values());
                return switch (property) {
                    case "charge_type" -> prettify(value);
                    case "display_context" -> prettify(value) + " view";
                    case "custom_model_data" -> prettify(value);
                    case "trim_material" -> prettify(value) + " trim";
                    case "main_hand" -> prettify(value) + " hand";
                    default -> prettify(value);
                };
            }
            case COMPOSITE -> {
                return "Layer " + (step.index() + 1);
            }
        }
        return null;
    }

    /**
     * Numeric properties that describe drawing a weapon. These are the ones
     * where "Pull 1, Pull 2, Pull 3" is a better label than the raw threshold,
     * because that is what the stages mean.
     */
    public static boolean isDrawProperty(String shortProperty) {
        return "use_duration".equals(shortProperty)
                || "crossbow/pull".equals(shortProperty)
                || "use_cycle".equals(shortProperty)
                || "pull".equals(shortProperty);
    }

    private static String prettify(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String[] words = raw.replace('/', ' ').replace('_', ' ').trim().split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) {
                out.append(word.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return out.toString();
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
    private static String trim(float value) {
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
