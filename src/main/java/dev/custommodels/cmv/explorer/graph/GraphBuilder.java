package dev.custommodels.cmv.explorer.graph;

import dev.custommodels.cmv.explorer.def.DiscoveredModel;
import dev.custommodels.cmv.explorer.def.Selector;
import dev.custommodels.cmv.explorer.res.ModelJson;
import dev.custommodels.cmv.explorer.res.ModelResolver;
import dev.custommodels.cmv.explorer.res.ResolvedModel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Turns raw discovery results into the logical items the browser shows.
 *
 * <h2>Grouping</h2>
 *
 * <p>The question "which of these models are the same item?" has an answer in
 * the resource pack, and it is not the file names. Minecraft picks between
 * models by walking one item definition and taking branches; every model at the
 * bottom of that walk is a state of whatever the branches above it selected.
 * So the grouping key is the route, not the name:</p>
 *
 * <pre>
 *   assets/example/items/bow.json
 *     select custom_model_data
 *       case "dragon_bow"            &lt;- everything below is the Dragon Bow
 *         condition using_item
 *           false  -&gt; dragon_bow            state: Default
 *           true   -&gt; range_dispatch use_duration
 *                       fallback -&gt; dragon_bow-1   state: Pull 0
 *                       0.65     -&gt; dragon_bow-2   state: Pull 1
 *                       0.9      -&gt; dragon_bow-3   state: Pull 2
 *       case "frost_bow"             &lt;- a different item entirely
 * </pre>
 *
 * <p>That gives one card per custom model data selection, with its states
 * attached, which is the behaviour the browser needs. A definition with no
 * custom model data at all is still one item: it is the pack's replacement for
 * the vanilla one, and its branches are still its states.</p>
 *
 * <p>File names are used only as a last resort, only within one definition and
 * one base item, and only for names that differ by a recognised state suffix.
 * Every grouping records which rule produced it, and the weakest rule is marked
 * as a guess rather than presented as fact.</p>
 *
 * <h2>Deduplication</h2>
 *
 * <p>Three kinds of duplicate are collapsed, in increasing order of nerve:
 * two references to the same model, two states that reach the same model, and
 * two items whose models resolve to identical geometry and identical textures.
 * In every case the discarded references are kept on the survivor, so
 * deduplication removes cards, never information.</p>
 */
public final class GraphBuilder {

    /**
     * Filename suffixes that mean "a state of the thing before me". Used only
     * by the low-confidence pass, and only when the structural rules have
     * already failed.
     */
    private static final List<String> STATE_SUFFIXES = List.of(
            "_pulling_", "_pull_", "_charged_", "_blocking", "_broken", "_cast",
            "_open", "_closed", "_active", "_powered", "_stage");

    /**
     * Suffixes that name a way of drawing one appearance, not a second one.
     *
     * <p>ItemsAdder ships each appearance as a base plus render variants —
     * {@code wings_v2_1} alongside {@code _cosmetic}, {@code _cosmetic_self},
     * {@code _cosmetic_self_2} and {@code _cosmetic_normal_2} — which differ
     * only in whose view they are drawn for. Five cards for one pair of wings,
     * and choosing between them is choosing which camera you are.</p>
     *
     * <p>Kept apart from the state suffixes because the claim is stronger. A
     * numeric suffix means "related, probably a stage of"; this means "the same
     * appearance, drawn for someone else", which is why {@link
     * #mergeRenderVariants} may merge across item definitions where the state
     * pass deliberately will not.</p>
     */
    private static final List<String> VARIANT_SUFFIXES =
            List.of("_cosmetic", "_self", "_normal");

    /**
     * Largest render-variant family.
     *
     * <p>Bigger than the state cap because the suffixes stack: one appearance
     * can ship a base, {@code _self}, {@code _self_2}, {@code _normal},
     * {@code _normal_2} and then the same four again behind {@code _cosmetic}.
     * A state sequence has no reason to be that long; a variant family does.</p>
     */
    private static final int MAX_VARIANT_GROUP = 12;

    /** Largest group the filename pass will form. */
    private static final int MAX_HEURISTIC_GROUP = 8;

    private final ModelResolver resolver;
    private final Function<String, ItemType.Classification> classifier;
    private final ToIntFunction<String> packOrdinals;
    private final Predicate<String> isUserPack;
    private final Predicate<String> itemExists;
    private final boolean allowFilenameHeuristic;
    private boolean includeBroken = true;

    /** Counters the scan report shows the user. */
    private int exactDuplicates;
    private int stateDuplicates;
    private int resolvedDuplicates;
    private int heuristicGroups;
    private int groupedStates;
    private int skippedVanilla;
    private int skippedBroken;

    /**
     * Models a definition reaches whose geometry is not there, by model id.
     *
     * <p>Noted whether or not they are then dropped. What the grid shows is a
     * preference; what the packs are missing is a fact, and the page that
     * reports it should not change its answer because of a switch about
     * display. Split by which fault it is because they want different
     * answers: a missing file is usually a pack that was not shipped whole,
     * and a model that resolves to nothing is usually a template meant to be
     * inherited from rather than drawn.</p>
     */
    private final List<String> missingModels = new ArrayList<>();
    private final List<String> emptyModels = new ArrayList<>();

    /**
     * @param resolver              supplies resolved models; its caches are what
     *                              keep this cheap for large packs
     * @param classifier            maps a base item id to a type; supplied by the
     *                              game layer so this class stays testable
     * @param packOrdinals          maps a pack id to its load order position
     * @param itemExists            true when an id names a registered item; a
     *                              definition whose id does not is an
     *                              {@code item_model} target rather than an item
     * @param isUserPack            true for a pack the player installed, false
     *                              for {@code vanilla} and other built-in
     *                              sources; decides what counts as custom
     * @param allowFilenameHeuristic whether the low-confidence naming pass runs
     */
    public GraphBuilder(ModelResolver resolver,
                        Function<String, ItemType.Classification> classifier,
                        ToIntFunction<String> packOrdinals,
                        Predicate<String> isUserPack,
                        Predicate<String> itemExists,
                        boolean allowFilenameHeuristic) {
        this.resolver = resolver;
        this.classifier = classifier;
        this.packOrdinals = packOrdinals;
        this.isUserPack = isUserPack;
        this.itemExists = itemExists;
        this.allowFilenameHeuristic = allowFilenameHeuristic;
    }

    /** Whether models whose files are missing or broken are still listed. */
    public GraphBuilder includeBroken(boolean include) {
        this.includeBroken = include;
        return this;
    }

    public int skippedVanilla() {
        return skippedVanilla;
    }

    /** How many states were dropped for being broken, when they are dropped. */
    public int skippedBroken() {
        return skippedBroken;
    }

    /** Model ids a definition asked for that no enabled pack ships. */
    public List<String> missingModels() {
        return List.copyOf(missingModels);
    }

    /** Model ids that resolve to a file with no geometry in it. */
    public List<String> emptyModels() {
        return List.copyOf(emptyModels);
    }


    public int exactDuplicatesRemoved() {
        return exactDuplicates;
    }

    public int stateDuplicatesRemoved() {
        return stateDuplicates;
    }

    public int resolvedDuplicatesRemoved() {
        return resolvedDuplicates;
    }

    public int heuristicGroupsFormed() {
        return heuristicGroups;
    }

    public int groupedStates() {
        return groupedStates;
    }

