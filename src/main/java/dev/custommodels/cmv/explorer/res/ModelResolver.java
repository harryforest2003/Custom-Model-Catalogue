package dev.custommodels.cmv.explorer.res;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns a model id into a fully flattened {@link ResolvedModel}.
 *
 * <h2>What Minecraft 26.2 actually does</h2>
 *
 * <p>Resolution walks the {@code parent} chain to its root and then folds it
 * back down, with three separate inheritance rules that are easy to conflate:</p>
 *
 * <ul>
 *   <li><b>Textures</b> merge. Every slot declared anywhere in the chain is
 *       visible; a child's slot wins over an ancestor's. A slot value beginning
 *       with {@code #} is a reference to another slot and is followed until it
 *       reaches a real texture id or a dead end.</li>
 *   <li><b>Geometry does not merge.</b> The nearest ancestor that declares
 *       {@code elements} supplies <em>all</em> of it; a child either replaces
 *       the geometry entirely or inherits it entirely. This is why a model with
 *       only a {@code textures} block still draws its parent's shape.</li>
 *   <li><b>Display transforms</b> merge per context, child winning, matching
 *       the texture rule rather than the geometry one.</li>
 * </ul>
 *
 * <p>The chain root {@code builtin/generated} has no file. It is a marker
 * meaning "extrude geometry from the {@code layerN} textures", which is how
 * every flat item is drawn.</p>
 *
 * <h2>Caching</h2>
 *
 * <p>Parsed files and resolved results are both memoised. A large pack points
 * thousands of item definitions at a few dozen shared parents, so without this
 * the same {@code item/handheld} would be re-read thousands of times. Both maps
 * are concurrent because several worker threads resolve at once.</p>
 */
public final class ModelResolver {

    /** Root markers that mean "generate geometry from layer textures". */
    private static final Set<String> GENERATED_ROOTS = Set.of(
            "minecraft:builtin/generated", "builtin/generated");

    /** Depth guard; also what breaks a circular parent chain. */
    private static final int MAX_PARENT_DEPTH = 64;

    private final ResourceSource source;
    private final Map<String, ModelJson> parsed = new ConcurrentHashMap<>();
    private final Map<String, ResolvedModel> resolved = new ConcurrentHashMap<>();
    private final Set<String> knownMissing = ConcurrentHashMap.newKeySet();

    /**
     * Model ids that have been parsed at least once.
     *
     * <p>The point of the parse cache is to avoid re-reading a parent that
     * fifty children share. Leaf models are read exactly once and then never
     * again, so caching them buys nothing and costs everything: on a pack of
     * ten thousand models it was a 636 MB spike, because a single detailed
     * model can be a hundred kilobytes of cuboids.</p>
     *
     * <p>So nothing is cached on its first parse. A file only earns a cache
     * entry when it is asked for a second time, which is precisely the
     * definition of a shared parent. The cost is one extra read per parent;
     * the saving is every leaf in the pack.</p>
     */
    private final Set<String> parsedOnce = ConcurrentHashMap.newKeySet();

    /**
     * The JSON of every file that declares geometry, deflated, keyed by model id.
     *
     * <p>This exists so that building a mesh never touches the resource manager.
     * The first version of the lazy-geometry change re-opened the pack at render
     * time, on preview worker threads, outside any resource reload — reads the
     * game makes no promise about being safe there, and the run that followed
     * filled the log with {@code ClosedChannelException} from a pack whose zip
     * had been closed underneath it. Whatever the precise mechanism, reading
     * pack files while the game is drawing is not something a mod should be
     * doing, and it is not necessary: the bytes are already in hand during the
     * scan.</p>
     *
     * <p>Deflated because the raw JSON for a large pack is 175 MB and compresses
     * to 26 MB, which is affordable where the parsed form (623 MB) was not.
     * Level 1 rather than 9: the difference in size is a few per cent and the
     * difference in scan time is not.</p>
     */
    private final Map<String, byte[]> geometryJson = new ConcurrentHashMap<>();

    private final java.util.concurrent.atomic.AtomicLong geometryBytes =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * Ceiling on that cache.
     *
     * <p>Past this, later geometry is left out and falls back to a read. On the
     * pack this was measured against the whole thing fits in 26 MB, so the cap
     * is headroom rather than a limit anyone should reach.</p>
     */
    private static final long MAX_GEOMETRY_BYTES = 96L * 1024 * 1024;

    public ModelResolver(ResourceSource source) {
        this.source = source;
    }

    /** Number of distinct model files parsed so far. */
    public int parsedCount() {
        return parsed.size();
    }

    /** Number of distinct models fully resolved so far. */
    public int resolvedCount() {
        return resolved.size();
    }

    /**
     * Releases the parsed-file cache, keeping the resolved results.
     *
     * <p>The parsed form exists only so a parent chain can be walked without
     * re-reading its files, which every model in a chain shares. Once a scan
     * has finished nothing walks a chain again, so holding it is pure cost —
     * and on a large pack it is hundreds of megabytes of cost.</p>
     */
    public void forgetParsed() {
        parsed.clear();
        parsedOnce.clear();
    }

    public void clear() {
        geometryJson.clear();
        geometryBytes.set(0);
        parsed.clear();
        parsedOnce.clear();
        resolved.clear();
        knownMissing.clear();
    }

    /**
     * Resolves a model, reusing earlier work wherever possible.
     *
     * @param rawModelId the reference as written in the item definition; the
     *                   {@code minecraft} namespace is assumed when absent
     */
    public ResolvedModel resolve(String rawModelId) {
        String modelId = ModelJson.namespaced(rawModelId);
        if (modelId == null) {
            return missing("");
        }
        ResolvedModel cached = resolved.get(modelId);
        if (cached != null) {
            return cached;
        }
        ResolvedModel result = resolveUncached(modelId);
        resolved.put(modelId, result);
        return result;
    }

    private ResolvedModel resolveUncached(String modelId) {
        List<String> warnings = new ArrayList<>();

        // Walk up the parent chain, newest first. A LinkedHashSet both keeps
        // the order and detects the A -> B -> A case in one step.
        List<ModelJson> chain = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String current = modelId;
        boolean generated = false;

        while (current != null) {
            if (GENERATED_ROOTS.contains(current)) {
                generated = true;
                break;
            }
            if (!seen.add(current)) {
                warnings.add("Circular parent chain at " + current + "; stopped");
                break;
            }
            if (seen.size() > MAX_PARENT_DEPTH) {
                warnings.add("Parent chain deeper than " + MAX_PARENT_DEPTH + "; stopped");
                break;
            }
            ModelJson json = parse(current);
            if (json == null) {
                if (current.equals(modelId)) {
                    return missing(modelId);
                }
                warnings.add("Parent model " + current + " does not exist");
                break;
            }
            chain.add(json);
            for (String warning : json.warnings()) {
                warnings.add(current + ": " + warning);
            }
            current = json.parentId();
        }

        if (chain.isEmpty()) {
            return missing(modelId);
        }

        // Textures merge child-first. Iterating the chain from the root
        // downwards and overwriting gives the child the last word.
        Map<String, String> rawSlots = new LinkedHashMap<>();
        Set<String> translucentSlots = new LinkedHashSet<>();
        Map<String, ModelJson.Transform> transforms = new LinkedHashMap<>();
        Boolean guiLightFront = null;
        for (int i = chain.size() - 1; i >= 0; i--) {
            ModelJson json = chain.get(i);
            rawSlots.putAll(json.textures());
            translucentSlots.addAll(json.forceTranslucent().keySet());
            transforms.putAll(json.display());
            if (json.guiLight() != null) {
                guiLightFront = "front".equalsIgnoreCase(json.guiLight());
            }
        }

        // Geometry comes wholesale from the nearest ancestor that declares it.
        // Only its identity is kept: see ResolvedModel.isEmpty for why the
        // cuboids themselves are not retained.
        List<ModelJson.Element> elements = List.of();
        String geometryModelId = "";
        for (ModelJson json : chain) {
            if (json.hasElementsKey() && !json.elements().isEmpty()) {
                elements = json.elements();
                geometryModelId = json.modelId();
                rememberGeometry(json.modelId(), json.rawJson());
                break;
            }
        }

        // Resolve every #reference to a concrete texture id.
        Map<String, String> slots = new LinkedHashMap<>();
        List<String> unresolved = new ArrayList<>();
        for (String name : rawSlots.keySet()) {
            String value = follow(name, rawSlots, unresolved);
            if (value != null) {
                slots.put(name, ModelJson.namespaced(value));
            }
        }

        List<String> layers = List.of();
        if (generated) {
            layers = collectLayers(slots);
            if (layers.isEmpty() && elements.isEmpty()) {
                warnings.add("Generated model has no layer textures");
            }
        }

        // Hashed now, while the cuboids are in hand, because they are about to
        // be dropped. See ResolvedModel.isEmpty.
        String geometryHash = GeometryHash.of(elements, generated, layers.size(), transforms);

        // Only report a texture missing once we know it is actually used.
        List<String> missingTextures = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>(slots.values());
        for (String textureId : used) {
            if (!textureExists(textureId)) {
                missingTextures.add(textureId);
            }
        }

        List<String> parentChain = new ArrayList<>();
        for (int i = 1; i < chain.size(); i++) {
            parentChain.add(chain.get(i).modelId());
        }
        if (generated) {
            parentChain.add("minecraft:builtin/generated");
        }

        return new ResolvedModel(
                modelId,
                source.modelArchivePath(modelId),
                nullToEmpty(source.modelSourcePack(modelId)),
                true,
                List.copyOf(parentChain),
                Map.copyOf(slots),
                List.copyOf(missingTextures),
                List.copyOf(unresolved),
                Set.copyOf(translucentSlots),
                geometryModelId,
                elements.size(),
                geometryHash,
                generated,
                List.copyOf(layers),
                Map.copyOf(transforms),
                guiLightFront != null ? guiLightFront : generated,
                List.copyOf(warnings));
    }

    /**
     * Follows a texture slot through any chain of {@code #references}.
     * A slot pointing at itself, or at a slot that does not exist, resolves to
     * nothing and is reported rather than silently dropped.
     */
    private static String follow(String slot, Map<String, String> slots, List<String> unresolved) {
        String value = slots.get(slot);
        Set<String> visited = new LinkedHashSet<>();
        visited.add(slot);
        int guard = 0;
        while (value != null && value.startsWith("#")) {
            if (++guard > 16) {
                unresolved.add("#" + slot + " (reference chain too long)");
                return null;
            }
            String next = value.substring(1);
            if (!visited.add(next)) {
                unresolved.add("#" + next + " (circular texture reference)");
                return null;
            }
            if (!slots.containsKey(next)) {
                unresolved.add("#" + next + " (no such texture slot)");
                return null;
            }
            value = slots.get(next);
        }
        return value;
    }

    /** {@code layer0}, {@code layer1}, ... in order, stopping at the first gap. */
    private static List<String> collectLayers(Map<String, String> slots) {
        List<String> layers = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            String value = slots.get("layer" + i);
            if (value == null) {
                break;
            }
            layers.add(value);
        }
        return layers;
    }

    private ModelJson parse(String modelId) {
        ModelJson cached = parsed.get(modelId);
        if (cached != null) {
            return cached;
        }
        if (knownMissing.contains(modelId)) {
            return null;
        }
        try (Reader reader = source.openModel(modelId)) {
            if (reader == null) {
                knownMissing.add(modelId);
                return null;
            }
            // keepRaw: the text is retained only for files that declare
            // geometry, which is exactly what the geometry cache needs.
            ModelJson json = ModelJson.parse(modelId, reader, true);
            if (!parsedOnce.add(modelId)) {
                parsed.put(modelId, json);
            }
            return json;
        } catch (IOException | RuntimeException e) {
            knownMissing.add(modelId);
            return null;
        }
    }

    /**
     * Reads back the cuboids of a resolved model.
     *
     * <p>Called only when a mesh is actually built, which happens for the
     * models on screen and is then cached. Deliberately does not populate the
     * parse cache: one detailed model can be a hundred kilobytes of faces, and
     * holding every one of them is the thing this method exists to avoid.</p>
     */
    public List<ModelJson.Element> loadElements(ResolvedModel model) {
        if (!model.hasCuboidGeometry()) {
            return List.of();
        }
        String id = model.geometryModelId();
        ModelJson cached = parsed.get(id);
        if (cached != null) {
            return cached.elements();
        }
        byte[] deflated = geometryJson.get(id);
        if (deflated != null) {
            try {
                return ModelJson.parse(id, new java.io.StringReader(Deflate.inflate(deflated)))
                        .elements();
            } catch (RuntimeException e) {
                warnOnce("Could not re-read the geometry of " + id, e);
                return List.of();
            }
        }
        // Nothing cached: only reachable if the cap was hit. Reading the pack
        // is a last resort and is refused outright once the resources this was
        // scanned from are no longer the ones the game is using, because those
        // packs may already be closed.
        if (!source.isCurrent() || !source.canLoadHere()) {
            return List.of();
        }
        try (Reader reader = source.openModel(id)) {
            if (reader == null) {
                return List.of();
            }
            return ModelJson.parse(id, reader).elements();
        } catch (IOException | RuntimeException e) {
            warnOnce("Could not read the geometry of " + id, e);
            return List.of();
        }
    }

    private void rememberGeometry(String modelId, String rawJson) {
        if (rawJson == null || rawJson.isEmpty() || geometryJson.containsKey(modelId)) {
            return;
        }
        if (geometryBytes.get() >= MAX_GEOMETRY_BYTES) {
            return;
        }
        byte[] deflated = Deflate.deflate(rawJson);
        if (geometryJson.putIfAbsent(modelId, deflated) == null) {
            geometryBytes.addAndGet(deflated.length);
        }
    }

    /** Deflated size of the geometry cache, for the scan report. */
    public long geometryCacheBytes() {
        return geometryBytes.get();
    }

    private final java.util.concurrent.atomic.AtomicInteger warned =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Reports a geometry failure, at most a handful of times.
     *
     * <p>The previous version swallowed these silently, which is why a run where
     * every one of them was failing left no trace in the log at all and the only
     * symptom was models that would not draw.</p>
     */
    private void warnOnce(String message, Throwable cause) {
        if (warned.incrementAndGet() <= 5) {
            dev.custommodels.cmv.core.CmvMod.LOGGER.warn("{} ({})", message,
                    cause.getClass().getSimpleName());
        }
    }

    private boolean textureExists(String textureId) {
        if (filed(textureId)) {
            return true;
        }
        for (String alternative : ResourceSource.alternativeTextureIds(textureId)) {
            if (filed(alternative)) {
                return true;
            }
        }
        return false;
    }

    private boolean filed(String textureId) {
        String pack = source.textureSourcePack(textureId);
        return pack != null && !pack.isEmpty();
    }

    private ResolvedModel missing(String modelId) {
        return new ResolvedModel(modelId,
                modelId.isEmpty() ? "" : source.modelArchivePath(modelId),
                "", false, List.of(), Map.of(), List.of(), List.of(), Set.of(), "", 0, "missing",
                false, List.of(), Map.of(), true,
                List.of("Model file not found in any loaded pack"));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Snapshot of cache sizes, used by the statistics panel. */
    public Map<String, Integer> cacheStats() {
        Map<String, Integer> stats = new HashMap<>();
        stats.put("parsedModels", parsed.size());
        stats.put("resolvedModels", resolved.size());
        stats.put("missingModels", knownMissing.size());
        return stats;
    }
}
