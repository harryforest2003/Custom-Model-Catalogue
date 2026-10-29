package dev.custommodels.cmv.explorer.scan;

import dev.custommodels.cmv.compat.McCompat;
import dev.custommodels.cmv.core.CmvMod;
import dev.custommodels.cmv.explorer.def.DiscoveredModel;
import dev.custommodels.cmv.explorer.def.ItemDefinitionParser;
import dev.custommodels.cmv.explorer.graph.GraphBuilder;
import dev.custommodels.cmv.explorer.graph.ItemType;
import dev.custommodels.cmv.explorer.graph.LogicalModel;
import dev.custommodels.cmv.explorer.res.ModelResolver;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Runs the discovery pipeline, entirely off the client thread.
 *
 * <pre>
 *   list assets/&lt;ns&gt;/items/*.json      (resource manager, already indexed)
 *          |
 *   parse each definition               ItemDefinitionParser
 *          |
 *   resolve models and textures         ModelResolver, memoised
 *          |
 *   group states, deduplicate           GraphBuilder
 *          |
 *   logical models                      -> ExplorerIndex
 * </pre>
 *
 * <h2>Why this never touches the client thread</h2>
 *
 * <p>Nothing in the pipeline needs the render thread. Parsing is Gson over
 * streams, resolution is map lookups, texture decoding is Minecraft's PNG
 * decoder, and hashing is arithmetic. The only client-thread work in the whole
 * feature is uploading a finished preview image to the GPU, which the preview
 * service does a couple of frames at a time.</p>
 *
 * <p>The scan is also cancellable at every phase boundary and inside the
 * per-file loop, so closing the screen or starting a rescan stops the old work
 * rather than racing it.</p>
 */
public final class ExplorerScanner {

    /** Result of a completed scan. */
    public record Result(List<LogicalModel> models,
                         ModelResolver resolver,
                         GameResourceSource source,
                         PackFingerprint fingerprint,
                         ScanReport report) {
    }

    /** Raised to unwind the pipeline when a scan is superseded. */
    static final class Cancelled extends RuntimeException {
        Cancelled() {
            super(null, null, false, false);
        }
    }

    private final ResourceManager resources;
    private final PackFingerprint fingerprint;
    private final ScanReport report = new ScanReport();
    private final boolean allowFilenameHeuristic;
    private final boolean includeBroken;
    private final int maxModels;
    private volatile boolean cancelled;

    public ExplorerScanner(ResourceManager resources,
                           PackFingerprint fingerprint,
                           boolean allowFilenameHeuristic,
                           boolean includeBroken,
                           int maxModels) {
        this.resources = resources;
        this.fingerprint = fingerprint;
        this.allowFilenameHeuristic = allowFilenameHeuristic;
        this.includeBroken = includeBroken;
        this.maxModels = Math.max(1000, maxModels);
    }

    public ScanReport report() {
        return report;
    }

    public void cancel() {
        cancelled = true;
    }

    private void checkCancelled() {
        if (cancelled) {
            throw new Cancelled();
        }
    }