    /**
     * Builds the logical items.
     *
     * @param discovered every leaf found across every item definition
     */
    public List<LogicalModel> build(List<DiscoveredModel> discovered) {
        Map<String, List<DiscoveredModel>> byRoute = new LinkedHashMap<>();
        for (DiscoveredModel model : discovered) {
            if (model.leafKind() == DiscoveredModel.LeafKind.EMPTY) {
                // "empty" draws nothing on purpose; it is not a missing model.
                continue;
            }
            if (!isCustom(model)) {
                skippedVanilla++;
                continue;
            }
            byRoute.computeIfAbsent(routeKey(model), key -> new ArrayList<>()).add(model);
        }

        List<LogicalModel> models = new ArrayList<>(byRoute.size());
        for (Map.Entry<String, List<DiscoveredModel>> entry : byRoute.entrySet()) {
            LogicalModel built = buildOne(entry.getKey(), entry.getValue());
            if (built != null) {
                models.add(built);
            }
        }

        models = mergeIdenticalModels(models);
        if (allowFilenameHeuristic) {
            models = mergeByFilename(models);
            models = mergeRenderVariants(models);
        }
        reclassify(models);

        for (LogicalModel model : models) {
            if (model.hasMultipleStates()) {
                groupedStates += model.states().size();
            }
        }

        models.sort(Comparator
                .comparingInt(LogicalModel::packOrdinal)
                .thenComparingInt(LogicalModel::discoveryIndex)
                .thenComparing(LogicalModel::displayName));
        return models;
    }

    /**
     * Settles each model's category once every route to it is known.
     *
     * <h2>Why this cannot be decided one route at a time</h2>
     *
     * <p>A model is offered on whatever items the pack's definitions reference
     * it from, and each of those is a separate route. Judged alone, a route
     * only knows the one item it came through; judged together, they say
     * something much more useful. In the pack examined here 2,110 models are
     * offered on more than one item and 758 of those disagree about what kind
     * of item it is.</p>
     *
     * <p>So the items decide when they agree, and the name decides when they do
     * not:</p>
     *
     * <ul>
     *   <li><b>All the items agree.</b> That is the answer. A model offered
     *       only on helmets is headwear whatever it is shaped like, because the
     *       head is the only place it can go.</li>
     *   <li><b>They disagree.</b> The item cannot settle it, so the name does.
     *       {@code fairyset:pickaxe} is offered on helmets and on pickaxes, and
     *       is a pickaxe; {@code crown_of_undeath} is offered on helmets,
     *       pickaxes and a gold nugget, and is a crown.</li>
     *   <li><b>No item says anything</b> — the carriers are paper and sticks —
     *       so the name is all there is.</li>
     * </ul>
     */
    private void reclassify(List<LogicalModel> models) {
        Map<String, List<LogicalModel>> byModel = new LinkedHashMap<>();
        for (LogicalModel model : models) {
            byModel.computeIfAbsent(model.defaultState().modelId(),
                    key -> new ArrayList<>()).add(model);
        }

        for (Map.Entry<String, List<LogicalModel>> entry : byModel.entrySet()) {
            String modelId = entry.getKey();
            List<LogicalModel> group = entry.getValue();

            // Every category the items carrying this model imply, and how many
            // of them vote for each.
            Map<ItemType, Integer> votes = new LinkedHashMap<>();
            String evidence = "";
            for (LogicalModel member : group) {
                String itemId = member.baseItemId();
                if (!member.baseItemIsRegistered() || ItemType.isGenericCarrier(itemId)) {
                    continue;
                }
                ItemType.Classification fromItem = classifier.apply(itemId);
                if (fromItem != null && fromItem.type() != ItemType.MISC) {
                    if (votes.merge(fromItem.type(), 1, Integer::sum) == 1) {
                        evidence = evidence.isEmpty()
                                ? fromItem.evidence() : evidence + ", " + itemId;
                    }
                }
            }
            Set<ItemType> candidates = votes.keySet();

            ItemType.Classification chosen;
            if (candidates.size() == 1) {
                chosen = new ItemType.Classification(candidates.iterator().next(),
                        ItemType.Source.ITEM_TAG, evidence);
            } else {
                ItemType fromName = fromModelPath(modelId);
                if (fromName != null) {
                    chosen = new ItemType.Classification(fromName,
                            ItemType.Source.MODEL_PATH, candidates.isEmpty()
                            ? modelId
                            : modelId + "  (items disagree: " + candidates + ")");
                } else if (namesHeldTool(modelId) && anyHeldCandidate(candidates)) {
                    // The name says this is swung at something, and the items
                    // include something you swing. The name settles held versus
                    // worn; the items settle which held thing it is, which is
                    // the half they actually know.
                    chosen = new ItemType.Classification(mostSupportedHeld(votes),
                            ItemType.Source.MODEL_PATH,
                            modelId + "  (held, items say: " + candidates + ")");
                } else if (!candidates.isEmpty()) {
                    // Several kinds and nothing in the name to choose between
                    // them. Taking whichever came first put 342 name-silent
                    // cosmetics into Pickaxes purely because that route was
                    // read first, so the routes are counted and an explicit
                    // precedence settles an exact tie. A cosmetic offered on
                    // both a helmet and a pickaxe is filed as headwear, because
                    // being wearable is the more particular of the two, and on
                    // this pack the name-silent remainder really is hats.
                    chosen = new ItemType.Classification(mostSupported(votes),
                            ItemType.Source.ITEM_TAG, "items disagree: " + candidates);
                } else {
                    chosen = new ItemType.Classification(ItemType.MISC,
                            ItemType.Source.NONE, "");
                }
            }
            for (LogicalModel member : group) {
                member.applyClassification(chosen);
            }
        }
    }

    /**
     * Which category is best supported, with an explicit tiebreak.
     *
     * <p>The order below is "how particular is this claim". Being wearable on
     * the head narrows a thing down more than being holdable, and a pickaxe is
     * the item this pack hands out as a general-purpose carrier, so it loses
     * every tie it is in.</p>
     */
    private static final List<ItemType> TIEBREAK = List.of(
            ItemType.HELMET, ItemType.ARMOUR, ItemType.SHIELD, ItemType.BOW,
            ItemType.CROSSBOW, ItemType.SWORD, ItemType.AXE, ItemType.SHOVEL,
            ItemType.HOE, ItemType.FISHING_ROD, ItemType.PICKAXE, ItemType.TOOL,
            ItemType.FOOD,
            ItemType.BLOCK, ItemType.DECORATION, ItemType.DYE, ItemType.FEATHER,
            ItemType.ITEM);

    /** Categories that describe something held in the hand. */
    private static final Set<ItemType> HELD = Set.of(
            ItemType.PICKAXE, ItemType.AXE, ItemType.SHOVEL, ItemType.HOE,
            ItemType.SWORD, ItemType.BOW, ItemType.CROSSBOW, ItemType.FISHING_ROD,
            ItemType.TOOL);

    private static boolean anyHeldCandidate(Set<ItemType> candidates) {
        for (ItemType type : candidates) {
            if (HELD.contains(type)) {
                return true;
            }
        }
        return false;
    }

    /** The best-supported category among the ones you hold, ignoring the rest. */
    private static ItemType mostSupportedHeld(Map<ItemType, Integer> votes) {
        Map<ItemType, Integer> held = new LinkedHashMap<>();
        for (Map.Entry<ItemType, Integer> vote : votes.entrySet()) {
            if (HELD.contains(vote.getKey())) {
                held.put(vote.getKey(), vote.getValue());
            }
        }
        return held.isEmpty() ? mostSupported(votes) : mostSupported(held);
    }

