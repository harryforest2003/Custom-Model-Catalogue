package dev.custommodels.cmv.wrap;

import dev.custommodels.cmv.explorer.def.Selector;
import dev.custommodels.cmv.explorer.graph.ItemType;
import dev.custommodels.cmv.explorer.graph.LogicalModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The set of appearances that can be applied to items, derived from the model
 * database the explorer already built.
 *
 * <p>Nothing here scans anything. The catalogue is a view over
 * the explorer index's finished list: it regroups those models by the model
 * they point at, works out which items each is associated with, and stops.
 * Building it for a pack of fifty thousand models is a few map operations, so
 * opening the Wrap Bank costs nothing beyond what the explorer already paid.</p>
 *
 * <h2>Compatibility</h2>
 *
 * <p>Two models that resolve to the same file but hang off different base items
 * — the same greatsword offered on both the diamond and the netherite sword —
 * are one wrap with two compatible items, not two wraps. Grouping by model id
 * is what recovers that, and it is also what makes the compatibility list a
 * fact from the pack rather than a guess from the name.</p>
 */
public final class WrapCatalogue {

    private final List<WrapTarget> targets;
    private final Map<String, String> blobs = new LinkedHashMap<>();
    /** Keyed by the model drawn, which is how the catalogue is grouped anyway. */
    private final Map<String, WrapTarget> byModelId = new LinkedHashMap<>();
    /**
     * What each wrap's model calls itself, by name alone.
     *
     * <p>Held here rather than on the wrap because it is a reading of the
     * model's file name, not a fact about the wrap, and because working it out
     * costs a tokenise that the offered list would otherwise repeat.</p>
     */
    private final Map<WrapTarget, ItemType> namedKind = new java.util.IdentityHashMap<>();
    private final java.util.function.Function<String, ItemType> itemKind;
    private final String fingerprint;

    private WrapCatalogue(List<WrapTarget> targets,
                          java.util.function.Function<String, ItemType> itemKind,
                          String fingerprint) {
        this.targets = targets;
        this.itemKind = itemKind;
        this.fingerprint = fingerprint;
        for (WrapTarget target : targets) {
            blobs.put(target.id(), target.searchBlob());
            byModelId.putIfAbsent(target.model().defaultState().modelId(), target);
            ItemType kind = dev.custommodels.cmv.explorer.graph.GraphBuilder
                    .fromModelPath(target.model().defaultState().modelId());
            if (kind != null) {
                namedKind.put(target, kind);
            }
        }
    }

    public List<WrapTarget> all() {
        return targets;
    }

    /** The scan this catalogue was built from, so it can be invalidated. */
    public String fingerprint() {
        return fingerprint;
    }

    public boolean isEmpty() {
        return targets.isEmpty();
    }

    /**
     * Builds a catalogue from the current scan.
     *
     * @param models    the explorer's logical models
     * @param itemExists whether an id names a registered item
     */
    public static WrapCatalogue build(List<LogicalModel> models,
                                      java.util.function.Predicate<String> itemExists,
                                      String fingerprint) {
        // No classifier: nothing is known about what the item in hand is, so
        // the offered list is not narrowed by kind.
        return build(models, itemExists, id -> null, fingerprint);
    }

    /**
     * @param itemKind what the game says an item is, for deciding whether a
     *                 model belongs on it; see {@link #compatibleWith}
     */
    public static WrapCatalogue build(List<LogicalModel> models,
                                      java.util.function.Predicate<String> itemExists,
                                      java.util.function.Function<String, ItemType> itemKind,
                                      String fingerprint) {
        // Group by the model actually drawn, so the same appearance offered on
        // several items becomes one wrap with several compatible items.
        Map<String, List<LogicalModel>> byModel = new LinkedHashMap<>();
        for (LogicalModel model : models) {
            String modelId = model.defaultState().modelId();
            if (modelId.isEmpty() || !model.isRenderable()) {
                // Nothing to show and nothing to apply.
                continue;
            }
            byModel.computeIfAbsent(modelId, key -> new ArrayList<>()).add(model);
        }

        Set<String> losers = shadowedValues(models);

        List<WrapTarget> targets = new ArrayList<>(byModel.size());
        for (Map.Entry<String, List<LogicalModel>> entry : byModel.entrySet()) {
            WrapTarget target = toTarget(entry.getValue(), itemExists, losers);
            if (target != null) {
                targets.add(target);
            }
        }

        targets = mergeIdentical(targets);
        targets.sort(Comparator.comparing(WrapTarget::displayName));
        return new WrapCatalogue(List.copyOf(targets), itemKind, fingerprint);
    }

