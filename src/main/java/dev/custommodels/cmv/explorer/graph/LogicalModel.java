package dev.custommodels.cmv.explorer.graph;

import dev.custommodels.cmv.explorer.def.Selector;
import dev.custommodels.cmv.explorer.res.ResolvedModel;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * One item as a person thinks of it: "the Dragon Bow", not "the four JSON files
 * that make up the Dragon Bow".
 *
 * <p>This is what a card in the browser stands for. It carries every state the
 * item can take, every reference that led to it, and the reason its parts were
 * put together, so that nothing discovered during the scan is lost to
 * grouping.</p>
 */
public final class LogicalModel {

    private final String canonicalId;
    private final String displayName;
    private ResolvedName resolvedName;
    private List<String> alsoCarriedBy = List.of();
    private final String baseItemId;
    private final boolean baseItemIsRegistered;
    private ItemType.Classification classification;
    private final String namespace;
    private String sourcePackId;
    private final String definitionId;
    private final String definitionArchivePath;
    private final List<String> customModelData;
    private final List<ModelState> states;
    private final int defaultStateIndex;
    private final GroupReason groupReason;
    private final String geometryHash;
    private final String textureHash;
    private final List<String> mergedReferences;
    private final int packOrdinal;
    private final int discoveryIndex;

    private String searchBlob;

    LogicalModel(String canonicalId,
                 String displayName,
                 String baseItemId,
                 boolean baseItemIsRegistered,
                 ItemType.Classification classification,
                 String namespace,
                 String sourcePackId,
                 String definitionId,
                 String definitionArchivePath,
                 List<String> customModelData,
                 List<ModelState> states,
                 int defaultStateIndex,
                 GroupReason groupReason,
                 String geometryHash,
                 String textureHash,
                 List<String> mergedReferences,
                 int packOrdinal,
                 int discoveryIndex) {
        this.canonicalId = canonicalId;
        this.displayName = displayName;
        this.baseItemId = baseItemId;
        this.baseItemIsRegistered = baseItemIsRegistered;
        this.classification = classification;
        this.namespace = namespace;
        this.sourcePackId = sourcePackId;
        this.definitionId = definitionId;
        this.definitionArchivePath = definitionArchivePath;
        this.customModelData = List.copyOf(customModelData);
        this.states = List.copyOf(states);
        this.defaultStateIndex = defaultStateIndex;
        this.groupReason = groupReason;
        this.geometryHash = geometryHash;
        this.textureHash = textureHash;
        this.mergedReferences = List.copyOf(mergedReferences);
        this.packOrdinal = packOrdinal;
        this.discoveryIndex = discoveryIndex;
    }

    /**
     * A stable identity for this item, built from the facts that decide what it
     * is rather than from where its files happen to sit. Two scans of the same
     * pack produce the same value, which is what makes the thumbnail cache and
     * the incremental scan work.
     */
    public String canonicalId() {
        return canonicalId;
    }

    /** e.g. {@code Dragon Bow}. */
    public String displayName() {
        return resolvedName != null ? resolvedName.value() : displayName;
    }

    /**
     * The name together with where it came from.
     *
     * <p>Never null once the scan has finished. Before then it falls back to the
     * path-derived name, marked as such, so a caller never has to special-case
     * a half-built model.</p>
     */
    public ResolvedName resolvedName() {
        return resolvedName != null ? resolvedName
                : ResolvedName.of(displayName, ResolvedName.Source.PATH, "");
    }

    public void applyResolvedName(ResolvedName name) {
        this.resolvedName = name;
    }

    /**
     * Other items that reach this same model.
     *
     * <p>A pack points many item definitions at one model — on the pack this
     * was tuned against, one model is reachable from 1,390 of them. Listing it
     * once per definition turned 10,732 skins into 55,642 entries to scroll
     * past. The browse list keeps one entry and hangs the rest here.</p>
     */
    public List<String> alsoCarriedBy() {
        return alsoCarriedBy;
    }

    public void applyAlsoCarriedBy(List<String> items) {
        this.alsoCarriedBy = List.copyOf(items);
    }

    /**
     * The id of the item definition file this came from, e.g. {@code minecraft:bow}.
     *
     * <p>Only a real base item when {@link #baseItemIsRegistered()} is true. See
     * that method before showing this to anyone as "the item".</p>
     */
    public String baseItemId() {
        return baseItemId;
    }

    /**
     * Whether {@link #baseItemId()} names an item that exists.
     *
     * <p>False for a definition in a pack's own namespace, which since 1.21.4
     * is reached through an item's {@code minecraft:item_model} component
     * rather than by being that item. Which item ends up carrying it is the
     * server's decision and is not in the pack, so the explorer says so rather
     * than guessing.</p>
     */
    public boolean baseItemIsRegistered() {
        return baseItemIsRegistered;
    }