    private static ItemType mostSupported(Map<ItemType, Integer> votes) {
        ItemType best = null;
        int bestVotes = -1;
        int bestRank = Integer.MAX_VALUE;
        for (Map.Entry<ItemType, Integer> vote : votes.entrySet()) {
            int rank = TIEBREAK.indexOf(vote.getKey());
            if (rank < 0) {
                rank = TIEBREAK.size();
            }
            if (vote.getValue() > bestVotes
                    || (vote.getValue() == bestVotes && rank < bestRank)) {
                best = vote.getKey();
                bestVotes = vote.getValue();
                bestRank = rank;
            }
        }
        return best == null ? ItemType.MISC : best;
    }

    // ------------------------------------------------------- route grouping

    /**
     * The identity of a logical item: which definition file, and which custom
     * model data selections were taken inside it. Everything below the last
     * such selection is a state of this item rather than a separate one.
     */
    private static String routeKey(DiscoveredModel model) {
        StringBuilder key = new StringBuilder(model.definitionId());
        for (Selector step : model.identitySteps()) {
            key.append('|').append(step.token());
        }
        return key.toString();
    }

    /**
     * Whether a discovered model is something the pack contributes.
     *
     * <p>Two things qualify. One is being reached through a custom model data
     * selection, which is the pack adding an appearance that vanilla has no way
     * to show. The other is the model file itself coming from a user pack,
     * which is the pack replacing an appearance vanilla already had. The second
     * is what makes a retextured diamond sword show up even though nothing in
     * its definition mentions custom model data.</p>
     *
     * <p>What does not qualify is a branch of a pack-supplied definition that
     * still lands on a vanilla model. Every definition file has such branches —
     * they are the untouched parts — and listing them would bury the pack's own
     * work under a thousand vanilla entries.</p>
     *
     * <p>The reachability idea, and the flag that carries it down the
     * recursion, come from Custom Model Data Viewer (Apache-2.0). The second
     * test is this viewer's own addition; CMDV lists only the first kind.</p>
     */
    private boolean isCustom(DiscoveredModel model) {
        if (model.viaCustomModelData()) {
            return true;
        }
        ResolvedModel resolved = resolver.resolve(model.modelId());
        return resolved.exists() && isUserPack.test(resolved.sourcePackId());
    }

    private LogicalModel buildOne(String routeKey, List<DiscoveredModel> leaves) {
        // Two references to the same model under the same conditions are the
        // same thing said twice.
        Map<String, DiscoveredModel> uniqueLeaves = new LinkedHashMap<>();
        List<String> merged = new ArrayList<>();
        for (DiscoveredModel leaf : leaves) {
            String key = leaf.modelId() + "#" + stateKey(leaf);
            DiscoveredModel existing = uniqueLeaves.putIfAbsent(key, leaf);
            if (existing != null) {
                exactDuplicates++;
                merged.add(leaf.definitionId() + " -> " + leaf.modelId());
            }
        }

        // Two different conditions that reach the same model look identical, so
        // they become one state carrying both conditions.
        Map<String, DiscoveredModel> byModel = new LinkedHashMap<>();
        for (DiscoveredModel leaf : uniqueLeaves.values()) {
            DiscoveredModel existing = byModel.get(leaf.modelId());
            if (existing == null) {
                byModel.put(leaf.modelId(), leaf);
                continue;
            }
            stateDuplicates++;
            merged.add(leaf.modelId() + " also reached by " + describe(leaf.stateSteps()));
            // Keep whichever route is closest to the item's resting state, so
            // the surviving label is the more useful one.
            if (leaf.activationCost() < existing.activationCost()) {
                byModel.put(leaf.modelId(), leaf);
            }
        }
        if (byModel.isEmpty()) {
            return null;
        }

        List<DiscoveredModel> ordered = new ArrayList<>(byModel.values());
        ordered.sort(Comparator
                .comparingInt(DiscoveredModel::activationCost)
                .thenComparingInt(DiscoveredModel::discoveryIndex));

        List<ModelState> states = buildStates(ordered);
        for (ModelState state : states) {
            if (!state.resolved().exists()) {
                missingModels.add(state.modelId());
            } else if (state.resolved().isEmpty()) {
                emptyModels.add(state.modelId());
            }
        }
        if (!includeBroken) {
            List<ModelState> usable = new ArrayList<>(states.size());
            for (ModelState state : states) {
                if (state.resolved().exists() && !state.resolved().isEmpty()) {
                    usable.add(state);
                } else {
                    skippedBroken++;
                }
            }
            if (usable.isEmpty()) {
                return null;
            }
            states = usable;
        }
        int defaultIndex = pickDefaultState(states);

        DiscoveredModel representative = states.get(defaultIndex).source();
        ResolvedModel preview = states.get(defaultIndex).resolved();

        List<String> customModelData = new ArrayList<>();
        String nameSource = "";
        for (Selector step : representative.identitySteps()) {
            if (step.branch() == Selector.Branch.FALLBACK) {
                continue;
            }
            customModelData.addAll(step.values());
            if (!Float.isNaN(step.threshold())) {
                customModelData.add(String.valueOf(step.threshold()));
            }
            // A case may list several values that all reach this model. The
            // first is the one to name it after; the rest are aliases.
            if (!step.values().isEmpty()) {
                nameSource = step.values().get(0);
            }
        }

        GroupReason reason;
        if (states.size() == 1) {
            reason = GroupReason.SINGLE;
        } else if (!customModelData.isEmpty()) {
            reason = GroupReason.SAME_CUSTOM_MODEL_DATA;
        } else {
            reason = GroupReason.SAME_ITEM_DISPATCH;
        }

        String namespace = namespaceOf(representative.modelId(), representative.definitionId());
        String name = displayName(nameSource, representative);
        ItemType.Classification classification = classify(representative, preview);

        return new LogicalModel(
                canonicalId(routeKey, states),
                name,
                representative.baseItemId(),
                itemExists.test(representative.baseItemId()),
                classification,
                namespace,
                representative.sourcePackId(),
                representative.definitionId(),
                representative.definitionArchivePath(),
                customModelData,
                states,
                defaultIndex,
                reason,
                geometryHash(preview),
                textureHash(preview),
                merged,
                packOrdinals.applyAsInt(representative.sourcePackId()),
                representative.discoveryIndex());
    }