    /**
     * Turns one group of logical models into a wrap.
     *
     * <p>Which component to write is decided by how the pack reaches the model.
     * A model selected by a {@code custom_model_data} case inside a real item's
     * own definition is applied by writing that string; the item has to be that
     * item for the case to fire. A model reached through a definition that is
     * not itself an item — which is how a pack's own namespace works since
     * 1.21.4 — is applied by pointing {@code item_model} at that definition.</p>
     */
    /**
     * One tile per thing that can be written, rather than per model.
     *
     * <p>Grouping is by the model drawn, which is usually the same question —
     * but not always. A bow's draw stages are four model files sharing one
     * custom model data value, and where the grouping does not fold them into
     * states of each other they arrive here as four wraps that write the
     * identical component onto the identical item. Applying any of them does
     * exactly the same thing, so offering four cards is offering a choice that
     * does not exist.</p>
     *
     * <p>Identical means identical: same mechanism, same value, same index,
     * same items. Two wraps that differ anywhere in what they write are left
     * alone, because then they really are different wraps.</p>
     *
     * <p>The survivor is the one with the shortest model id, which is how these
     * families are named — {@code bow} before {@code bow_0}, the base before
     * the stage. That is a naming convention rather than a rule, so it decides
     * only which of several identical things is shown, never whether something
     * is shown at all.</p>
     */
    private static List<WrapTarget> mergeIdentical(List<WrapTarget> targets) {
        Map<String, WrapTarget> byWrite = new LinkedHashMap<>();
        for (WrapTarget target : targets) {
            String key = writeSignature(target);
            WrapTarget existing = byWrite.get(key);
            if (existing == null || shorterId(target, existing)) {
                byWrite.put(key, target);
            }
        }
        return new ArrayList<>(byWrite.values());
    }

    private static boolean shorterId(WrapTarget candidate, WrapTarget existing) {
        String a = candidate.model().defaultState().modelId();
        String b = existing.model().defaultState().modelId();
        return a.length() != b.length() ? a.length() < b.length() : a.compareTo(b) < 0;
    }

    /** Everything that decides what applying this wrap puts on the item. */
    private static String writeSignature(WrapTarget target) {
        StringBuilder key = new StringBuilder(64);
        key.append(target.kind()).append('\u0000')
                .append(target.itemModelId()).append('\u0000')
                .append(target.customName()).append('\u0000');
        for (String item : new java.util.TreeSet<>(target.compatibleItems())) {
            key.append(item).append(',');
            for (WrapTarget.CustomModelDataEntry entry : target.entriesFor(item)) {
                key.append(entry.numeric() ? "f" : "s").append(entry.index())
                        .append('=').append(entry.numeric()
                                ? Float.toString(entry.number()) : entry.text())
                        .append(';');
            }
            key.append('\u0000');
        }
        return key.toString();
    }

    /**
     * The mod's own definition for this appearance, if one was generated.
     *
     * <p>Grouping is by the model actually drawn, so a definition the mod wrote
     * pointing at that model is a member of the same group as the pack's own
     * route to it. That is the join: nothing has to be looked up, because the
     * two are already together by the time this is asked.</p>
     *
     * <p>Only the mod's namespace counts. Another pack's definition reaching
     * the same model is that pack's business and may be shadowed; this one
     * cannot be, which is the property the fallback depends on.</p>
     */
    private static String generatedRouteIn(List<LogicalModel> group) {
        // Always none: this catalogue generates no definitions, so no model is
        // ever reached by one. Kept as the single place that answers the
        // question, rather than scattering the assumption through the callers.
        return "";
    }

