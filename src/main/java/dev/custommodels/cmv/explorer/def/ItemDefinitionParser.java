package dev.custommodels.cmv.explorer.def;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Walks one Minecraft 26.2 item definition and reports every model it can
 * reach, together with the route taken.
 *
 * <h2>The 26.2 format</h2>
 *
 * <p>An item definition lives at {@code assets/<ns>/items/<item>.json} and has
 * a single {@code model} field holding a tree. The node types below are the
 * complete set registered by {@code net.minecraft.client.renderer.item.ItemModels}
 * in 26.2, read out of the game jar rather than assumed:</p>
 *
 * <pre>
 *   minecraft:model                 leaf   { "model": "&lt;model id&gt;", "tints": [...] }
 *   minecraft:special               leaf   { "base": "&lt;model id&gt;", "model": {...} }
 *   minecraft:empty                 leaf   {}
 *   minecraft:bundle/selected_item  leaf   {}
 *   minecraft:composite             branch { "models": [ ... ] }
 *   minecraft:condition             branch { "property": .., "on_true": .., "on_false": .. }
 *   minecraft:select                branch { "property": .., "cases": [{"when":..,"model":..}], "fallback": .. }
 *   minecraft:range_dispatch        branch { "property": .., "scale": .., "entries": [{"threshold":..,"model":..}], "fallback": .. }
 * </pre>
 *
 * <p>Any node may additionally carry a {@code transformation}. Vanilla's own
 * {@code shield.json} and {@code trident.json} use it, so it is recorded.</p>
 *
 * <h2>Why this is a raw JSON walk rather than a codec decode</h2>
 *
 * <p>Minecraft can decode these files with {@code ItemModels.CODEC}, and doing
 * so would be shorter. It is not used, for two reasons. A codec failure
 * discards the <em>whole file</em>, so one malformed case in a thousand-entry
 * pack would lose the other nine hundred and ninety-nine; and the decoded form
 * has already thrown away the raw strings the information panel has to show.
 * Walking the JSON directly degrades per node instead of per file, and needs no
 * registry access, which is what lets the scan run on a worker thread.</p>
 *
 * <h2>Attribution</h2>
 *
 * <p>The idea of carrying a "did we pass through custom model data" flag down
 * the recursion, and of accumulating the select values and range thresholds
 * along the way so that a previewable component can be rebuilt, is taken from
 * Custom Model Data Viewer (Apache-2.0). The code here is an independent
 * implementation; see {@code NOTICE}.</p>
 */
public final class ItemDefinitionParser {

    /** Depth guard. Vanilla's deepest definition nests four levels. */
    private static final int MAX_DEPTH = 32;

    /** Guard against a pack producing an unbounded number of leaves. */
    private static final int MAX_LEAVES_PER_FILE = 20_000;

    private ItemDefinitionParser() {
    }

    /** Anything the parser could not make sense of, reported rather than hidden. */
    public record Warning(String definitionId, String message) {
    }

    /** Result of parsing one file. */
    public record Result(List<DiscoveredModel> models, List<Warning> warnings) {
    }

    /**
     * Parses one item definition.
     *
     * @param baseItemId    e.g. {@code minecraft:bow}
     * @param definitionId  e.g. {@code minecraft:items/bow.json}
     * @param archivePath   e.g. {@code assets/minecraft/items/bow.json}
     * @param sourcePackId  the pack that won this file
     * @param reader        the file contents; closed by the caller
     */
    public static Result parse(String baseItemId,
                               String definitionId,
                               String archivePath,
                               String sourcePackId,
                               Reader reader) {
        List<DiscoveredModel> models = new ArrayList<>();
        List<Warning> warnings = new ArrayList<>();

        JsonElement root;
        try {
            root = JsonParser.parseReader(reader);
        } catch (RuntimeException | StackOverflowError e) {
            // The overflow is not hypothetical: Gson builds the tree by
            // recursion, so nesting deep enough overruns the stack before this
            // parser's own depth limit is ever consulted. One file from one pack
            // must not end a scan, so it is caught here with everything else
            // that means "this file is not usable".
            warnings.add(new Warning(definitionId, "Malformed JSON: " + rootCause(e)));
            return new Result(models, warnings);
        }
        if (root == null || !root.isJsonObject()) {
            warnings.add(new Warning(definitionId, "Definition is not a JSON object"));
            return new Result(models, warnings);
        }
        JsonElement model = root.getAsJsonObject().get("model");
        if (model == null) {
            // Legal: a definition may exist purely to set properties such as
            // oversized_in_gui. Nothing to show, and not worth warning about.
            return new Result(models, warnings);
        }

        Walker walker = new Walker(baseItemId, definitionId, archivePath, sourcePackId,
                models, warnings);
        walker.walk(model, 0);
        return new Result(models, warnings);
    }