    private List<ModelState> buildStates(List<DiscoveredModel> ordered) {
        // Number the draw stages by threshold so that "Pull 1" really is the
        // first stage, whatever order the pack wrote its entries in.
        List<DiscoveredModel> drawStages = new ArrayList<>();
        for (DiscoveredModel leaf : ordered) {
            for (Selector step : leaf.stateSteps()) {
                if (step.kind() == Selector.Kind.RANGE_DISPATCH
                        && ModelState.isDrawProperty(step.shortProperty())) {
                    drawStages.add(leaf);
                    break;
                }
            }
        }
        // The fallback branch is the first stage of the draw, since it is what
        // shows before any threshold is met; sorting NaN-free puts it first.
        drawStages.sort(Comparator.comparingDouble(GraphBuilder::drawThreshold));
        Map<DiscoveredModel, Integer> pullOrdinals = new HashMap<>();
        for (int i = 0; i < drawStages.size(); i++) {
            pullOrdinals.put(drawStages.get(i), i + 1);
        }

        List<ModelState> states = new ArrayList<>(ordered.size());
        Set<String> usedLabels = new LinkedHashSet<>();
        for (DiscoveredModel leaf : ordered) {
            String label = ModelState.label(leaf.stateSteps(),
                    pullOrdinals.getOrDefault(leaf, -1));
            // A label is a shortened rendering whenever there were conditions to
            // shorten. Previously this was decided by formatting the full
            // condition text and comparing it, which meant building that string
            // for every state of every model purely to set a boolean.
            boolean derived = !leaf.stateSteps().isEmpty();
            // Labels have to be distinct for the state selector to make sense.
            String unique = label;
            int suffix = 2;
            while (!usedLabels.add(unique)) {
                unique = label + " " + suffix++;
            }
            states.add(new ModelState(unique, derived, leaf, resolver.resolve(leaf.modelId())));
        }
        return states;
    }

    private static float drawThreshold(DiscoveredModel leaf) {
        for (Selector step : leaf.stateSteps()) {
            if (step.kind() == Selector.Kind.RANGE_DISPATCH
                    && ModelState.isDrawProperty(step.shortProperty())) {
                return step.branch() == Selector.Branch.FALLBACK
                        ? Float.NEGATIVE_INFINITY : step.threshold();
            }
        }
        return Float.MAX_VALUE;
    }

    /**
     * Which state the card shows.
     *
     * <p>A state selected explicitly for the {@code gui} display context wins,
     * because that is literally the appearance Minecraft uses in an inventory
     * and therefore the one a viewer should lead with. Vanilla's trident is the
     * case that makes this necessary: its resting state is the in-hand model,
     * but the inventory shows a different one. Failing that, the state reached
     * through the fewest active conditions is the item at rest.</p>
     */
    private static int pickDefaultState(List<ModelState> states) {
        for (int i = 0; i < states.size(); i++) {
            for (Selector step : states.get(i).steps()) {
                if (step.kind() == Selector.Kind.SELECT
                        && "display_context".equals(step.shortProperty())
                        && step.branch() == Selector.Branch.MATCH
                        && step.values().contains("gui")) {
                    return i;
                }
            }
        }
        int best = 0;
        int bestCost = Integer.MAX_VALUE;
        for (int i = 0; i < states.size(); i++) {
            ModelState state = states.get(i);
            int cost = state.source().activationCost();
            // A state whose model is missing makes a poor card.
            if (!state.resolved().exists() || state.resolved().isEmpty()) {
                cost += 100;
            }
            if (cost < bestCost) {
                bestCost = cost;
                best = i;
            }
        }
        return best;
    }

    // -------------------------------------------------------------- naming

    /**
     * A readable name, preferring what the pack author actually chose.
     *
     * <p>The custom model data string is the author's own label for the item
     * and is used when present. Otherwise the model's file name is, since that
     * is also their choice. The base item is the last resort, and only tells us
     * anything when the pack is replacing a vanilla appearance outright.</p>
     */
    private static String displayName(String nameSource, DiscoveredModel representative) {
        // A select case's value is the author's own label and is the best name
        // there is. A range dispatch's is a threshold — a number — and naming a
        // card "7" helps nobody, so a numeric source is skipped in favour of the
        // model path.
        if (!nameSource.isEmpty() && !isNumeric(nameSource)) {
            String candidate = prettify(nameSource);
            if (!candidate.isEmpty()) {
                return candidate;
            }
        }
        String fileName = fileNameOf(representative.modelId());
        if (!fileName.isEmpty()) {
            // Packs that lay models out as folder/1, folder/2 leave a file name
            // that identifies nothing on its own. The folder is what carries the
            // meaning, so it comes along: "lr_healthbar/12" reads as
            // "Lr Healthbar 12" rather than as "12".
            if (isNumeric(fileName)) {
                String folder = lastFolderOf(representative.modelId());
                if (!folder.isEmpty()) {
                    return prettify(folder) + " " + fileName;
                }
            }
            return qualify(prettify(fileName), representative.modelId());
        }
        if (!nameSource.isEmpty()) {
            return prettify(nameSource);
        }
        String item = representative.baseItemId();
        int colon = item.indexOf(':');
        return prettify(colon >= 0 ? item.substring(colon + 1) : item);
    }

    /**
     * Puts the namespace in front of a name that is only an item kind.
     *
     * <p>A pack that files each set in its own namespace names the files inside
     * it for what they are: {@code malachite:pickaxe}, {@code april:pickaxe},
     * {@code clown:pickaxe}. Every one of those is a different appearance and
     * every one of them was called "Pickaxe", which reads as twenty-one copies
     * of one thing when it is twenty-one different things. The namespace is the
     * part that distinguishes them, and it is the set's own name.</p>
     *
     * <p>Only for names that are a single word, and only where the file sits
     * at the root of its namespace. A file named for itself —
     * {@code 2024nov_bow}, {@code fut_glasses} — already says which one it is,
     * and a pack that files models under {@code blockbench/} or {@code item/}
     * has somewhere else for the meaning to live. Vanilla's namespace is left
     * off too: "Minecraft Bow" is not an improvement on "Bow".</p>
     */
    private static String qualify(String name, String modelId) {
        if (name.isEmpty() || name.indexOf(' ') >= 0) {
            return name;
        }
        int colon = modelId == null ? -1 : modelId.indexOf(':');
        if (colon <= 0) {
            return name;
        }
        // The nearest thing that names the set. A folder when there is one
        // that means anything — "pirate_weapon_set_v2/key" is a pirate key —
        // and the namespace when the file sits at the root, which is the other
        // layout these packs use. A container folder names nothing and is
        // stepped over.
        String qualifier = "";
        String path = modelId.substring(colon + 1);
        int cut = path.lastIndexOf('/');
        while (cut > 0 && qualifier.isEmpty()) {
            String folder = path.substring(path.lastIndexOf('/', cut - 1) + 1, cut);
            if (!isContainerFolder(folder)) {
                qualifier = folder;
            }
            cut = path.lastIndexOf('/', cut - 1);
        }
        if (qualifier.isEmpty() && path.indexOf('/') < 0) {
            qualifier = modelId.substring(0, colon);
        }
        if (qualifier.isEmpty() || qualifier.equals("minecraft")) {
            return name;
        }
        String prefix = prettify(qualifier);
        if (prefix.isEmpty() || prefix.equalsIgnoreCase(name)
                || name.toLowerCase(Locale.ROOT)
                        .startsWith(prefix.toLowerCase(Locale.ROOT))) {
            return name;
        }
        return prefix + " " + name;
    }

    /**
     * A folder that says where a file is kept rather than what it is.
     *
     * <p>{@code blockbench} is here because it names the tool the models were
     * exported from, which every model in the pack shares and none is
     * distinguished by; {@code armor} because a pack that files a hat under it
     * has already said "hat".</p>
     */
    private static boolean isContainerFolder(String folder) {
        return switch (folder.toLowerCase(Locale.ROOT)) {
            case "", "item", "items", "model", "models", "block", "blocks",
                 "blockbench", "custom", "armor", "armour", "gui", "misc" -> true;
            default -> false;
        };
    }