    private static WrapTarget toTarget(List<LogicalModel> group,
                                       java.util.function.Predicate<String> itemExists,
                                       Set<String> losers) {
        // Prefer a member whose base item is real: it carries the compatibility
        // information and the better classification.
        LogicalModel primary = group.stream()
                .filter(LogicalModel::baseItemIsRegistered)
                .findFirst()
                .orElse(group.get(0));

        Set<String> compatible = new LinkedHashSet<>();
        boolean anyCustomModelData = false;
        // The value per item, because a pack numbers its models inside each
        // item definition and the same appearance is routinely a different
        // number on every item it is offered on. Taking one member's value and
        // writing it onto all of them draws whatever that number happens to
        // mean on the other item, which is a different skin, not a failure.
        Map<String, List<WrapTarget.CustomModelDataEntry>> byItem = new LinkedHashMap<>();
        for (LogicalModel model : group) {
            if (model.baseItemIsRegistered() && itemExists.test(model.baseItemId())) {
                compatible.add(model.baseItemId());
                if (!model.customModelData().isEmpty()) {
                    anyCustomModelData = true;
                }
                List<WrapTarget.CustomModelDataEntry> entries =
                        customModelDataEntries(model);
                if (!entries.isEmpty()
                        && !losers.contains(loserKey(model.baseItemId(), entries,
                                model.defaultState().modelId()))) {
                    // First usable wins. An item may reach one model at more
                    // than one value, and where it does the pack's own
                    // redundancy is not a choice for the player — but a value
                    // another model wins on this item is no choice at all, so
                    // it is passed over in favour of one that works.
                    byItem.putIfAbsent(model.baseItemId(), entries);
                }
            }
        }

        WrapTarget.Kind kind;
        String itemModelId = "";
        String customName = customNameFor(primary);
        List<WrapTarget.CustomModelDataEntry> customModelData =
                customModelDataEntries(primary);

        if (anyCustomModelData && !compatible.isEmpty()
                && (!byItem.isEmpty() || !customModelData.isEmpty())) {
            kind = WrapTarget.Kind.CUSTOM_MODEL_DATA;
            // A definition the mod generated for this appearance, if there is
            // one. It is in the same group because grouping is by the model
            // drawn, and it is what lets the wrap reach an item the pack never
            // offered it on. Recorded even here, where the pack's own number is
            // the preferred route: the fallback is only consulted per item, and
            // only where that number does not exist.
            itemModelId = generatedRouteIn(group);
            if (!byItem.isEmpty()) {
                // The representative, for display and for search: the primary
                // item's own value where it has one, since that is the item
                // the rest of the entry describes.
                customModelData = byItem.containsKey(primary.baseItemId())
                        ? byItem.get(primary.baseItemId())
                        : byItem.values().iterator().next();
            }
            // With nothing in the map the wrap still belongs to this mechanism
            // and still says so; it simply has no value it may write, and
            // refuses per item rather than pointing item_model at the item's
            // own definition, which would quietly change nothing.
        } else if (!customName.isEmpty() && !compatible.isEmpty()) {
            byItem = new LinkedHashMap<>();
            // The pack asked to be summoned by name. Checked before item_model
            // because pointing item_model at the item's own definition would
            // land straight back on the select this came from and change
            // nothing.
            kind = WrapTarget.Kind.CUSTOM_NAME;
            customModelData = List.of();
        } else {
            customModelData = List.of();
            byItem = new LinkedHashMap<>();
            customName = "";
            kind = WrapTarget.Kind.ITEM_MODEL;
            itemModelId = definitionAsItemModelId(primary.definitionId());
            if (itemModelId.isEmpty()) {
                // Nothing writable: the model exists but there is no supported
                // way to ask Minecraft for it.
                return null;
            }
            // An item_model reaches the model whatever it is put on, so the
            // pack's association is a preference rather than a restriction.
            // Recorded either way; the UI decides how strictly to apply it.
        }

        return new WrapTarget(
                primary.canonicalId(),
                primary.resolvedName(),
                primary.type(),
                kind,
                itemModelId,
                customName,
                customModelData,
                java.util.Collections.unmodifiableMap(new LinkedHashMap<>(byItem)),
                List.copyOf(compatible),
                primary);
    }