    /** How to describe the item relationship in the UI, without overclaiming. */
    public String baseItemLabel() {
        return baseItemIsRegistered
                ? baseItemId
                : baseItemId + "   (item_model target, not an item)";
    }

    public ItemType type() {
        return classification.type();
    }

    /** Where the type came from, so the UI can flag an inference as one. */
    public ItemType.Classification classification() {
        return classification;
    }

    /**
     * Set by the reclassification pass, which can see every item a model is
     * offered on and therefore knows things a single route does not.
     */
    public void applyClassification(ItemType.Classification value) {
        this.classification = value;
    }

    /** Namespace of the model, e.g. {@code example}. */
    public String namespace() {
        return namespace;
    }

    public String sourcePackId() {
        return sourcePackId;
    }

    /**
     * Corrects which pack this came from.
     *
     * <p>For a model reached through a definition the mod generated, the
     * definition's pack is the mod's own — and that is where the file is, not
     * where the skin is from. The model file itself never moves, so the pack
     * holding it is the truthful answer, and it is only known once the model
     * has been resolved. Clears the search text, which had the wrong pack in
     * it.</p>
     */
    public void applySourcePack(String packId) {
        this.sourcePackId = packId;
        this.searchBlob = null;
    }

    /** e.g. {@code minecraft:items/bow.json}. */
    public String definitionId() {
        return definitionId;
    }

    /** e.g. {@code assets/minecraft/items/bow.json}. */
    public String definitionArchivePath() {
        return definitionArchivePath;
    }

    /** The custom model data strings that select this item, outermost first. */
    public List<String> customModelData() {
        return customModelData;
    }

    public List<ModelState> states() {
        return states;
    }

    /** The state shown on the card: the item at rest. */
    public ModelState defaultState() {
        return states.get(Math.min(defaultStateIndex, states.size() - 1));
    }

    public int defaultStateIndex() {
        return defaultStateIndex;
    }

    public boolean hasMultipleStates() {
        return states.size() > 1;
    }

    /** Worked out once, on demand; the states never change after grouping. */
    private List<Integer> animationFrames;

    /**
     * The states that are frames of one animation, in the order they play.
     *
     * <h2>Which states those are</h2>
     *
     * <p>Not all of them, and most models have none. A model's states come in
     * two kinds, and only one can be animated:</p>
     *
     * <ul>
     *   <li>A member of a {@code composite} is drawn <em>with</em> its
     *       siblings — it is a part of the model, not a version of it. Showing
     *       the parts one after another would take a model apart rather than
     *       animate it. Measured over the packs this was built against, this is
     *       almost all of them: 34,762 of the 35,193 models with more than one
     *       state.</li>
     *   <li>Everything else is reached <em>instead of</em> its siblings — a bow
     *       at each stage of its draw, a crossbow loaded with an arrow, a rod
     *       cast. Those are frames. There are 431 such models, 0.7% of the
     *       catalogue, which is why animating them costs so little.</li>
     * </ul>
     *
     * <h2>The order</h2>
     *
     * <p>The item at rest first, then the draw stages in the order the game
     * would pass through them — taken from the threshold each one waits for,
     * not from its name — then anything else. A bow therefore reads as a bow
     * being drawn rather than as its states in file order.</p>
     *
     * @return the state indices to cycle, or empty when there is nothing to
     *         animate
     */
    public List<Integer> animationFrames() {
        if (animationFrames == null) {
            animationFrames = List.copyOf(computeAnimationFrames());
        }
        return animationFrames;
    }

    private List<Integer> computeAnimationFrames() {
        if (states.size() < 2) {
            return List.of();
        }
        List<Integer> frames = new java.util.ArrayList<>(states.size());
        for (int i = 0; i < states.size(); i++) {
            if (!isCompositeMember(states.get(i))) {
                frames.add(i);
            }
        }
        if (frames.size() < 2) {
            return List.of();
        }
        int fallback = Math.min(defaultStateIndex, states.size() - 1);
        frames.sort(java.util.Comparator
                .comparingInt((Integer i) -> i == fallback ? 0 : 1)
                .thenComparingInt(i -> drawThreshold(states.get(i)) == null ? 1 : 0)
                .thenComparing(i -> {
                    Float threshold = drawThreshold(states.get(i));
                    return threshold == null ? 0.0F : threshold;
                })
                .thenComparingInt(i -> i));
        return frames;
    }

    private static boolean isCompositeMember(ModelState state) {
        for (Selector step : state.source().path()) {
            if (step.branch() == Selector.Branch.MEMBER) {
                return true;
            }
        }
        return false;
    }