    // ------------------------------------------------------------------------

    private static final class Walker {

        private final String baseItemId;
        private final String definitionId;
        private final String archivePath;
        private final String sourcePackId;
        private final List<DiscoveredModel> out;
        private final List<Warning> warnings;
        private final Deque<Selector> path = new ArrayDeque<>();

        private int discoveryIndex = 0;
        private int transformationDepth = 0;
        private boolean truncated = false;

        Walker(String baseItemId, String definitionId, String archivePath, String sourcePackId,
               List<DiscoveredModel> out, List<Warning> warnings) {
            this.baseItemId = baseItemId;
            this.definitionId = definitionId;
            this.archivePath = archivePath;
            this.sourcePackId = sourcePackId;
            this.out = out;
            this.warnings = warnings;
        }

        void walk(JsonElement node, int depth) {
            if (node == null || truncated) {
                return;
            }
            if (depth > MAX_DEPTH) {
                warn("Model tree deeper than " + MAX_DEPTH + " levels; branch skipped");
                return;
            }
            if (!node.isJsonObject()) {
                // A bare string where an object belongs is a common pack typo.
                if (node.isJsonPrimitive()) {
                    warn("Expected a model object but found the string \""
                            + node.getAsString() + "\"");
                }
                return;
            }
            JsonObject object = node.getAsJsonObject();
            boolean carriesTransformation = object.has("transformation");
            if (carriesTransformation) {
                transformationDepth++;
            }
            try {
                dispatch(object, depth);
            } finally {
                if (carriesTransformation) {
                    transformationDepth--;
                }
            }
        }

        private void dispatch(JsonObject object, int depth) {
            String type = shortType(object.get("type"));
            if (type == null) {
                warn("Model node without a \"type\"");
                return;
            }
            switch (type) {
                case "model" -> emit(string(object.get("model")),
                        DiscoveredModel.LeafKind.MODEL, tintsOf(object));
                case "special" -> emit(string(object.get("base")),
                        DiscoveredModel.LeafKind.SPECIAL, List.of());
                case "empty" -> emit("", DiscoveredModel.LeafKind.EMPTY, List.of());
                case "bundle/selected_item" -> emit("",
                        DiscoveredModel.LeafKind.BUNDLE_CONTENT, List.of());
                case "composite" -> walkComposite(object, depth);
                case "condition" -> walkCondition(object, depth);
                case "select" -> walkSelect(object, depth);
                case "range_dispatch" -> walkRangeDispatch(object, depth);
                default -> warn("Unknown model type \"" + type + "\"");
            }
        }

        private void walkComposite(JsonObject object, int depth) {
            JsonElement models = object.get("models");
            if (models == null || !models.isJsonArray()) {
                warn("composite without a \"models\" array");
                return;
            }
            JsonArray array = models.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                push(new Selector(Selector.Kind.COMPOSITE, "", List.of(),
                        Float.NaN, 1.0F, Selector.Branch.MEMBER, i));
                walk(array.get(i), depth + 1);
                path.removeLast();
            }
        }

        private void walkCondition(JsonObject object, int depth) {
            String property = property(object);
            if (property == null) {
                warn("condition without a \"property\"");
                return;
            }
            push(new Selector(Selector.Kind.CONDITION, property, List.of(),
                    Float.NaN, 1.0F, Selector.Branch.MATCH, 0));
            walk(object.get("on_true"), depth + 1);
            path.removeLast();

            push(new Selector(Selector.Kind.CONDITION, property, List.of(),
                    Float.NaN, 1.0F, Selector.Branch.NEGATIVE, 1));
            walk(object.get("on_false"), depth + 1);
            path.removeLast();
        }

        private void walkSelect(JsonObject object, int depth) {
            String property = property(object);
            if (property == null) {
                warn("select without a \"property\"");
                return;
            }
            int dataIndex = intOr(object.get("index"), 0);
            // A select on "minecraft:component" also names which component it
            // reads, and that decides whether the branch means "a different
            // item" or "a different state of one".
            String componentId = string(object.get("component"));
            JsonElement cases = object.get("cases");
            if (cases != null && cases.isJsonArray()) {
                JsonArray array = cases.getAsJsonArray();
                for (int i = 0; i < array.size(); i++) {
                    JsonElement element = array.get(i);
                    if (!element.isJsonObject()) {
                        warn("select case " + i + " is not an object");
                        continue;
                    }
                    JsonObject caseObject = element.getAsJsonObject();
                    // "when" is a single value or an array of them. Vanilla's
                    // trident.json uses the array form, so both are required.
                    List<String> when = stringList(caseObject.get("when"));
                    if (when.isEmpty()) {
                        warn("select case " + i + " has no usable \"when\" value");
                        continue;
                    }
                    push(new Selector(Selector.Kind.SELECT, property, when,
                            Float.NaN, 1.0F, Selector.Branch.MATCH, i, dataIndex,
                            componentId));
                    walk(caseObject.get("model"), depth + 1);
                    path.removeLast();
                }
            } else if (cases != null) {
                warn("select \"cases\" is not an array");
            }
            walkFallback(object, property, Selector.Kind.SELECT, depth, dataIndex);
        }