    /**
     * Values a pack hands to two different models on the same item.
     *
     * <p>It happens: in the packs this was built against, a diamond pickaxe is
     * given both the driftwood helmet and the coral crown at 441. Only one can
     * be drawn, and which one is not a matter of opinion — the game sorts a
     * range dispatch by threshold with a stable sort and then takes the
     * <em>last</em> entry not above the value, so among equal thresholds the
     * one written later in the file wins. Writing that value for the other
     * model applies cleanly and draws the winner instead, which is the same
     * kind of wrong model as writing another item's number.</p>
     *
     * <p>Returned is the set of losers, so that a wrap can pass over a value it
     * would not win and use another the pack gives it on that item — the
     * driftwood helmet is also 443 there, and 443 is uncontested.</p>
     *
     * <p>Every state is examined rather than just the default: where two models
     * share one value on one item, the grouping has usually already made the
     * second a state of the first, so the contest is invisible from the
     * defaults alone.</p>
     */
    private static Set<String> shadowedValues(List<LogicalModel> models) {
        // item + value -> the model with the highest entry ordinal so far
        Map<String, String> winner = new LinkedHashMap<>();
        Map<String, Integer> winningOrdinal = new LinkedHashMap<>();
        Map<String, Set<String>> claimants = new LinkedHashMap<>();

        for (LogicalModel model : models) {
            if (!model.baseItemIsRegistered()) {
                continue;
            }
            for (dev.custommodels.cmv.explorer.graph.ModelState state : model.states()) {
                List<Selector> path = state.source().path();
                if (drawnAlongside(path)) {
                    // A composite draws its members together, so two members
                    // sharing a value are not competing for it.
                    continue;
                }
                List<WrapTarget.CustomModelDataEntry> entries = entriesOf(path);
                if (entries.isEmpty() || state.modelId().isEmpty()) {
                    continue;
                }
                String key = model.baseItemId() + '\u0000' + signatureOf(entries);
                claimants.computeIfAbsent(key, k -> new LinkedHashSet<>())
                        .add(state.modelId());
                int ordinal = lastOrdinal(path);
                Integer best = winningOrdinal.get(key);
                if (best == null || ordinal > best) {
                    winningOrdinal.put(key, ordinal);
                    winner.put(key, state.modelId());
                }
            }
        }

        Set<String> losers = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> entry : claimants.entrySet()) {
            if (entry.getValue().size() < 2) {
                continue;
            }
            String won = winner.get(entry.getKey());
            for (String modelId : entry.getValue()) {
                if (!modelId.equals(won)) {
                    losers.add(entry.getKey() + '\u0000' + modelId);
                }
            }
        }
        return losers;
    }

    /** True when this route ends inside a composite, whose members all draw. */
    private static boolean drawnAlongside(List<Selector> path) {
        for (Selector step : path) {
            if (step.kind() == Selector.Kind.COMPOSITE) {
                return true;
            }
        }
        return false;
    }

    /** The ordinal of the last custom model data step, deciding equal values. */
    private static int lastOrdinal(List<Selector> path) {
        int ordinal = -1;
        for (Selector step : path) {
            if (step.isCustomModelData() && step.branch() != Selector.Branch.FALLBACK) {
                ordinal = step.index();
            }
        }
        return ordinal;
    }

    private static String signatureOf(List<WrapTarget.CustomModelDataEntry> entries) {
        StringBuilder key = new StringBuilder(24);
        for (WrapTarget.CustomModelDataEntry entry : entries) {
            key.append(entry.numeric() ? 'f' : 's').append(entry.index()).append('=')
               .append(entry.numeric() ? String.valueOf(entry.number()) : entry.text())
               .append(';');
        }
        return key.toString();
    }

    private static String loserKey(String itemId,
                                   List<WrapTarget.CustomModelDataEntry> entries,
                                   String modelId) {
        return itemId + '\u0000' + signatureOf(entries) + '\u0000' + modelId;
    }

    /**
     * Reads the exact custom model data values that reach this model.
     *
     * <p>Taken from the route the scanner recorded, not from the display
     * strings, because the two differ in the way that matters: the route knows
     * whether each value was a {@code select} case (a string) or a
     * {@code range_dispatch} threshold (a number), and which index of the
     * component's list the property reads. Both are needed to write a component
     * the game will actually match.</p>
     */
    private static List<WrapTarget.CustomModelDataEntry> customModelDataEntries(
            LogicalModel model) {
        return entriesOf(model.defaultState().source().path());
    }

    /**
     * The furthest into the component's lists a wrap will write.
     *
     * <p>The index comes out of a resource pack, and writing at it means
     * filling every position below it first. Left unbounded, a pack asking for
     * index 2,000,000,000 — by mistake or otherwise — would have the mod build
     * a list of two billion entries the moment the player clicked it, and a
     * negative one would throw out of a click handler. Neither value can be
     * matched by any component an item could really carry, so a wrap is simply
     * not offered for it; the model is still listed and can still be looked
     * at.</p>
     */
    private static final int MAX_DATA_INDEX = 255;

    /** The same, for any one route. */
    private static List<WrapTarget.CustomModelDataEntry> entriesOf(List<Selector> path) {
        List<WrapTarget.CustomModelDataEntry> entries = new ArrayList<>(2);
        for (Selector step : path) {
            if (!step.isCustomModelData() || step.branch() == Selector.Branch.FALLBACK) {
                continue;
            }
            if (step.dataIndex() < 0 || step.dataIndex() > MAX_DATA_INDEX) {
                continue;
            }
            switch (step.kind()) {
                case RANGE_DISPATCH -> {
                    if (!Float.isNaN(step.threshold())) {
                        // The threshold is the lowest value that selects this
                        // entry, so writing it exactly lands on this model.
                        entries.add(new WrapTarget.CustomModelDataEntry(
                                true, step.dataIndex(), step.threshold(), ""));
                    }
                }
                case SELECT -> {
                    if (!step.values().isEmpty()) {
                        entries.add(new WrapTarget.CustomModelDataEntry(
                                false, step.dataIndex(), 0.0F, step.values().get(0)));
                    }
                }
                default -> {
                    // condition and composite carry no custom model data value.
                }
            }
        }
        return List.copyOf(entries);
    }

    /**
     * The exact name a {@code select} on {@code custom_name} switches on.
     *
     * <p>Empty unless the pack reaches this model that way. The first case
     * value is used: a case may list several names, and any one of them
     * reaches the model, so the first is as good as the rest and is the one
     * the pack author wrote first.</p>
     */
    private static String customNameFor(LogicalModel model) {
        for (Selector step : model.defaultState().source().path()) {
            if (step.branch() == Selector.Branch.FALLBACK
                    || step.isCustomModelData() || !step.isIdentity()) {
                continue;
            }
            if (!step.values().isEmpty()) {
                return step.values().get(0);
            }
        }
        return "";
    }

    /**
     * Turns {@code lf:items/count_paper.json} into {@code lf:count_paper}, which
     * is what {@code minecraft:item_model} expects.
     */
    public static String definitionAsItemModelId(String definitionId) {
        if (definitionId == null || definitionId.isEmpty()) {
            return "";
        }
        int colon = definitionId.indexOf(':');
        if (colon < 0) {
            return "";
        }
        String namespace = definitionId.substring(0, colon);
        String path = definitionId.substring(colon + 1);
        if (path.startsWith("items/")) {
            path = path.substring("items/".length());
        }
        if (path.endsWith(".json")) {
            path = path.substring(0, path.length() - ".json".length());
        }
        return path.isEmpty() ? "" : namespace + ":" + path;
    }

    // --------------------------------------------------------------- queries

    /** Wraps that can be applied to an item, best matches first. */
    public List<WrapTarget> compatibleWith(String itemId, boolean strict) {
        List<WrapTarget> out = new ArrayList<>();
        java.util.Set<WrapTarget> added =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (WrapTarget target : targets) {
            // Listed and applicable. A pack can name an item in a definition
            // without giving the model a value there, and offering a card that
            // refuses when clicked helps nobody.
            if (target.compatibleItems().contains(itemId)
                    && target.isCompatibleWith(itemId)
                    && (!strict || belongsOn(target, itemId))) {
                out.add(target);
                added.add(target);
            }
        }
        if (!strict) {
            // Universal wraps come after the ones the pack actually associates
            // with this item, so the certain answers are seen first.
            for (WrapTarget target : targets) {
                if (!added.contains(target) && target.isCompatibleWith(itemId)) {
                    out.add(target);
                }
            }
        }
        return out;
    }

    /**
     * Whether a model belongs on this item, as opposed to merely being reachable
     * from it.
     *
     * <p>Packs put a model in every item definition it might ever be wanted on.
     * The pack this was built against reaches its hats through the pickaxe
     * definitions as well as the helmet ones, so asking a diamond pickaxe what
     * it can wear returns 917 appearances of which 441 are hats, glasses,
     * antlers and crowns — the pickaxes buried among them.</p>
     *
     * <p>Two independent readings have to disagree before anything is hidden:
     * what the model calls itself, taken from its file name, and what the game
     * says the item is, taken from its tags. A model that names itself a crown,
     * offered on an item the game calls a pickaxe, is not offered there —
     * <em>provided</em> it is offered somewhere it does belong, so that a model
     * with nowhere better to go is never hidden altogether. A model whose name
     * says nothing is left alone: no evidence, no filtering.</p>
     *
     * <p>This narrows what is <em>offered</em>, not what is possible. Turning
     * the compatibility filter off lists them again, and applying one still
     * works — the pack does reach it that way, and a player who wants a crown
     * on a pickaxe is not wrong, only unusual.</p>
     */
    private boolean belongsOn(WrapTarget target, String itemId) {
        ItemType named = namedKind.get(target);
        if (named == null) {
            // What the wrap was classified as, when its file name says nothing.
            // The sidebar has always counted these — a pair of wings offered on
            // a helmet and a pickaxe is filed under hats there — and the filter
            // was ignoring the same evidence, which is why a pickaxe listed two
            // hundred and twenty-one of them alongside its pickaxes.
            named = target.type();
        }
        if (named == null || named == ItemType.MISC || named == ItemType.ALL) {
            // Nothing said about what it is, from either reading.
            return true;
        }
        ItemType actual = itemKind.apply(itemId);
        if (actual == null || actual == ItemType.MISC || actual == ItemType.ALL
                || actual == named) {
            return true;
        }
        for (String other : target.compatibleItems()) {
            if (!other.equals(itemId) && itemKind.apply(other) == named) {
                return false;
            }
        }
        return true;
    }

    /** Applies a text query across names, sets, ids and compatible items. */
    public List<WrapTarget> search(List<WrapTarget> from, String query) {
        if (query == null || query.isBlank()) {
            return from;
        }
        String[] terms = query.trim().toLowerCase(Locale.ROOT).split("\\s+");
        List<WrapTarget> out = new ArrayList<>();
        for (WrapTarget target : from) {
            String blob = blobs.getOrDefault(target.id(), "");
            boolean all = true;
            for (String term : terms) {
                if (!blob.contains(term)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                out.add(target);
            }
        }
        return out;
    }

    /**
     * The orders the Wrap Bank offers.
     *
     * <p>Set membership is inferred and, on a pack that files its models by
     * event and contributor rather than by collection, inferred badly. Sorting
     * by the item type instead is grounded in the base item's own registry
     * tags, so a sword is under Swords because Minecraft says it is a sword.</p>
     */
    public enum Order {
        NAME("Name A-Z"),
        NAME_DESC("Name Z-A"),
        NEWEST("Newest first"),
        OLDEST("Oldest first");

        private final String label;

        Order(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public Order next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /**
     * Applies an order.
     *
     * <p>"Newest" is the position the model occupies in its pack, reconstructed
     * from where its definition file sits and where the entry sits inside that
     * file. Pack authors append, so later means added later. It is a good proxy
     * and not a timestamp, which no resource pack records.</p>
     */
    public static List<WrapTarget> sort(List<WrapTarget> from, Order order) {
        List<WrapTarget> out = new ArrayList<>(from);
        Comparator<WrapTarget> byName =
                Comparator.comparing(t -> t.displayName().toLowerCase(Locale.ROOT));
        Comparator<WrapTarget> byAge = Comparator
                .comparingInt((WrapTarget t) -> t.model().packOrdinal())
                .thenComparingInt(t -> t.model().discoveryIndex());
        out.sort(switch (order) {
            case NAME -> byName;
            case NAME_DESC -> byName.reversed();
            case OLDEST -> byAge.thenComparing(byName);
            case NEWEST -> byAge.reversed().thenComparing(byName);
        });
        return out;
    }

    /** Wraps of one item type. */
    public List<WrapTarget> ofType(List<WrapTarget> from, ItemType type) {
        if (type == ItemType.ALL) {
            return from;
        }
        List<WrapTarget> out = new ArrayList<>();
        for (WrapTarget target : from) {
            if (target.type() == type) {
                out.add(target);
            }
        }
        return out;
    }

    /** How many wraps each type has, for the sidebar counts. */
    public Map<ItemType, Integer> countsByType(List<WrapTarget> from) {
        Map<ItemType, Integer> counts = new java.util.EnumMap<>(ItemType.class);
        counts.put(ItemType.ALL, from.size());
        for (WrapTarget target : from) {
            counts.merge(target.type(), 1, Integer::sum);
        }
        return counts;
    }


    /** The wrap that draws a given model, or null when nothing can apply it. */
    public WrapTarget byModel(String modelId) {
        return byModelId.get(modelId);
    }

    public WrapTarget byId(String id) {
        for (WrapTarget target : targets) {
            if (target.id().equals(id)) {
                return target;
            }
        }
        return null;
    }
}