    /**
     * Runs the whole pipeline. Call from a worker thread.
     *
     * @param onPhase notified as each phase begins, for logging
     */
    public Result run(Consumer<ScanReport.Phase> onPhase) {
        long start = System.nanoTime();
        try {
            GameResourceSource source = new GameResourceSource(resources);
            ModelResolver resolver = new ModelResolver(source);

            phase(ScanReport.Phase.READING, onPhase);
            List<McCompat.ItemDefinition> definitions =
                    new ArrayList<>(McCompat.listItemDefinitions(resources));
            int found = definitions.size();
            // Dropped here, before anything has been read, because a definition
            // that is never opened costs nothing at all — not a parse, not a
            // model resolution, not a texture. This is the only phase where
            // excluding a pack can still save the whole price of it.
            definitions.removeIf(definition ->
                    dev.custommodels.cmv.core.CmvConfig.get()
                            .ignoresPack(definition.sourcePackId())
                            || dev.custommodels.cmv.core.CmvConfig.get()
                            .ignoresNamespace(definition.namespace()));
            if (definitions.size() < found) {
                report.setIgnoredDefinitions(found - definitions.size());
            }
            report.setProgress(0, definitions.size());

            phase(ScanReport.Phase.DEFINITIONS, onPhase);
            List<DiscoveredModel> discovered = readDefinitions(definitions);

            // And again by the namespace of the model itself, which is not the
            // same question. ModelEngine hangs its entity parts on a vanilla
            // item — one leather_horse_armor definition in the pack measured
            // reaches 1,300 of them — so dropping the modelengine definitions
            // left every one of those still on screen. The model's own
            // namespace is what the player means by "not that".
            int beforeModels = discovered.size();
            discovered.removeIf(model ->
                    dev.custommodels.cmv.core.CmvConfig.get()
                            .ignoresNamespace(namespaceOfModel(model.modelId())));
            if (discovered.size() < beforeModels) {
                report.setIgnoredDefinitions(report.ignoredDefinitions()
                        + (beforeModels - discovered.size()));
            }

            phase(ScanReport.Phase.MODELS, onPhase);
            // Only the models item definitions actually point at are resolved.
            // Walking every model file in the pack instead would read thousands
            // that nothing references; a realistic pack has more unused models
            // than used ones.
            warmResolveInParallel(resolver, discovered);

            // Both of these are asked the same question tens of thousands of
            // times about a few thousand items — measured at 67,264 classify
            // calls over 4,443 distinct items, and 58,389 existence checks over
            // 4,780. Each one is a registry lookup, and classification also
            // walks the item's tags into a fresh set. Remembering the answers
            // removes about nine in ten of them.
            //
            // Scoped to this scan, not to the process: tags are unbound until a
            // world is entered, so an answer from before that must not outlive
            // the scan that got it.
            ItemClassifier classifier = new ItemClassifier();
            java.util.Map<String, Boolean> itemExists = new java.util.concurrent.ConcurrentHashMap<>();

            GraphBuilder builder = new GraphBuilder(
                    resolver,
                    classifier::classify,
                    fingerprint::ordinalOf,
                    PackFingerprint::isUserPack,
                    id -> itemExists.computeIfAbsent(id, McCompat::itemExists),
                    allowFilenameHeuristic)
                    .includeBroken(includeBroken);

            phase(ScanReport.Phase.GROUPING, onPhase);
            List<LogicalModel> models = builder.build(discovered);
            checkCancelled();

            if (models.size() > maxModels) {
                // Sorted first so what survives is the beginning of the list the
                // player would have seen anyway, not an arbitrary slice.
                models.sort(java.util.Comparator.comparing(
                        LogicalModel::displayName, String.CASE_INSENSITIVE_ORDER));
                report.setTruncated(models.size(), maxModels);
                CmvMod.LOGGER.warn(
                        "Model explorer: {} models exceeds the {} limit; listing the first {}."
                                + " Raise maxModels in config.json to see the rest.",
                        models.size(), maxModels, maxModels);
                models = new java.util.ArrayList<>(models.subList(0, maxModels));
            }

            // A model reached through a definition this mod generated reports
            // the mod's own pack, because that is where the definition file
            // sits. The model file it points at never moved, so the pack
            // holding that is where the skin is really from — and it is what
            // the pack filter and the tooltip should say.

            phase(ScanReport.Phase.NAMING, onPhase);
            // Names and sets are decided after grouping, because both depend on
            // the finished set of logical models: a name may come from the set
            // a model turned out to belong to, and a set is defined by which
            // models survived deduplication.
            dev.custommodels.cmv.explorer.graph.NameResolver names =
                    new dev.custommodels.cmv.explorer.graph.NameResolver(source);
            for (LogicalModel model : models) {
                checkCancelled();
                dev.custommodels.cmv.explorer.graph.ResolvedName resolved = names.resolve(
                        model.defaultState().modelId(),
                        model.definitionId(),
                        model.baseItemId(),
                        model.customModelData(),
                        model.displayName());
                // What the server calls it, where a fetched catalogue says.
                // Settled here and not in the wrap catalogue, because this is
                // the name every screen reads: the card, the grid and the
                // tooltip all come back to the model's own resolved name, and
                // one put on a wrap target reaches none of them.
                dev.custommodels.cmv.core.ItemFacts.Fact stated =
                        dev.custommodels.cmv.core.CmvConfig.get().itemFacts
                                ? dev.custommodels.cmv.core.ItemFacts.forModel(
                                        model.baseItemId(), model.customModelData())
                                : null;
                if (stated != null && stated.name() != null && !stated.name().isEmpty()) {
                    String evidence = stated.crate() == null || stated.crate().isEmpty()
                            ? model.baseItemId()
                            : stated.crate() + " \u2014 " + model.baseItemId();
                    resolved = dev.custommodels.cmv.explorer.graph.ResolvedName.of(
                            stated.name(),
                            dev.custommodels.cmv.explorer.graph.ResolvedName.Source.SERVER,
                            evidence);
                }
                model.applyResolvedName(resolved);
            }
            report.setNaming(names.entriesLoaded(), names.filesRead());


            phase(ScanReport.Phase.TEXTURES, onPhase);
            int texturesResolved = countTextures(models);

            phase(ScanReport.Phase.DEDUPLICATING, onPhase);
            report.setResolution(resolver.resolvedCount(), resolver.parsedCount(),
                    texturesResolved, source.texturePixels());
            report.setGrouping(models.size(),
                    builder.exactDuplicatesRemoved() + builder.stateDuplicatesRemoved()
                            + builder.resolvedDuplicatesRemoved(),
                    builder.groupedStates(),
                    builder.heuristicGroupsFormed(),
                    builder.skippedVanilla());
            for (LogicalModel model : models) {
                for (String warning : model.warnings()) {
                    report.addWarning(model.displayName() + " — " + warning);
                }
            }

            // The parsed files were scaffolding for this scan and nothing
            // reads them again; a mesh reads its geometry from the cache.
            resolver.forgetParsed();

            // From here the source belongs to the interface, which draws on the
            // client thread. Nothing may read a pack off it again.
            source.endScan();

            report.setDuration((System.nanoTime() - start) / 1_000_000L);
            report.setPhase(ScanReport.Phase.DONE);
            return new Result(models, resolver, source, fingerprint, report);
        } catch (Cancelled e) {
            report.setPhase(ScanReport.Phase.CANCELLED);
            report.setDuration((System.nanoTime() - start) / 1_000_000L);
            return null;
        } catch (RuntimeException | LinkageError e) {
            CmvMod.LOGGER.error("Model explorer scan failed", e);
            report.setFailure(e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            report.setPhase(ScanReport.Phase.FAILED);
            report.setDuration((System.nanoTime() - start) / 1_000_000L);
            return null;
        }
    }

    /**
     * How many threads to resolve on.
     *
     * <p>Measured on eight cores over a real pack set of 11,847 models: one
     * thread 6,516 ms, three 3,380 ms, six 2,762 ms, eight 2,450 ms. The old
     * cap of three left most of that on the table on a machine with cores to
     * spare.</p>
     *
     * <p>Two are kept back rather than none. The game is still running — there
     * is a render thread that must not be starved, and the player is looking at
     * it — and the last two threads buy three hundred milliseconds of a scan
     * that already takes seconds. Six is the ceiling for the same reason: past
     * that the returns are small and the machine is fully committed to work the
     * player did not ask to wait for.</p>
     */
    private static int resolveThreads() {
        int cores = Runtime.getRuntime().availableProcessors();
        return Math.max(1, Math.min(6, cores - 2));
    }

    /**
     * Resolves every referenced model up front, across a few threads.
     *
     * <p>Resolution is dominated by reading and parsing one small JSON file per
     * model, which is latency as much as computation, so it parallelises
     * usefully. Measured over fresh JVMs on a pack of ten thousand files, the
     * whole scan goes from about 920 ms to about 740 ms. Worthwhile rather than
     * dramatic: a first measurement suggested far better, but it was comparing
     * a cold single-threaded run against a warmed-up parallel one.</p>
     *
     * <p>The graph builder would resolve these anyway, one at a time, as it
     * walked the discovery list; doing it here first means it finds everything
     * already cached.</p>
     *
     * <p>Parallelism is bounded so that a scan cannot saturate a small machine;
     * see {@link #resolveThreads}. The threads are created for this phase and
     * shut down at the end of it — nothing is left running.</p>
     */
    private void warmResolveInParallel(ModelResolver resolver,
                                       List<DiscoveredModel> discovered) {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (DiscoveredModel model : discovered) {
            if (!model.modelId().isEmpty()) {
                ids.add(model.modelId());
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        report.setProgress(0, ids.size());

        int threads = resolveThreads();
        if (threads == 1) {
            int done = 0;
            for (String id : ids) {
                checkCancelled();
                resolver.resolve(id);
                if ((++done & 63) == 0) {
                    report.setProgress(done, ids.size());
                }
            }
            return;
        }

        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads, runnable -> {
                    Thread thread = new Thread(runnable, "CMV-Resolve");
                    thread.setDaemon(true);
                    thread.setPriority(Thread.NORM_PRIORITY - 2);
                    return thread;
                });
        try {
            java.util.concurrent.atomic.AtomicInteger done =
                    new java.util.concurrent.atomic.AtomicInteger();
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>(ids.size());
            for (String id : ids) {
                futures.add(pool.submit(() -> {
                    if (!cancelled) {
                        resolver.resolve(id);
                        int count = done.incrementAndGet();
                        if ((count & 63) == 0) {
                            report.setProgress(count, ids.size());
                        }
                    }
                }));
            }
            for (java.util.concurrent.Future<?> future : futures) {
                checkCancelled();
                try {
                    future.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    // One unreadable model must not sink the scan; the resolver
                    // has already recorded it as missing.
                    CmvMod.LOGGER.debug("Model resolution failed", e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new Cancelled();
                }
            }
        } finally {
            pool.shutdownNow();
        }
        report.setProgress(ids.size(), ids.size());
    }

    /** The namespace of a model id, or empty when it names none. */
    private static String namespaceOfModel(String modelId) {
        if (modelId == null) {
            return "";
        }
        int colon = modelId.indexOf(':');
        return colon > 0 ? modelId.substring(0, colon) : "";
    }

    private void phase(ScanReport.Phase next, Consumer<ScanReport.Phase> onPhase) {
        checkCancelled();
        report.setPhase(next);
        report.setProgress(0, 0);
        if (onPhase != null) {
            onPhase.accept(next);
        }
    }

    private List<DiscoveredModel> readDefinitions(List<McCompat.ItemDefinition> definitions) {
        List<DiscoveredModel> discovered = new ArrayList<>(definitions.size() * 2);
        int read = 0;
        for (McCompat.ItemDefinition definition : definitions) {
            // Checked per file rather than per phase: a very large pack spends
            // most of a scan inside this loop.
            checkCancelled();
            try (BufferedReader reader = definition.resource().openAsReader()) {
                ItemDefinitionParser.Result result = ItemDefinitionParser.parse(
                        definition.baseItemId(),
                        definition.id(),
                        definition.archivePath(),
                        definition.sourcePackId(),
                        reader);
                discovered.addAll(result.models());
                for (ItemDefinitionParser.Warning warning : result.warnings()) {
                    report.addWarning(warning.definitionId() + " — " + warning.message());
                }
            } catch (Exception e) {
                report.addWarning(definition.id() + " — could not be read: "
                        + e.getClass().getSimpleName());
            }
            read++;
            if ((read & 63) == 0) {
                report.setProgress(read, definitions.size());
            }
        }
        report.setProgress(definitions.size(), definitions.size());
        report.setCounts(definitions.size(), read, discovered.size());
        return discovered;
    }

    private int countTextures(List<LogicalModel> models) {
        java.util.Set<String> textures = new java.util.HashSet<>();
        for (LogicalModel model : models) {
            checkCancelled();
            textures.addAll(model.allTextures());
        }
        return textures.size();
    }
}
