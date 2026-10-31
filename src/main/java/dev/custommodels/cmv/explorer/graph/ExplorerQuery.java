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
     * The models that look like a given one, closest first.
     *
     * <p>Six tiers, in this order, because "similar" is not one question and
     * the honest answers to it are of very different strengths. Order is the
     * answer's confidence, so an exact match is never buried among guesses.</p>
     *
     * <ol>
     *   <li><b>The same shape.</b> Identical geometry hash: the same model
     *       wearing another texture.</li>
     *   <li><b>The same textures.</b> Identical texture hash: one sheet cut
     *       into a family.</li>
     *   <li><b>The same template.</b> Both inherit one of the pack's own
     *       parent models.</li>
     *   <li><b>The same folder.</b> Both filed in the same subfolder. A pack
     *       that puts its plushes in {@code item/plushes/} has said they belong
     *       together, and that is better evidence than any measurement of the
     *       geometry.</li>
     *   <li><b>A shared texture.</b> They use at least one texture file in
     *       common, without the whole set matching.</li>
     *   <li><b>The same build.</b> Same kind of item, about as many cuboids,
     *       about as many texture slots. A guess, and last.</li>
     * </ol>
     *
     * <p>The first three alone were too tight to be useful. They found the same
     * model in nine colours and nothing else: a family of plushes differing by
     * one cuboid shared no hash, no template and no exact cuboid count, so a
     * question whose whole point is "what else is like this" answered "nothing
     * is". Hence the folder, the partial texture match, and a tolerance on the
     * counts rather than equality.</p>
     *
     * <p>What keeps the loose tiers from swallowing the pack: the item folder
     * itself is not a folder signal, because a pack that files everything flat
     * has said nothing by doing so; a vanilla parent is not a template, since
     * every generated item shares one; and flat models are left out of the last
     * tier entirely, a sprite's shape being its texture rather than its
     * geometry. An empty hash matches nothing rather than everything.</p>
     *
     * <p>The subject leads the list rather than being left out of it. The
     * question is nearly always "which of these did I mean", and that is
     * answered by seeing it beside the others, not instead of them.</p>
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
        String template = templateOf(subject);
        String folder = folderOf(subject);
        java.util.Set<String> sheets =
                new java.util.HashSet<>(subject.defaultState().resolved().textureSlots().values());

        java.util.List<String> sameShape = new java.util.ArrayList<>();
        java.util.List<String> sameTextures = new java.util.ArrayList<>();
        java.util.List<String> sameTemplate = new java.util.ArrayList<>();
        java.util.List<String> sameFolder = new java.util.ArrayList<>();
        java.util.List<String> sharedSheet = new java.util.ArrayList<>();
        java.util.List<String> sameBuild = new java.util.ArrayList<>();

        for (LogicalModel model : all) {
            if (model == null) {
                continue;
            }
            String other = model.defaultState().modelId();
            if (other.equals(id)) {
                continue;
            }
            if (!geometry.isEmpty() && geometry.equals(model.geometryHash())) {
                sameShape.add(other);
            } else if (!textures.isEmpty() && textures.equals(model.textureHash())) {
                sameTextures.add(other);
            } else if (!template.isEmpty() && template.equals(templateOf(model))) {
                sameTemplate.add(other);
            } else if (!folder.isEmpty() && folder.equals(folderOf(model))) {
                sameFolder.add(other);
            } else if (sharesASheet(sheets, model)) {
                sharedSheet.add(other);
            } else if (sameBuildAs(subject, model)) {
                sameBuild.add(other);
            }
        }
        out.addAll(sameShape);
        out.addAll(sameTextures);
        out.addAll(sameTemplate);
        out.addAll(sameFolder);
        out.addAll(sharedSheet);
        out.addAll(sameBuild);
        return out;
    }

    /**
     * The pack's own template a model inherits from, or {@code ""} for none.
     *
     * <p>The nearest ancestor outside the {@code minecraft} namespace. The
     * chain holds ancestors only \u2014 not the model itself \u2014 so two models
     * sharing an answer here really do share a parent.</p>
     */
    public static String templateOf(LogicalModel model) {
        if (model == null) {
            return "";
        }
        for (String parent : model.defaultState().resolved().parentChain()) {
            if (parent == null || parent.isEmpty()) {
                continue;
            }
            int colon = parent.indexOf(':');
            String namespace = colon < 0 ? "minecraft" : parent.substring(0, colon);
            if (!namespace.equals("minecraft")) {
                return parent;
            }
        }
        return "";
    }

    /**
     * The subfolder a model is filed in, or {@code ""} when it is not in one.
     *
     * <p>The item folder itself does not count. Everything lands there by
     * default, so a pack that files its models flat has expressed no grouping,
     * and reading one into it would make every model in that pack similar to
     * every other.</p>
     */
    public static String folderOf(LogicalModel model) {
        if (model == null) {
            return "";
        }
        String id = model.defaultState().modelId();
        int slash = id.lastIndexOf('/');
        if (slash < 0) {
            return "";
        }
        String folder = id.substring(0, slash);
        return folder.endsWith("item") || folder.endsWith("items") ? "" : folder;
    }

    /** Whether a model draws with any of the same texture files. */
    public static boolean sharesASheet(java.util.Set<String> sheets, LogicalModel other) {
        if (sheets == null || sheets.isEmpty() || other == null) {
            return false;
        }
        for (String textureId : other.defaultState().resolved().textureSlots().values()) {
            if (textureId != null && sheets.contains(textureId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The loosest tier: about as complicated, on the same kind of item.
     *
     * <p>About, not exactly. A family of plushes where one has an extra cuboid
     * for an ear is the case this tier exists to catch, and equality excluded
     * precisely that. The tolerance is a quarter of the smaller count, with a
     * floor of two so that small models have any latitude at all \u2014 one
     * cuboid against six is still a different thing.</p>
     *
     * <p>Item type is checked too, because "twelve cuboids and one texture
     * slot" on its own describes half a pack. With it, the answer to "what
     * else looks like this sword" is other swords.</p>
     */
    public static boolean sameBuildAs(LogicalModel subject, LogicalModel other) {
        if (subject == null || other == null) {
            return false;
        }
        var mine = subject.defaultState().resolved();
        var theirs = other.defaultState().resolved();
        if (mine.generated() || theirs.generated()) {
            return false;
        }
        int ours = mine.elementCount();
        int yours = theirs.elementCount();
        if (ours <= 0 || yours <= 0) {
            return false;
        }
        if (Math.abs(ours - yours) > Math.max(2, Math.min(ours, yours) / 4)) {
            return false;
        }
        if (Math.abs(mine.textureSlots().size() - theirs.textureSlots().size()) > 1) {
            return false;
        }
        return subject.type() == other.type();
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