    /**
     * How far through a draw this state waits for, or null if it is not one.
     *
     * <p>The dispatch's fallback is the opening stage, not a missing one: it is
     * what a bow looks like from the instant it is drawn until the first
     * threshold is reached. It carries no number to sort by, so it is given one
     * below every real threshold — without which a bow plays its first frame
     * last, which is a bow snapping back rather than being drawn.</p>
     */
    private static Float drawThreshold(ModelState state) {
        for (Selector step : state.steps()) {
            if (step.kind() != Selector.Kind.RANGE_DISPATCH
                    || !ModelState.isDrawProperty(step.shortProperty())) {
                continue;
            }
            if (step.branch() == Selector.Branch.FALLBACK) {
                return -Float.MAX_VALUE;
            }
            if (!Float.isNaN(step.threshold())) {
                return step.threshold();
            }
        }
        return null;
    }

    public GroupReason groupReason() {
        return groupReason;
    }

    /** Hash of the resolved geometry, used for duplicate detection. */
    public String geometryHash() {
        return geometryHash;
    }

    /** Hash of the resolved texture set, used for duplicate detection. */
    public String textureHash() {
        return textureHash;
    }

    /**
     * Other references that resolved to this same item and were folded into it.
     * Kept so that deduplication never destroys information.
     */
    public List<String> mergedReferences() {
        return mergedReferences;
    }

    /** Position of the definition file within its pack; lower means added earlier. */
    public int packOrdinal() {
        return packOrdinal;
    }

    /** Position within the definition file. */
    public int discoveryIndex() {
        return discoveryIndex;
    }

    /** The default state's model, which is what previews render. */
    public ResolvedModel previewModel() {
        return defaultState().resolved();
    }

    /** Every distinct model id across all states. */
    public List<String> allModelIds() {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (ModelState state : states) {
            if (!state.modelId().isEmpty()) {
                ids.add(state.modelId());
            }
        }
        return List.copyOf(ids);
    }

    /** Every distinct texture id across all states. */
    public List<String> allTextures() {
        LinkedHashSet<String> textures = new LinkedHashSet<>();
        for (ModelState state : states) {
            textures.addAll(state.resolved().textureSlots().values());
        }
        return List.copyOf(textures);
    }

    /** Textures referenced but absent from every loaded pack. */
    public List<String> missingTextures() {
        LinkedHashSet<String> missing = new LinkedHashSet<>();
        for (ModelState state : states) {
            missing.addAll(state.resolved().missingTextures());
        }
        return List.copyOf(missing);
    }

    /** True when at least one state has geometry that can be drawn. */
    public boolean isRenderable() {
        for (ModelState state : states) {
            ResolvedModel resolved = state.resolved();
            if (resolved.exists() && !resolved.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Problems worth surfacing: missing files, bad JSON, circular parents. */
    public List<String> warnings() {
        List<String> out = new ArrayList<>();
        for (ModelState state : states) {
            ResolvedModel resolved = state.resolved();
            if (!resolved.exists()) {
                out.add(state.label() + ": model " + state.modelId() + " not found");
                continue;
            }
            for (String warning : resolved.warnings()) {
                out.add(state.label() + ": " + warning);
            }
            for (String missing : resolved.missingTextures()) {
                out.add(state.label() + ": texture " + missing + " not found");
            }
            for (String unresolved : resolved.unresolvedSlots()) {
                out.add(state.label() + ": unresolved texture slot " + unresolved);
            }
        }
        return out;
    }

    /**
     * Everything searchable, lowercased and joined once. Building this eagerly
     * turns each search keystroke into a handful of substring scans over a
     * prepared string instead of a walk of the whole object graph.
     */
    public String searchBlob() {
        if (searchBlob == null) {
            StringBuilder builder = new StringBuilder(256);
            append(builder, displayName);
            append(builder, baseItemId);
            append(builder, namespace);
            append(builder, sourcePackId);
            append(builder, definitionId);
            append(builder, definitionArchivePath);
            append(builder, type().displayName());
            append(builder, displayName());
            for (String value : customModelData) {
                append(builder, value);
            }
            for (ModelState state : states) {
                append(builder, state.label());
                append(builder, state.modelId());
                append(builder, state.resolved().archivePath());
                for (String texture : state.resolved().textureSlots().values()) {
                    append(builder, texture);
                }
            }
            for (String reference : mergedReferences) {
                append(builder, reference);
            }
            searchBlob = builder.toString();
        }
        return searchBlob;
    }

    private static void append(StringBuilder builder, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        builder.append(value.toLowerCase(Locale.ROOT)).append(' ');
    }

    @Override
    public String toString() {
        return displayName + " (" + baseItemId + ", " + states.size() + " states)";
    }
}