    /** True for a string that is nothing but digits, possibly with a decimal point. */
    public static boolean isNumeric(String value) {
        if (value.isEmpty()) {
            return false;
        }
        boolean digit = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isDigit(c)) {
                digit = true;
            } else if (c != '.' && c != '-' && c != '+') {
                return false;
            }
        }
        return digit;
    }

    /** The final folder of a model path, e.g. {@code lr_healthbar} for {@code ns:x/lr_healthbar/1}. */
    public static String lastFolderOf(String modelId) {
        String directory = directoryOf(modelId);
        int slash = directory.lastIndexOf('/');
        String segment = slash >= 0 ? directory.substring(slash + 1) : directory;
        int colon = segment.indexOf(':');
        if (colon >= 0) {
            segment = segment.substring(colon + 1);
        }
        // A generic container folder is no more informative than the number was.
        return switch (segment.toLowerCase(Locale.ROOT)) {
            case "", "item", "items", "model", "models", "block", "blocks" -> "";
            default -> segment;
        };
    }

    /**
     * Decides which category a model belongs in.
     *
     * <h2>Why the model's own name comes first</h2>
     *
     * <p>This used to trust the base item above everything, on the reasoning
     * that a model attached to {@code minecraft:diamond_sword} is a sword
     * whatever the pack calls it. That reasoning fails on how packs are
     * actually built. A server pack examined here hangs <b>761 models</b> off
     * {@code minecraft:netherite_helmet}: 148 with "hat" in the name, 44 with
     * "pickaxe", 38 "mask", 30 "crown", 29 "beanie", and one each of "sword"
     * and "axe". The head-armour tag on the carrier is a fact about the carrier
     * and says nothing about the four hundred cosmetics stapled to it, so
     * trusting it filed every pickaxe skin under Helmets.</p>
     *
     * <p>The model path is what the author named the thing they drew, and in a
     * browser of appearances that is the useful answer. So a confident reading
     * of the path wins, and the base item is the fallback for models whose
     * names carry no category at all — which is where it is genuinely the
     * better signal.</p>
     *
     * <p>Compatibility is unaffected and still comes from the base item. What
     * an appearance can be applied to and what it looks like are different
     * questions with different right answers.</p>
     */
    private ItemType.Classification classify(DiscoveredModel representative,
                                             ResolvedModel preview) {
        ItemType fromPath = fromModelPath(representative.modelId());
        if (fromPath != null) {
            return new ItemType.Classification(fromPath, ItemType.Source.MODEL_PATH,
                    representative.modelId());
        }
        ItemType.Classification fromItem = classifier.apply(representative.baseItemId());
        if (fromItem != null && fromItem.type() != ItemType.MISC) {
            return fromItem;
        }
        return fromItem != null ? fromItem
                : new ItemType.Classification(ItemType.MISC, ItemType.Source.NONE, "");
    }

    /**
     * Reads a type out of a model path. Tokens are matched whole, scanning from
     * the file name backwards, so {@code item/swords/decorative_axe} is an axe
     * and {@code axolotl} is not.
     */
    /**
     * Words that mean "this is held", used when the item cannot say.
     *
     * <p>The pack this was tuned against does not name its pickaxes "pickaxe".
     * It names them {@code vault_breaker}, {@code permafrost_crusher},
     * {@code egypt_sunpiercer}, {@code grave_diggers_delight},
     * {@code appleburrower}, {@code ion_cutter}. Every one of those is offered
     * on a helmet and a pickaxe alike, so the item list cannot tell them from
     * the hats sitting beside them; the verb in the name is the only thing that
     * can. These say nothing about <em>which</em> tool — that is left to the
     * items, which do know.</p>
     */
    private static final Set<String> HELD_TOOL_WORDS = Set.of(
            "ripper", "rippers", "breaker", "breakers", "crusher", "crushers",
            "piercer", "piercers", "sunpiercer", "burrower", "burrowers",
            "digger", "diggers", "buster", "busters", "cutter", "cutters",
            "drill", "drills", "driller", "smasher", "splitter", "miner",
            "excavator", "chipper", "gouger", "wrecker", "pulverizer",
            "pulveriser", "shatterer", "cleaver", "chisel", "pickaxes",
            "hammer", "mace", "maul", "sledge");

    /**
     * Words matched inside a longer token as well as on their own.
     *
     * <p>{@code strawhat}, {@code cowboyhat}, {@code tribalhelmet1} and
     * {@code industrialhelmet2} are all headwear whose name says so, and all of
     * them were invisible to whole-token matching. Kept to a curated list
     * rather than applied to every word because the traps are real: "rainbow"
     * ends in "bow" and "phoenix" contains "hoe", and either would file a hat
     * as archery equipment.</p>
     */
    private static final List<String> COMPOUND_WORDS = List.of(
            "pickaxe", "helmet", "helm", "crown", "circlet", "headpiece",
            "headband", "bandana", "beanie", "goggles", "glasses", "visor",
            "antlers", "antler", "moustache", "mustache", "stache", "sombrero",
            "turban", "hood", "mask", "hat", "shovel", "spade", "scythe",
            "sickle", "sword", "blade", "axe");

    /** True when the name says the thing is held rather than worn. */
    public static boolean namesHeldTool(String modelId) {
        for (String token : tokenize(modelId)) {
            if (HELD_TOOL_WORDS.contains(token)) {
                return true;
            }
            for (String word : HELD_TOOL_WORDS) {
                if (word.length() >= 5 && token.endsWith(word)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static ItemType fromModelPath(String modelId) {
        List<String> tokens = tokenize(modelId);
        for (int i = tokens.size() - 1; i >= 0; i--) {
            String token = tokens.get(i);
            ItemType type = switch (token) {
                case "sword", "swords", "blade", "blades", "katana", "sabre", "saber",
                     "rapier", "cutlass", "scimitar", "greatsword", "longsword",
                     "dagger", "spear", "lance" -> ItemType.SWORD;
                case "pickaxe", "pickaxes", "pick", "picks", "mattock" -> ItemType.PICKAXE;
                case "axe", "axes", "battleaxe", "hatchet", "greataxe", "waraxe" ->
                        i > 0 && "pick".equals(tokens.get(i - 1)) ? ItemType.PICKAXE : ItemType.AXE;
                case "shovel", "shovels", "spade", "spades" -> ItemType.SHOVEL;
                case "hoe", "hoes", "scythe", "scythes", "sickle" -> ItemType.HOE;
                case "bow", "bows", "longbow", "shortbow" -> ItemType.BOW;
                case "crossbow", "crossbows" -> ItemType.CROSSBOW;
                case "shield", "shields", "buckler" -> ItemType.SHIELD;
                // "rod" alone stays a tool — packs use it for wands and staves
                // far more than for fishing tackle — so the fishing words have
                // to be explicit.
                case "fishingrod", "fishing_rod", "fishingpole", "fishing",
                     "rod_fishing", "angling", "angler", "tacklerod" ->
                        ItemType.FISHING_ROD;
                // Hats and helmets share a category because packs mix them
                // freely and a player looking for headwear does not care which
                // of the two a given model technically is.
                case "helmet", "helmets", "helm", "helms", "hat", "hats", "cap", "caps",
                     "crown", "crowns", "mask", "masks", "hood", "hoods", "head",
                     "beanie", "antlers", "glasses", "goggles", "tiara", "halo",
                     "horns", "ears", "wig", "bandana", "fedora", "tophat",
                     "headband", "headdress", "sombrero", "visor", "monocle",
                     "eyepatch", "earmuffs", "bonnet", "turban", "veil",
                     // Names this pack uses that no generic list would have.
                     "circlet", "headpiece", "antler", "moustache", "mustache",
                     "stache", "earmuff", "hairband", "hairpiece", "topper",
                     "headwear", "headgear" ->
                        ItemType.HELMET;
                case "armor", "armour", "chestplate", "chestplates", "leggings",
                     "legging", "boots", "boot", "greaves", "elytra", "cape",
                     "cloak", "tunic" -> ItemType.ARMOUR;
                case "food", "foods", "bread", "meat", "fruit", "drink", "potion" ->
                        ItemType.FOOD;
                case "dye", "dyes" -> ItemType.DYE;
                case "feather", "feathers", "quill", "quills" -> ItemType.FEATHER;
                case "block", "blocks" -> ItemType.BLOCK;
                case "decor", "decoration", "decorations", "deco", "furniture", "prop",
                     "props", "statue", "statues", "banner", "lamp", "lamps", "chair",
                     "chairs", "table", "tables", "rug", "rugs", "painting",
                     "paintings", "ornament", "ornaments" -> ItemType.DECORATION;
                case "rod", "rods", "pole", "poles" ->
                        i > 0 && ("fishing".equals(tokens.get(i - 1))
                                || "fish".equals(tokens.get(i - 1))
                                || "angler".equals(tokens.get(i - 1)))
                                ? ItemType.FISHING_ROD : ItemType.TOOL;
                case "tool", "tools", "wand", "wands", "staff", "staves" ->
                        ItemType.TOOL;
                default -> null;
            };
            if (type != null) {
                return type;
            }
        }
        // Nothing matched a whole token. Try again allowing a word to sit at
        // the end of a longer one, which is how this pack writes "strawhat"
        // and "tribalhelmet1".
        for (int i = tokens.size() - 1; i >= 0; i--) {
            String token = tokens.get(i);
            for (String word : COMPOUND_WORDS) {
                if (token.length() > word.length() && token.endsWith(word)) {
                    ItemType type = fromModelPath("x_" + word);
                    if (type != null) {
                        return type;
                    }
                }
            }
        }
        return null;
    }

    // -------------------------------------------------- resolved duplicates

    /**
     * Folds together items whose default state resolves to identical geometry
     * and identical textures, and whose state structure matches.
     *
     * <p>Restricted to items sharing a base item. Two models that happen to look
     * alike but hang off a sword and a hat are two different things to anyone
     * using the pack, and merging them would lose that.</p>
     */
    private List<LogicalModel> mergeIdenticalModels(List<LogicalModel> models) {
        Map<String, LogicalModel> byIdentity = new LinkedHashMap<>();
        List<LogicalModel> out = new ArrayList<>(models.size());
        Map<LogicalModel, List<String>> extraReferences = new LinkedHashMap<>();

        for (LogicalModel model : models) {
            if (!model.previewModel().exists()) {
                out.add(model);
                continue;
            }
            String identity = model.baseItemId() + "|" + model.geometryHash()
                    + "|" + model.textureHash() + "|" + stateSignature(model);
            LogicalModel existing = byIdentity.get(identity);
            if (existing == null) {
                byIdentity.put(identity, model);
                out.add(model);
                continue;
            }
            resolvedDuplicates++;
            extraReferences.computeIfAbsent(existing, key -> new ArrayList<>())
                    .add(model.definitionId() + " -> " + model.defaultState().modelId()
                            + (model.customModelData().isEmpty() ? ""
                            : " [" + String.join(", ", model.customModelData()) + "]"));
        }

        if (extraReferences.isEmpty()) {
            return out;
        }
        List<LogicalModel> rebuilt = new ArrayList<>(out.size());
        for (LogicalModel model : out) {
            List<String> extra = extraReferences.get(model);
            rebuilt.add(extra == null ? model
                    : withReferences(model, extra, GroupReason.SAME_RESOLVED_MODEL));
        }
        return rebuilt;
    }

    /** State labels and conditions, so two items only merge if they behave alike. */
    private static String stateSignature(LogicalModel model) {
        StringBuilder builder = new StringBuilder();
        for (ModelState state : model.states()) {
            builder.append(state.conditionText()).append(';');
        }
        return builder.toString();
    }

    // ----------------------------------------------------- filename fallback

    /**
     * The one guess in the system: merges items whose model file names differ
     * only by a recognised state suffix.
     *
     * <p>It runs last, so it can only ever act on models the definition graph
     * left separate, and it requires the same definition file, the same base
     * item and the same folder before it will consider two names related. Every
     * group it forms is stamped {@link GroupReason#FILENAME_HEURISTIC} and
     * reported as low confidence.</p>
     */
    /**
     * Folds an appearance's render variants onto the appearance.
     *
     * <h2>Why this is not the filename pass</h2>
     *
     * <p>That pass requires the same item definition and the same base item
     * before it will relate two names, which is what stops two unrelated models
     * merging because they share a folder. It is the right guard for a numeric
     * suffix, and it is why five wings stayed five: the pack reaches the base
     * through {@code minecraft:paper} and its four variants through
     * {@code minecraft:potion}, so the keys never matched.</p>
     *
     * <p>A variant suffix carries a stronger claim than a number does. It says
     * this file is the same appearance drawn for a different viewer, which is
     * true across whatever items the pack happened to hang them on. So this
     * pass drops the definition and item from the key and keeps the rest: same
     * namespace, same folder, same stem, and at least one member actually
     * carrying the suffix.</p>
     *
     * <p>The folder is worked out here rather than with {@link #directoryOf},
     * which answers the whole id when there is no folder at all — right for
     * naming, wrong for a key, because it makes every model in a flat namespace
     * its own folder and so groupable with nothing.</p>
     */
    private List<LogicalModel> mergeRenderVariants(List<LogicalModel> models) {
        Map<String, List<LogicalModel>> candidates = new LinkedHashMap<>();
        List<LogicalModel> out = new ArrayList<>();

        for (LogicalModel model : models) {
            if (!model.previewModel().exists()) {
                out.add(model);
                continue;
            }
            String modelId = model.defaultState().modelId();
            String fileName = fileNameOf(modelId);
            String variant = variantStem(fileName);
            String stem = variant != null ? variant : fileName.toLowerCase(Locale.ROOT);
            candidates.computeIfAbsent(
                    namespaceOf(modelId, model.definitionId()) + "|"
                            + folderKeyOf(modelId) + "|" + stem,
                    key -> new ArrayList<>()).add(model);
        }

        for (List<LogicalModel> group : candidates.values()) {
            boolean anyVariant = false;
            for (LogicalModel member : group) {
                if (variantStem(fileNameOf(member.defaultState().modelId())) != null) {
                    anyVariant = true;
                    break;
                }
            }
            if (group.size() < 2 || group.size() > MAX_VARIANT_GROUP || !anyVariant) {
                out.addAll(group);
                continue;
            }
            heuristicGroups++;
            // Shortest name first: the base is the one without the suffix, and
            // it is the one whose preview and name the card should carry.
            group.sort(Comparator.comparingInt(
                            (LogicalModel m) -> fileNameOf(m.defaultState().modelId()).length())
                    .thenComparing(m -> fileNameOf(m.defaultState().modelId())));
            LogicalModel base = group.get(0);
            String groupStem = fileNameOf(base.defaultState().modelId())
                    .toLowerCase(Locale.ROOT);
            List<ModelState> states = new ArrayList<>();
            List<String> references = new ArrayList<>(base.mergedReferences());
            for (LogicalModel member : group) {
                for (ModelState state : member.states()) {
                    states.add(new ModelState(
                            fileNameLabel(state.modelId(), groupStem),
                            true, state.source(), state.resolved()));
                }
                if (member != base) {
                    references.add(member.definitionId() + " -> "
                            + member.defaultState().modelId());
                }
            }
            out.add(new LogicalModel(
                    base.canonicalId(), base.displayName(), base.baseItemId(),
                    base.baseItemIsRegistered(),
                    base.classification(), base.namespace(), base.sourcePackId(),
                    base.definitionId(), base.definitionArchivePath(),
                    base.customModelData(), states, 0,
                    GroupReason.FILENAME_HEURISTIC,
                    base.geometryHash(), base.textureHash(), references,
                    base.packOrdinal(), base.discoveryIndex()));
        }
        return out;
    }

    /** The part of a name before a render-variant suffix, or null. */
    public static String variantStem(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return null;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        // The earliest suffix, not the first in the list. These stack —
        // "x_cosmetic_self" carries two — and cutting at the later one would
        // leave "x_cosmetic", which is a stem no base model has, so the family
        // would split in two instead of folding onto x.
        int earliest = -1;
        for (String suffix : VARIANT_SUFFIXES) {
            int at = lower.indexOf(suffix);
            while (at > 0) {
                if (endsWord(lower, at + suffix.length())
                        && (earliest < 0 || at < earliest)) {
                    earliest = at;
                    break;
                }
                at = lower.indexOf(suffix, at + 1);
            }
        }
        return earliest < 0 ? null : lower.substring(0, earliest);
    }

    /**
     * Whether a suffix match ends where a word ends.
     *
     * <p>Without this the match is a substring search, and
     * {@code wings_cosmeticscore} reads as {@code wings} plus a variant marker
     * — folding a set into another set that merely shares a prefix. A marker
     * ends the name, or is followed by another marker, or by its layer
     * number.</p>
     */
    private static boolean endsWord(String name, int at) {
        if (at >= name.length()) {
            return true;
        }
        char next = name.charAt(at);
        return next == '_' || Character.isDigit(next);
    }

    /** The folder part of a model id, or empty when it has none. */
    private static String folderKeyOf(String modelId) {
        if (modelId == null) {
            return "";
        }
        int slash = modelId.lastIndexOf('/');
        if (slash >= 0) {
            return modelId.substring(0, slash);
        }
        return "";
    }

    private List<LogicalModel> mergeByFilename(List<LogicalModel> models) {
        Map<String, List<LogicalModel>> candidates = new LinkedHashMap<>();
        List<LogicalModel> out = new ArrayList<>();

        for (LogicalModel model : models) {
            if (model.hasMultipleStates() || !model.previewModel().exists()) {
                out.add(model);
                continue;
            }
            String fileName = fileNameOf(model.defaultState().modelId());
            String suffixed = stateStem(fileName);
            // A model with no state suffix is still a candidate: it is the one
            // the suffixed names are stating a state of. Requiring at least one
            // genuinely suffixed member is what stops two unrelated models from
            // being merged just because they sit in the same folder.
            String stem = suffixed != null ? suffixed
                    : fileName.toLowerCase(java.util.Locale.ROOT);
            String key = model.definitionId() + "|" + model.baseItemId() + "|"
                    + directoryOf(model.defaultState().modelId()) + "|" + stem;
            candidates.computeIfAbsent(key, k -> new ArrayList<>()).add(model);
        }

        for (List<LogicalModel> group : candidates.values()) {
            boolean anySuffixed = false;
            for (LogicalModel member : group) {
                if (stateStem(fileNameOf(member.defaultState().modelId())) != null) {
                    anySuffixed = true;
                    break;
                }
            }
            // A real state sequence is small: at rest, plus a handful of draw
            // or blocking stages. A large group means the names rhyme for some
            // other reason, and merging on that evidence would be wrong.
            if (group.size() < 2 || group.size() > MAX_HEURISTIC_GROUP || !anySuffixed) {
                out.addAll(group);
                continue;
            }
            heuristicGroups++;
            group.sort(Comparator.comparingInt(
                            (LogicalModel m) -> fileNameOf(m.defaultState().modelId()).length())
                    .thenComparing(m -> fileNameOf(m.defaultState().modelId())));
            LogicalModel base = group.get(0);
            String groupStem = fileNameOf(base.defaultState().modelId())
                    .toLowerCase(java.util.Locale.ROOT);
            List<ModelState> states = new ArrayList<>();
            List<String> references = new ArrayList<>(base.mergedReferences());
            for (LogicalModel member : group) {
                for (ModelState state : member.states()) {
                    states.add(new ModelState(
                            fileNameLabel(state.modelId(), groupStem),
                            true, state.source(), state.resolved()));
                }
                if (member != base) {
                    references.add(member.definitionId() + " -> "
                            + member.defaultState().modelId());
                }
            }
            out.add(new LogicalModel(
                    base.canonicalId(), base.displayName(), base.baseItemId(),
                    base.baseItemIsRegistered(),
                    base.classification(), base.namespace(), base.sourcePackId(),
                    base.definitionId(), base.definitionArchivePath(),
                    base.customModelData(), states, 0,
                    GroupReason.FILENAME_HEURISTIC,
                    base.geometryHash(), base.textureHash(), references,
                    base.packOrdinal(), base.discoveryIndex()));
        }
        return out;
    }

    /**
     * The part of a file name before a recognised state suffix, or null when
     * the name carries no such suffix.
     *
     * <p>Public because it is the one guess in the grouping system and the one
     * most worth pinning down in tests; see {@code tools/ExplorerTest.java}.</p>
     */
    public static String stateStem(String fileName) {
        if (fileName.isEmpty()) {
            return null;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        String variant = variantStem(fileName);
        if (variant != null) {
            return variant;
        }
        for (String suffix : STATE_SUFFIXES) {
            int at = lower.lastIndexOf(suffix);
            if (at > 0) {
                return lower.substring(0, at);
            }
        }
        // Trailing "-1" or "_2", but only when the stem it leaves behind does
        // not itself end in a digit: "sword_2_3" must not become "sword_2",
        // which would make two unrelated numbered models look like states of a
        // third that does not exist.
        int split = lower.length();
        while (split > 0 && Character.isDigit(lower.charAt(split - 1))) {
            split--;
        }
        int digits = lower.length() - split;
        if (digits == 0 || split < 2) {
            return null;
        }
        // A state index is short: bow-1, crossbow_pulling_2, shield_3. A long
        // or zero-padded number is a catalogue index, and treating it as a
        // state is how "sword_0000" through "sword_0399" turn into one item
        // with four hundred states. Three digits is already past anything a
        // real state sequence needs.
        if (digits > 2 || (digits > 1 && lower.charAt(split) == '0')) {
            return null;
        }
        char separator = lower.charAt(split - 1);
        if (separator != '-' && separator != '_') {
            return null;
        }
        String stem = lower.substring(0, split - 1);
        return stem.isEmpty() || Character.isDigit(stem.charAt(stem.length() - 1))
                ? null : stem;
    }

    private static String fileNameLabel(String modelId, String stem) {
        String fileName = fileNameOf(modelId);
        if (stem == null || !fileName.toLowerCase(Locale.ROOT).startsWith(stem)) {
            return prettify(fileName);
        }
        String remainder = fileName.substring(stem.length()).replaceAll("^[-_]+", "");
        return remainder.isEmpty() ? "Default" : prettify(remainder);
    }

    private static LogicalModel withReferences(LogicalModel model,
                                               List<String> extra,
                                               GroupReason reason) {
        List<String> references = new ArrayList<>(model.mergedReferences());
        references.addAll(extra);
        return new LogicalModel(
                model.canonicalId(), model.displayName(), model.baseItemId(),
                model.baseItemIsRegistered(),
                model.classification(), model.namespace(), model.sourcePackId(),
                model.definitionId(), model.definitionArchivePath(),
                model.customModelData(), model.states(), model.defaultStateIndex(),
                model.groupReason() == GroupReason.SINGLE ? reason : model.groupReason(),
                model.geometryHash(), model.textureHash(), references,
                model.packOrdinal(), model.discoveryIndex());
    }

    /**
     * Collapses the routes to a model down to one entry each.
     *
     * <p>The entry kept is one whose base item is a real registered item where
     * there is one, because that route carries the better name and the better
     * classification — a model reached only through a pack-namespace definition
     * knows nothing about what kind of item it is meant to be. The items that
     * were merged away are recorded on the survivor rather than discarded, so
     * the detail view can still say what else carries it.</p>
     */
    public static List<LogicalModel> collapseToDistinctModels(List<LogicalModel> all) {
        Map<String, List<LogicalModel>> byModel = new LinkedHashMap<>();
        for (LogicalModel model : all) {
            byModel.computeIfAbsent(model.defaultState().modelId(), k -> new ArrayList<>())
                    .add(model);
        }
        List<LogicalModel> kept = new ArrayList<>(byModel.size());
        for (List<LogicalModel> group : byModel.values()) {
            LogicalModel best = group.get(0);
            for (LogicalModel candidate : group) {
                if (candidate.baseItemIsRegistered() && !best.baseItemIsRegistered()) {
                    best = candidate;
                }
            }
            if (group.size() > 1) {
                Set<String> others = new LinkedHashSet<>();
                for (LogicalModel member : group) {
                    if (member != best && member.baseItemIsRegistered()) {
                        others.add(member.baseItemId());
                    }
                }
                best.applyAlsoCarriedBy(new ArrayList<>(others));
            }
            kept.add(best);
        }
        return List.copyOf(kept);
    }

    // --------------------------------------------------------------- hashes

    /**
     * A hash of everything about a model's shape. Two models with the same
     * value draw the same silhouette, whatever their files are called.
     *
     * <p>Computed during resolution rather than here, because doing it here
     * meant every resolved model had to keep its cuboids alive until the whole
     * pack had been grouped.</p>
     */
    static String geometryHash(ResolvedModel model) {
        return model.exists() ? model.geometryHash() : "missing";
    }

    /**
     * A hash of the textures a model actually uses.
     *
     * <p>Texture <em>ids</em> are hashed rather than texture <em>pixels</em>.
     * Hashing pixels would catch two differently-named but byte-identical PNGs,
     * and would also mean decoding every image in the pack during the scan,
     * which is exactly the cost the scan exists to avoid. Two models pointing at
     * the same texture is the case that actually occurs.</p>
     */
    static String textureHash(ResolvedModel model) {
        if (!model.exists()) {
            return "missing";
        }
        List<String> textures = new ArrayList<>(model.textureSlots().values());
        textures.sort(Comparator.naturalOrder());
        return digest(String.join("|", textures));
    }

    private static String canonicalId(String routeKey, List<ModelState> states) {
        StringBuilder builder = new StringBuilder(routeKey);
        for (ModelState state : states) {
            builder.append('#').append(state.modelId());
        }
        return digest(builder.toString());
    }

    /**
     * One digest per thread rather than one per call.
     *
     * <p>{@code MessageDigest.getInstance} walks the security provider list,
     * which showed up in profiles of a large scan: three hashes are taken per
     * model, so a pack with three thousand of them made nine thousand provider
     * lookups. The instance is reset by {@code digest()} itself, so reusing it
     * is safe as long as it does not cross threads — hence the thread local.</p>
     */
    private static final ThreadLocal<MessageDigest> SHA1 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            // Every JVM is required to provide SHA-1.
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    });

    private static String digest(String input) {
        try {
            MessageDigest sha = SHA1.get();
            byte[] bytes = sha.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(Character.forDigit((bytes[i] >> 4) & 0xF, 16));
                hex.append(Character.forDigit(bytes[i] & 0xF, 16));
            }
            return hex.toString();
        } catch (RuntimeException e) {
            // Only reachable if SHA-1 is somehow unavailable; a weaker identity
            // still lets the scan finish.
            return Integer.toHexString(input.hashCode());
        }
    }

    // -------------------------------------------------------------- helpers

    private static String stateKey(DiscoveredModel leaf) {
        StringBuilder builder = new StringBuilder();
        for (Selector step : leaf.stateSteps()) {
            builder.append(step.token()).append('/');
        }
        return builder.toString();
    }

    private static String describe(List<Selector> steps) {
        if (steps.isEmpty()) {
            return "no conditions";
        }
        List<String> parts = new ArrayList<>(steps.size());
        for (Selector step : steps) {
            parts.add(step.describe());
        }
        return String.join(", ", parts);
    }

    public static String fileNameOf(String modelId) {
        if (modelId == null || modelId.isEmpty()) {
            return "";
        }
        int colon = modelId.indexOf(':');
        String path = colon >= 0 ? modelId.substring(colon + 1) : modelId;
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    public static String directoryOf(String modelId) {
        if (modelId == null) {
            return "";
        }
        int slash = modelId.lastIndexOf('/');
        return slash >= 0 ? modelId.substring(0, slash) : modelId;
    }

    private static String namespaceOf(String modelId, String fallbackId) {
        int colon = modelId.indexOf(':');
        if (colon > 0) {
            return modelId.substring(0, colon);
        }
        colon = fallbackId.indexOf(':');
        return colon > 0 ? fallbackId.substring(0, colon) : "minecraft";
    }

    /** Splits on separators, camel humps and letter/digit boundaries. */
    public static List<String> tokenize(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        StringBuilder current = new StringBuilder();
        char[] chars = raw.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (!Character.isLetterOrDigit(c)) {
                flush(out, current);
                continue;
            }
            if (current.length() > 0) {
                char previous = chars[i - 1];
                boolean hump = Character.isUpperCase(c) && Character.isLowerCase(previous);
                boolean digitEdge = Character.isDigit(c) != Character.isDigit(previous);
                if (hump || digitEdge) {
                    flush(out, current);
                }
            }
            current.append(Character.toLowerCase(c));
        }
        flush(out, current);
        return out;
    }

    private static void flush(List<String> out, StringBuilder current) {
        if (current.length() > 0) {
            out.add(current.toString());
            current.setLength(0);
        }
    }

    /** {@code dragon_bow} becomes {@code Dragon Bow}. */
    public static String prettify(String raw) {
        List<String> tokens = tokenize(raw);
        if (tokens.isEmpty()) {
            return raw == null ? "" : raw;
        }
        StringBuilder out = new StringBuilder();
        for (String token : tokens) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(token.charAt(0)));
            if (token.length() > 1) {
                out.append(token.substring(1));
            }
        }
        return out.toString();
    }
}