        private void walkRangeDispatch(JsonObject object, int depth) {
            String property = property(object);
            if (property == null) {
                warn("range_dispatch without a \"property\"");
                return;
            }
            float scale = floatOr(object.get("scale"), 1.0F);
            int dataIndex = intOr(object.get("index"), 0);
            JsonElement entries = object.get("entries");
            if (entries != null && entries.isJsonArray()) {
                JsonArray array = entries.getAsJsonArray();
                for (int i = 0; i < array.size(); i++) {
                    JsonElement element = array.get(i);
                    if (!element.isJsonObject()) {
                        warn("range_dispatch entry " + i + " is not an object");
                        continue;
                    }
                    JsonObject entry = element.getAsJsonObject();
                    float threshold = floatOr(entry.get("threshold"), Float.NaN);
                    push(new Selector(Selector.Kind.RANGE_DISPATCH, property, List.of(),
                            threshold, scale, Selector.Branch.MATCH, i, dataIndex));
                    walk(entry.get("model"), depth + 1);
                    path.removeLast();
                }
            } else if (entries != null) {
                warn("range_dispatch \"entries\" is not an array");
            }
            walkFallback(object, property, Selector.Kind.RANGE_DISPATCH, depth, dataIndex);
        }

        private void walkFallback(JsonObject object, String property, Selector.Kind kind,
                                  int depth, int dataIndex) {
            JsonElement fallback = object.get("fallback");
            if (fallback == null) {
                return;
            }
            push(new Selector(kind, property, List.of(), Float.NaN, 1.0F,
                    Selector.Branch.FALLBACK, Integer.MAX_VALUE, dataIndex));
            walk(fallback, depth + 1);
            path.removeLast();
        }

        private void emit(String modelId, DiscoveredModel.LeafKind kind, List<String> tints) {
            if (out.size() >= MAX_LEAVES_PER_FILE) {
                if (!truncated) {
                    truncated = true;
                    warn("Stopped after " + MAX_LEAVES_PER_FILE
                            + " models; the rest of this definition was not read");
                }
                return;
            }
            if (modelId == null) {
                warn("Leaf node of type " + kind + " without a model reference");
                return;
            }
            out.add(new DiscoveredModel(baseItemId, definitionId, archivePath, sourcePackId,
                    modelId, kind, new ArrayList<>(path), tints,
                    discoveryIndex++, transformationDepth > 0));
        }

        private void push(Selector selector) {
            path.addLast(selector);
        }

        private void warn(String message) {
            if (warnings.size() < 200) {
                warnings.add(new Warning(definitionId, message));
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Records the {@code tints} array as readable text. Tints change an item's
     * colour without changing its geometry, so the explorer reports them rather
     * than trying to apply them.
     */
    private static List<String> tintsOf(JsonObject object) {
        JsonElement tints = object.get("tints");
        if (tints == null || !tints.isJsonArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonElement element : tints.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject tint = element.getAsJsonObject();
            String type = shortType(tint.get("type"));
            if (type == null) {
                continue;
            }
            JsonElement value = tint.has("value") ? tint.get("value") : tint.get("default");
            out.add(value == null ? type : type + " = " + value);
        }
        return Collections.unmodifiableList(out);
    }

    /** Reads {@code property}, tolerating the unnamespaced spelling. */
    private static String property(JsonObject object) {
        String raw = string(object.get("property"));
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        return raw.indexOf(':') >= 0 ? raw : "minecraft:" + raw;
    }

    /** Returns the type with any namespace stripped, or null. */
    private static String shortType(JsonElement element) {
        String raw = string(element);
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        int colon = raw.indexOf(':');
        return colon >= 0 ? raw.substring(colon + 1) : raw;
    }

    private static String string(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        try {
            return element.getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {@code when} accepts either a single value or an array of values. */
    private static List<String> stringList(JsonElement element) {
        if (element == null) {
            return List.of();
        }
        if (element.isJsonArray()) {
            List<String> out = new ArrayList<>();
            for (JsonElement child : element.getAsJsonArray()) {
                String value = string(child);
                if (value != null) {
                    out.add(value);
                }
            }
            return out;
        }
        String value = string(element);
        return value == null ? List.of() : List.of(value);
    }

    private static int intOr(JsonElement element, int fallback) {
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsInt();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static float floatOr(JsonElement element, float fallback) {
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsFloat();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static String rootCause(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message;
    }
}
