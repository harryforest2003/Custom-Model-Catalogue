package dev.custommodels.cmv.explorer.graph;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The filter and search applied to the model list.
 *
 * <p>Filters and search compose: narrowing the type and typing a word both
 * apply, and neither triggers a rescan. Evaluation is a linear pass over the
 * already-built list, with the per-model text prepared once in
 * {@link LogicalModel#searchBlob()}, so a keystroke costs a few substring scans
 * per model rather than a walk of the object graph. Ten thousand models come to
 * roughly a millisecond, which is fast enough to run on every keystroke without
 * debouncing.</p>
 *
 * <h2>Query syntax</h2>
 *
 * <p>Terms are separated by spaces and all must match. A term may be prefixed
 * with a field to restrict it:</p>
 *
 * <pre>
 *   katana                 anything
 *   item:diamond_sword     base item only
 *   model:bows/dragon      model id or path only
 *   texture:bows/dragon    texture id only
 *   ns:example             namespace only
 *   pack:skyrealm          source pack only
 *   cmd:dragon_bow         custom model data only
 *   state:pull             state names only
 *   def:items/bow          item definition path only
 *   type:bow               item type only
 * </pre>
 */
public record ExplorerQuery(String text,
                            ItemType type,
                            String namespace,
                            String sourcePack,
                            String baseItem,
                            StateFilter states,
                            boolean onlyCustomModelData,
                            boolean onlyWithWarnings,
                            boolean onlyRealItems) {

    /**
     * The models that look like a given one, that one first.
     *
     * <p>Two hashes are already worked out for every model during the scan,
     * because collapsing duplicates needs them: one over the geometry and one
     * over the set of textures. Sharing either is what "looks like this"
     * means, and the two kinds of match are different and both wanted — same
     * geometry with different textures is a recolour of one thing, and same
     * textures with different geometry is a family built from one sheet.</p>
     *
     * <p>An empty hash matches nothing, not everything. A model with no
     * geometry or no textures has one, and treating absence as a value would
     * have every broken model in a pack claim to look like every other.</p>
     *
     * <p>The subject leads the list rather than being left out of it. The
     * question is nearly always "which of these did I mean", and that is
     * answered by seeing it beside the others, not instead of them.</p>
     *
     * <p>Ids are the default state's model id, which is what the grid
     * restricts itself on and what a saved set holds. {@code canonicalId} is
     * the stabler identity and the wrong one here: nothing else keys a visible
     * list on it.</p>
     */
    public static java.util.Set<String> alike(java.util.List<LogicalModel> all,
                                              LogicalModel subject) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (subject == null) {
            return out;
        }
        String id = subject.defaultState().modelId();
        out.add(id);
        if (all == null) {
            return out;
        }
        String geometry = subject.geometryHash() == null ? "" : subject.geometryHash();
        String textures = subject.textureHash() == null ? "" : subject.textureHash();
        if (geometry.isEmpty() && textures.isEmpty()) {
            return out;
        }
        for (LogicalModel model : all) {
            if (model == null || model.defaultState().modelId().equals(id)) {
                continue;
            }
            if (!geometry.isEmpty() && geometry.equals(model.geometryHash())) {
                out.add(model.defaultState().modelId());
                continue;
            }
            if (!textures.isEmpty() && textures.equals(model.textureHash())) {
                out.add(model.defaultState().modelId());
            }
        }
        return out;
    }

    /** Filter on how many appearances an item has. */
    public enum StateFilter {
        ANY("Any"),
        MULTIPLE("Multiple states"),
        SINGLE("Single state");

        private final String label;

        StateFilter(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public StateFilter next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    public static final ExplorerQuery EMPTY = new ExplorerQuery(
            "", ItemType.ALL, "", "", "", StateFilter.ANY, false, false, false);

    public ExplorerQuery withText(String newText) {
        return new ExplorerQuery(newText, type, namespace, sourcePack, baseItem,
                states, onlyCustomModelData, onlyWithWarnings, onlyRealItems);
    }

    public ExplorerQuery withType(ItemType newType) {
        return new ExplorerQuery(text, newType, namespace, sourcePack, baseItem,
                states, onlyCustomModelData, onlyWithWarnings, onlyRealItems);
    }

    public ExplorerQuery withNamespace(String newNamespace) {
        return new ExplorerQuery(text, type, newNamespace, sourcePack, baseItem,
                states, onlyCustomModelData, onlyWithWarnings, onlyRealItems);
    }

    public ExplorerQuery withSourcePack(String newPack) {
        return new ExplorerQuery(text, type, namespace, newPack, baseItem,
                states, onlyCustomModelData, onlyWithWarnings, onlyRealItems);
    }

    public ExplorerQuery withStates(StateFilter newStates) {
        return new ExplorerQuery(text, type, namespace, sourcePack, baseItem,
                newStates, onlyCustomModelData, onlyWithWarnings, onlyRealItems);
    }

    public ExplorerQuery withOnlyCustomModelData(boolean value) {
        return new ExplorerQuery(text, type, namespace, sourcePack, baseItem,
                states, value, onlyWithWarnings, onlyRealItems);
    }

    public ExplorerQuery withOnlyWithWarnings(boolean value) {
        return new ExplorerQuery(text, type, namespace, sourcePack, baseItem,
                states, onlyCustomModelData, value, onlyRealItems);
    }

    public ExplorerQuery withOnlyRealItems(boolean value) {
        return new ExplorerQuery(text, type, namespace, sourcePack, baseItem,
                states, onlyCustomModelData, onlyWithWarnings, value);
    }

    public boolean isEmpty() {
        return text.isBlank() && type == ItemType.ALL && namespace.isEmpty()
                && sourcePack.isEmpty() && baseItem.isEmpty()
                && states == StateFilter.ANY && !onlyCustomModelData
                && !onlyWithWarnings && !onlyRealItems;
    }

    // ---------------------------------------------------------------- apply

    /**
     * Orders models the same way the wrap list is ordered.
     *
     * <p>Age is the pack's own order: which pack a model came from, then where
     * in that pack it was found. A pack author adds new things at the end of a
     * file, so discovery order is the closest thing to a date that a resource
     * pack actually carries — there are no timestamps inside one.</p>
     */
    public static List<LogicalModel> sort(List<LogicalModel> from,
                                          dev.custommodels.cmv.wrap.WrapCatalogue.Order order) {
        List<LogicalModel> out = new ArrayList<>(from);
        java.util.Comparator<LogicalModel> byName =
                java.util.Comparator.comparing(m -> m.displayName().toLowerCase(Locale.ROOT));
        java.util.Comparator<LogicalModel> byAge = java.util.Comparator
                .comparingInt(LogicalModel::packOrdinal)
                .thenComparingInt(LogicalModel::discoveryIndex);
        out.sort(switch (order) {
            case NAME -> byName;
            case NAME_DESC -> byName.reversed();
            case OLDEST -> byAge.thenComparing(byName);
            case NEWEST -> byAge.reversed().thenComparing(byName);
        });
        return out;
    }

    /** Applies this query to a list, preserving its order. */
    public List<LogicalModel> apply(List<LogicalModel> models) {
        if (isEmpty()) {
            return models;
        }
        List<Term> terms = parse(text);
        List<LogicalModel> out = new ArrayList<>(Math.min(models.size(), 256));
        for (LogicalModel model : models) {
            if (matchesFilters(model) && matchesTerms(model, terms)) {
                out.add(model);
            }
        }
        return out;
    }

    private boolean matchesFilters(LogicalModel model) {
        if (type != ItemType.ALL && model.type() != type) {
            return false;
        }
        if (!namespace.isEmpty() && !namespace.equals(model.namespace())) {
            return false;
        }
        if (!sourcePack.isEmpty() && !sourcePack.equals(model.sourcePackId())) {
            return false;
        }
        if (!baseItem.isEmpty() && !baseItem.equals(model.baseItemId())) {
            return false;
        }
        if (states == StateFilter.MULTIPLE && !model.hasMultipleStates()) {
            return false;
        }
        if (states == StateFilter.SINGLE && model.hasMultipleStates()) {
            return false;
        }
        if (onlyCustomModelData && model.customModelData().isEmpty()) {
            return false;
        }
        // The single most useful filter on a large server pack: it drops the
        // thousands of entries that are model-engine internals and digit
        // overlays, leaving the models that a real item can actually wear.
        if (onlyRealItems && !model.baseItemIsRegistered()) {
            return false;
        }
        return !onlyWithWarnings || !model.warnings().isEmpty();
    }

    private static boolean matchesTerms(LogicalModel model, List<Term> terms) {
        for (Term term : terms) {
            if (!term.matches(model)) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- terms

    /** One space-separated piece of the query, optionally field-scoped. */
    private record Term(Field field, String value) {

        boolean matches(LogicalModel model) {
            return switch (field) {
                case ANY -> model.searchBlob().contains(value);
                case ITEM -> contains(model.baseItemId(), value);
                case MODEL -> {
                    for (String id : model.allModelIds()) {
                        if (contains(id, value)) {
                            yield true;
                        }
                    }
                    for (ModelState state : model.states()) {
                        if (contains(state.resolved().archivePath(), value)) {
                            yield true;
                        }
                    }
                    yield false;
                }
                case TEXTURE -> {
                    for (String texture : model.allTextures()) {
                        if (contains(texture, value)) {
                            yield true;
                        }
                    }
                    yield false;
                }
                case NAMESPACE -> contains(model.namespace(), value);
                case PACK -> contains(model.sourcePackId(), value);
                case CUSTOM_MODEL_DATA -> {
                    for (String data : model.customModelData()) {
                        if (contains(data, value)) {
                            yield true;
                        }
                    }
                    yield false;
                }
                case STATE -> {
                    for (ModelState state : model.states()) {
                        if (contains(state.label(), value)) {
                            yield true;
                        }
                    }
                    yield false;
                }
                case DEFINITION -> contains(model.definitionArchivePath(), value)
                        || contains(model.definitionId(), value);
                case TYPE -> contains(model.type().name(), value)
                        || contains(model.type().displayName(), value);
                case NAME -> contains(model.displayName(), value);
            };
        }

        private static boolean contains(String haystack, String needle) {
            return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
        }
    }

    private enum Field {
        ANY, ITEM, MODEL, TEXTURE, NAMESPACE, PACK, CUSTOM_MODEL_DATA,
        STATE, DEFINITION, TYPE, NAME
    }

    private static List<Term> parse(String raw) {
        List<Term> terms = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return terms;
        }
        for (String piece : raw.trim().split("\\s+")) {
            int colon = piece.indexOf(':');
            // A term such as "example:item/bow" is an identifier, not a field
            // scope, so only known prefixes are treated as fields.
            if (colon > 0) {
                Field field = fieldFor(piece.substring(0, colon));
                if (field != null) {
                    String value = piece.substring(colon + 1);
                    if (!value.isEmpty()) {
                        terms.add(new Term(field, value.toLowerCase(Locale.ROOT)));
                    }
                    continue;
                }
            }
            terms.add(new Term(Field.ANY, piece.toLowerCase(Locale.ROOT)));
        }
        return terms;
    }

    private static Field fieldFor(String prefix) {
        return switch (prefix.toLowerCase(Locale.ROOT)) {
            case "item", "base" -> Field.ITEM;
            case "model", "path" -> Field.MODEL;
            case "texture", "tex" -> Field.TEXTURE;
            case "ns", "namespace" -> Field.NAMESPACE;
            case "pack" -> Field.PACK;
            case "cmd", "data" -> Field.CUSTOM_MODEL_DATA;
            case "state" -> Field.STATE;
            case "def", "definition" -> Field.DEFINITION;
            case "type" -> Field.TYPE;
            case "name" -> Field.NAME;
            default -> null;
        };
    }

    // ------------------------------------------------------ facet discovery

    /** Namespaces present in a list, for the namespace filter's options. */
    public static List<String> namespacesOf(List<LogicalModel> models) {
        Set<String> values = new LinkedHashSet<>();
        for (LogicalModel model : models) {
            values.add(model.namespace());
        }
        List<String> sorted = new ArrayList<>(values);
        sorted.sort(String::compareTo);
        return sorted;
    }

    /**
     * Resource packs present in a list, for the pack filter.
     *
     * <p>Only packs the player actually installed. Every Fabric mod is also a
     * resource pack as far as the game is concerned, and a heavily modded
     * profile has a couple of hundred of them — a filter listing those is a
     * filter nobody can find anything in. {@code file/} is the game's own mark
     * for a pack that came out of the {@code resourcepacks} folder, which is
     * exactly the question the filter is asking.</p>
     *
     * <p>The mod's own generated pack is left out too. It holds no models of
     * its own — only merged item definitions pointing at other packs' models —
     * so it is an implementation detail rather than somewhere to browse.</p>
     */
    public static List<String> packsOf(List<LogicalModel> models) {
        Set<String> values = new LinkedHashSet<>();
        for (LogicalModel model : models) {
            String pack = model.sourcePackId();
            if (dev.custommodels.cmv.explorer.scan.PackFingerprint.isUserPack(pack)) {
                values.add(pack);
            }
        }
        List<String> sorted = new ArrayList<>(values);
        sorted.sort(String::compareTo);
        return sorted;
    }
}
