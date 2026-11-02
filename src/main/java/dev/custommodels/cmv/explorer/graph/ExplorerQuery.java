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
     * The models that look like a given one, closest first, as a shortlist.
     *
     * <p>Six tiers. Order is the answer's confidence, so an exact match is
     * never buried among the guesses.</p>
     *
     * <ol>
     *   <li><b>The same shape.</b> Identical geometry hash.</li>
     *   <li><b>The same textures.</b> Identical texture hash.</li>
     *   <li><b>The same template.</b> Both inherit one of the pack's own
     *       parent models.</li>
     *   <li><b>A name in common.</b> They share a word that few other models
     *       use \u2014 rarest word first.</li>
     *   <li><b>The same folder</b>, where the folder is a folder and not the
     *       place the pack puts everything.</li>
     *   <li><b>A shared sheet</b>, where the sheet is not the pack's palette.</li>
     * </ol>
     *
     * <p>The fourth tier is the one that works on a real pack, and it took
     * measuring one to find that out. Two plushes a player called obviously
     * similar had bounding boxes of 10x25x16 and 9x12x10 and 24 cuboids
     * against 15: the elf's hat alone makes it twice as tall as the other, so
     * no measurement of the geometry puts them together, and the first three
     * tiers put them nowhere near each other. What they share is the word
     * <em>plushie</em>, which 30 of that pack's 5,086 models carry. The pack
     * author grouped them by naming them, which is what pack authors do.</p>
     *
     * <p>Every loose tier is then held to one rule: a group of more than
     * {@value #FAMILY_SIZE} is a category rather than a family, and says
     * nothing. In the pack this was measured against, that admits
     * <em>plushie</em> (30) and <em>lucky</em> (42) while excluding
     * <em>decor</em> (423), <em>shield</em> (252) and <em>sword</em> (242). It
     * is the same rule that stops a shared palette counting as a shared sheet,
     * and the same rule that stopped the folder tier matching the entire pack:
     * every model there is filed under {@code blockbench/}, so "same folder"
     * meant "same pack" and the answer was a hundred and ten pages of
     * whatever happened to be indexed first.</p>
     *
     * <p>Words are at least four letters and carry no digits, so dates and
     * numbering do not bind a family together. The answer is capped at
     * {@value #MOST_ALIKE}, taking the tiers in order, so what is dropped is
     * always the weakest evidence.</p>
     *
     * <p>The subject leads the list rather than being left out of it: the
     * question is nearly always "which of these did I mean".</p>
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

        // One pass to find out which of this model's groups are small enough
        // to mean anything, then one to sort the models into tiers.
        java.util.Map<String, Integer> words = new java.util.HashMap<>();
        for (String word : tokensOf(subject)) {
            words.put(word, 0);
        }
        java.util.Set<String> sheets =
                new java.util.HashSet<>(subject.defaultState().resolved().textureSlots().values());
        java.util.Map<String, Integer> sheetUse = new java.util.HashMap<>();
        for (String sheet : sheets) {
            sheetUse.put(sheet, 0);
        }
        String folder = folderOf(subject);
        int inFolder = 0;
        for (LogicalModel model : all) {
            if (model == null) {
                continue;
            }
            if (!folder.isEmpty() && folder.equals(folderOf(model))) {
                inFolder++;
            }
            for (String textureId : model.defaultState().resolved().textureSlots().values()) {
                sheetUse.computeIfPresent(textureId, (key, seen) -> seen + 1);
            }
            for (String word : tokensOf(model)) {
                words.computeIfPresent(word, (key, seen) -> seen + 1);
            }
        }
        words.values().removeIf(seen -> seen > FAMILY_SIZE);
        sheetUse.values().removeIf(seen -> seen > FAMILY_SIZE);
        java.util.Set<String> familySheets = sheetUse.keySet();
        boolean folderMeansSomething = !folder.isEmpty() && inFolder <= FAMILY_SIZE;

        java.util.List<String> sameShape = new java.util.ArrayList<>();
        java.util.List<String> sameTextures = new java.util.ArrayList<>();
        java.util.List<String> sameTemplate = new java.util.ArrayList<>();
        java.util.List<Named> sameName = new java.util.ArrayList<>();
        java.util.List<String> sameFolder = new java.util.ArrayList<>();
        java.util.List<String> sharedSheet = new java.util.ArrayList<>();

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
                continue;
            }
            if (!textures.isEmpty() && textures.equals(model.textureHash())) {
                sameTextures.add(other);
                continue;
            }
            if (!template.isEmpty() && template.equals(templateOf(model))) {
                sameTemplate.add(other);
                continue;
            }
            int rarest = Integer.MAX_VALUE;
            for (String word : tokensOf(model)) {
                Integer seen = words.get(word);
                if (seen != null && seen < rarest) {
                    rarest = seen;
                }
            }
            if (rarest < Integer.MAX_VALUE) {
                sameName.add(new Named(other, rarest));
                continue;
            }
            if (folderMeansSomething && folder.equals(folderOf(model))) {
                sameFolder.add(other);
                continue;
            }
            if (sharesASheet(familySheets, model)) {
                sharedSheet.add(other);
            }
        }
        // The rarer the shared word, the likelier it names a family rather
        // than a kind of thing.
        sameName.sort(java.util.Comparator.comparingInt(Named::rarity));

        addUpToTheCap(out, sameShape);
        addUpToTheCap(out, sameTextures);
        addUpToTheCap(out, sameTemplate);
        for (Named named : sameName) {
            if (out.size() > MOST_ALIKE) {
                break;
            }
            out.add(named.id());
        }
        addUpToTheCap(out, sameFolder);
        addUpToTheCap(out, sharedSheet);
        return out;
    }

    /** A look-alike found by name, and how rare the word they share is. */
    private record Named(String id, int rarity) {
    }

    /** The most look-alikes worth offering before it stops being an answer. */
    public static final int MOST_ALIKE = 120;

    /**
     * Past this, a group is a category rather than a family.
     *
     * <p>One number for folders, texture sheets and words alike, because it is
     * one idea: a thing five hundred models have in common is what kind of
     * thing they are, and saying those five hundred look alike is true and
     * useless.</p>
     */
    public static final int FAMILY_SIZE = 60;

    /**
     * The words in a model's own file name.
     *
     * <p>The last part of the model id, cut on underscores and hyphens, keeping
     * runs of letters of four or more. Digits are dropped with the words that
     * contain them, so {@code 2024dec} and {@code plushie_2} do not bind
     * anything together \u2014 a date is not a family and neither is a number.</p>
     */
    public static java.util.Set<String> tokensOf(LogicalModel model) {
        if (model == null) {
            return java.util.Set.of();
        }
        String id = model.defaultState().modelId();
        int slash = id.lastIndexOf('/');
        String name = slash < 0 ? id : id.substring(slash + 1);
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        StringBuilder word = new StringBuilder();
        boolean spoiled = false;
        for (int i = 0; i <= name.length(); i++) {
            char at = i == name.length() ? '_' : Character.toLowerCase(name.charAt(i));
            if (at >= 'a' && at <= 'z') {
                word.append(at);
                continue;
            }
            if (at >= '0' && at <= '9') {
                // A run with a digit in it is a date or an index, not a word.
                spoiled = true;
                continue;
            }
            if (!spoiled && word.length() >= 4) {
                out.add(word.toString());
            }
            word.setLength(0);
            spoiled = false;
        }
        return out;
    }

    private static void addUpToTheCap(java.util.Set<String> out, java.util.List<String> tier) {
        for (String id : tier) {
            // Counted past the subject, which is already in the set and is not
            // one of its own look-alikes.
            if (out.size() > MOST_ALIKE) {
                return;
            }
            out.add(id);
        }
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
     * The folder a model is filed in, or {@code ""} when it is not in one.
     *
     * <p>The item folder itself does not count, because everything lands there
     * by default. Whether any other folder counts is decided by how many
     * models are in it, which is not something this can answer on its own.</p>
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

    /** Whether a model draws with any of these texture files. */
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
