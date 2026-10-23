package dev.custommodels.cmv.explorer.def;

import java.util.ArrayList;
import java.util.List;

/**
 * One concrete model reference found inside an item definition, together with
 * the full route taken to reach it.
 *
 * <p>This is deliberately the <em>raw</em> discovery result: nothing here has
 * been grouped, deduplicated or prettified yet. Keeping the route means the
 * detail UI can later explain the discovery instead of asserting it.</p>
 */
public final class DiscoveredModel {

    /** What kind of leaf node produced this reference. */
    public enum LeafKind {
        /** {@code minecraft:model} — a plain reference to a model JSON. */
        MODEL,
        /** {@code minecraft:special} — a hard-coded renderer over a base model. */
        SPECIAL,
        /** {@code minecraft:bundle/selected_item} — renders whatever is inside. */
        BUNDLE_CONTENT,
        /** {@code minecraft:empty} — draws nothing. */
        EMPTY
    }

    private final String baseItemId;
    private final String definitionId;
    private final String definitionArchivePath;
    private final String sourcePackId;
    private final String modelId;
    private final LeafKind leafKind;
    private final List<Selector> path;
    private final List<String> tints;
    private final int discoveryIndex;
    private final boolean hasNodeTransformation;

    public DiscoveredModel(String baseItemId,
                           String definitionId,
                           String definitionArchivePath,
                           String sourcePackId,
                           String modelId,
                           LeafKind leafKind,
                           List<Selector> path,
                           List<String> tints,
                           int discoveryIndex,
                           boolean hasNodeTransformation) {
        this.baseItemId = baseItemId;
        this.definitionId = definitionId;
        this.definitionArchivePath = definitionArchivePath;
        this.sourcePackId = sourcePackId;
        this.modelId = modelId == null ? "" : modelId;
        this.leafKind = leafKind;
        this.path = List.copyOf(path);
        this.tints = List.copyOf(tints);
        this.discoveryIndex = discoveryIndex;
        this.hasNodeTransformation = hasNodeTransformation;
    }

    /** e.g. {@code minecraft:bow}, derived from the definition file's own path. */
    public String baseItemId() {
        return baseItemId;
    }

    /** e.g. {@code minecraft:items/bow.json} as the resource manager reports it. */
    public String definitionId() {
        return definitionId;
    }

    /** e.g. {@code assets/minecraft/items/bow.json}. */
    public String definitionArchivePath() {
        return definitionArchivePath;
    }

    public String sourcePackId() {
        return sourcePackId;
    }

    /** e.g. {@code example:item/bows/dragon_bow}. Empty for {@link LeafKind#EMPTY}. */
    public String modelId() {
        return modelId;
    }

    public LeafKind leafKind() {
        return leafKind;
    }

    /** The branches taken from the definition root down to this leaf. */
    public List<Selector> path() {
        return path;
    }

    /** Raw {@code tints} entries recorded as text, for the information panel. */
    public List<String> tints() {
        return tints;
    }

    /** Order this leaf was encountered in its definition file. */
    public int discoveryIndex() {
        return discoveryIndex;
    }

    /** True when a node on the route carried an explicit {@code transformation}. */
    public boolean hasNodeTransformation() {
        return hasNodeTransformation;
    }

    /**
     * True when custom model data was consulted anywhere on the route.
     *
     * <p>This is the reachability test that separates "a model this pack adds"
     * from "a model that happens to exist in the pack". The idea comes from
     * Custom Model Data Viewer (Apache-2.0), whose scanner carries the same
     * flag down its recursion; see {@code NOTICE} in the repository root.</p>
     */
    public boolean viaCustomModelData() {
        for (Selector step : path) {
            // The fallback branch of a custom model data selection is the
            // branch taken when the item carries no custom model data at all,
            // so passing through it is the opposite of being selected by one.
            if (step.isIdentity() && step.branch() != Selector.Branch.FALLBACK) {
                return true;
            }
        }
        return false;
    }

    /**
     * The custom model data selections on the route, outermost first. These are
     * the strings a player would have to put on an item to make this model
     * appear, and together they identify the logical item.
     */
    /**
     * The steps that decide which appearance this is, as opposed to which state
     * of it. Usually custom model data; see {@link Selector#isIdentity()}.
     */
    public List<Selector> identitySteps() {
        List<Selector> out = new ArrayList<>(2);
        for (Selector step : path) {
            if (step.isIdentity()) {
                out.add(step);
            }
        }
        return out;
    }

    /**
     * The steps below the last identity selection. These are what make
     * one logical item's states differ from each other: pull stages, blocking,
     * broken, charge type and so on.
     */
    public List<Selector> stateSteps() {
        int lastIdentity = -1;
        for (int i = 0; i < path.size(); i++) {
            if (path.get(i).isIdentity()) {
                lastIdentity = i;
            }
        }
        return path.subList(lastIdentity + 1, path.size());
    }

    /** Sum of {@link Selector#activationCost()} over the state steps. */
    public int activationCost() {
        int total = 0;
        for (Selector step : stateSteps()) {
            total += step.activationCost();
        }
        return total;
    }

    @Override
    public String toString() {
        return baseItemId + " -> " + modelId + " " + path;
    }
}
