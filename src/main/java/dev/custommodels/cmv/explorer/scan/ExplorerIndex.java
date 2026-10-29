package dev.custommodels.cmv.explorer.scan;

import dev.custommodels.cmv.compat.McCompat;
import dev.custommodels.cmv.core.CmvConfig;
import dev.custommodels.cmv.core.CmvMod;
import dev.custommodels.cmv.explorer.graph.ExplorerQuery;
import dev.custommodels.cmv.explorer.graph.ItemType;
import dev.custommodels.cmv.explorer.graph.LogicalModel;
import dev.custommodels.cmv.explorer.render.PreviewService;
import dev.custommodels.cmv.explorer.graph.GraphBuilder;
import dev.custommodels.cmv.explorer.res.ModelResolver;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns the scan result and everything that depends on it.
 *
 * <h2>Lifecycle, and why it matters</h2>
 *
 * <p>The requirement this class exists to meet is that the feature costs
 * nothing while it is not in use. So:</p>
 *
 * <ul>
 *   <li>Nothing runs until {@link #openViewer()} is called. There is no tick
 *       handler, no file watcher and no reload hook doing work behind the
 *       game's back.</li>
 *   <li>The worker thread is created on the first scan and shut down again by
 *       {@link #closeViewer()} once nothing is pending. A closed viewer leaves
 *       no thread, no timer and no GPU allocation behind.</li>
 *   <li>Results survive closing the screen, so reopening is instant. They are
 *       thrown away when the pack fingerprint changes, and not before.</li>
 * </ul>
 *
 * <p>The one thing deliberately kept across a close is the model list itself,
 * which is plain heap data on the order of a few hundred kilobytes for a large
 * pack. Textures and GPU resources are not: those belong to the preview
 * service, and {@link #closeViewer()} releases them.</p>
 */
public final class ExplorerIndex {

    private static final ExplorerIndex INSTANCE = new ExplorerIndex();

    /** Names the worker so it is identifiable in a profiler or thread dump. */
    private static final ThreadFactory WORKER_FACTORY = runnable -> {
        Thread thread = new Thread(runnable, "CMV-Explorer-Scan");
        thread.setDaemon(true);
        // Below normal: a scan must never compete with the render thread for a
        // core, and a few hundred extra milliseconds of scanning is invisible
        // next to a dropped frame.
        thread.setPriority(Thread.NORM_PRIORITY - 2);
        return thread;
    };

    private final AtomicInteger generation = new AtomicInteger();
    private final PreviewService previews = new PreviewService();

    private volatile ExecutorService worker;
    private volatile ExplorerScanner active;
    private volatile ScanReport report = new ScanReport();

    private volatile List<LogicalModel> models = List.of();
    private volatile List<LogicalModel> browsable = List.of();
    private volatile ModelResolver resolver;
    private volatile GameResourceSource source;
    private volatile String fingerprint = "";
    private volatile List<String> packIds = List.of();
    private volatile Map<ItemType, Integer> counts = Map.of();
    private volatile dev.custommodels.cmv.wrap.WrapCatalogue catalogue;
    private volatile int openScreens;

    private ExplorerIndex() {
    }

    public static ExplorerIndex get() {
        return INSTANCE;
    }

    public PreviewService previews() {
        return previews;
    }

    public ScanReport report() {
        return report;
    }

    /**
     * Every model, one entry per item definition that reaches it.
     *
     * <p>This is what the catalogue wants: it has to see all the routes to a
     * model to work out whether it is applied by {@code custom_model_data} on a
     * particular item or by pointing {@code item_model} at a definition.</p>
     */
    public List<LogicalModel> models() {
        return models;
    }

    /**
     * One entry per distinct model — what the explorer lists.
     *
     * <p>Packs point a great many item definitions at the same model. Listing
     * each route separately meant 55,642 cards for 10,732 actual skins, most of
     * them the same picture under a different name, which is not a list anyone
     * can find anything in.</p>
     */
    public List<LogicalModel> browsable() {
        return browsable;
    }


    /**
     * The wrap catalogue for the current scan, built once and shared.
     *
     * <p>Both the catalogue and the detail view need it — the detail view to
     * answer "what would applying this model actually do" — and building it
     * twice for the same scan is pure duplication. Rebuilt when the scan
     * changes or when the item-facts document does, which is what keeps
     * opening a screen free without letting it go stale.</p>
     */
    public dev.custommodels.cmv.wrap.WrapCatalogue catalogue() {
        dev.custommodels.cmv.wrap.WrapCatalogue current = catalogue;
        // The facts document is part of what the catalogue is built from, so
        // it is part of what decides whether the catalogue is still current.
        // Keyed on the models alone, a fetch changed nothing anybody looked at.
        String print = models.hashCode()
                + ":" + dev.custommodels.cmv.core.ItemFacts.stamp();
        if (current == null || !print.equals(current.fingerprint())) {
            current = dev.custommodels.cmv.wrap.WrapCatalogue.build(
                    models, McCompat::itemExists, print);
            catalogue = current;
        }
        return current;
    }

    public ModelResolver resolver() {
        return resolver;
    }

    public GameResourceSource source() {
        return source;
    }

    public List<String> packIds() {
        return packIds;
    }


    /** Model count per type, for greying out empty filter chips. */
    public Map<ItemType, Integer> counts() {
        return counts;
    }

    // ------------------------------------------------------------- lifecycle

    /**
     * Called when a viewer screen opens.
     *
     * <p>Shows whatever is already known immediately, and checks whether the
     * packs have changed on a worker rather than here. That check reads pack
     * files, and for a folder pack — which is what pack authors actually work
     * in — it walks the directory tree. Doing that on the client thread would
     * stall the frame that opens the screen, by an amount proportional to the
     * size of the pack, every single time. So the screen opens against the
     * previous results and swaps them out if the check comes back stale.</p>
     */
    public void openViewer() {
        openScreens++;
        if (models.isEmpty()) {
            refresh();
            return;
        }
        String known = fingerprint;
        int mine = generation.get();
        executor().execute(() -> {
            String current = PackFingerprint.build(
                    McCompat.gameDir().resolve("resourcepacks"),
                    McCompat.selectedPackIds()).value();
            if (current.equals(known)) {
                return;
            }
            McCompat.mc().execute(() -> {
                // Only act if nothing else has already started a scan.
                if (generation.get() == mine && openScreens > 0) {
                    refresh();
                }
            });
        });
    }

    /**
     * Called when a viewer screen closes.
     *
     * <p>The release is deferred to the client's task queue rather than done
     * here, and the reason is the order Minecraft runs things in: opening the
     * detail view from a card calls {@code removed()} on the grid and then
     * {@code init()} on the detail screen, within the same call. Releasing
     * immediately would therefore throw away every cached preview each time the
     * player clicked into a model and again when they came back. By the time
     * the queued task runs, the replacement screen has already registered
     * itself and the release is correctly skipped.</p>
     *
     * <p>When the viewer really is closing, this cancels any scan in flight,
     * releases every GPU texture the previews allocated, and shuts the worker
     * down. What survives is the model list, so reopening does not rebuild
     * it.</p>
     */
    public void closeViewer() {
        openScreens = Math.max(0, openScreens - 1);
        if (openScreens > 0) {
            return;
        }
        McCompat.mc().execute(() -> {
            if (openScreens > 0) {
                return;
            }
            cancelActive();
            previews.releaseAll();
            shutdownWorker();
            // Placements changed anywhere in the viewer are written here, once,
            // rather than by each card on its way out. Moving six models across
            // six cards is one write when the viewer closes instead of six as
            // you go. This is the right place for it because of the check
            // above: card to grid and back drops the count to nothing and puts
            // it back before this runs, so only actually leaving reaches here.
            dev.custommodels.cmv.core.FigurePlacements.flush();
        });
    }

    /**
     * Starts a fresh scan, cancelling anything already running.
     *
     * <p>The only thing done on the calling thread is capturing the resource
     * manager and the config, both of which are cheap reads. Fingerprinting the
     * packs and everything after it happens on the worker.</p>
     */
    /** True while a scan is in flight, so nothing disturbs the packs under it. */
    public boolean isScanning() {
        return active != null;
    }

    public void refresh() {
        cancelActive();
        previews.releaseAll();

        // Captured here, not on the worker: the resource manager must be the
        // one current at the moment the scan was asked for, so that a reload
        // racing the scan is detected rather than silently mixed in.
        var resources = McCompat.resources();
        var packsDir = McCompat.gameDir().resolve("resourcepacks");
        var enabledPacks = McCompat.selectedPackIds();
        CmvConfig config = CmvConfig.get();
        boolean heuristic = config.groupByFilename;
        boolean includeBroken = config.showBrokenEntries;
        int maxModels = config.maxModels;

        ScanReport pending = new ScanReport();
        pending.setPhase(ScanReport.Phase.READING);
        this.report = pending;
        int mine = generation.incrementAndGet();

        executor().execute(() -> {
            PackFingerprint print;
            try {
                print = PackFingerprint.build(packsDir, enabledPacks);
            } catch (RuntimeException e) {
                // Leaving the pending report stuck on "Reading files" would look
                // like a hang, so it is failed explicitly.
                CmvMod.LOGGER.error("Could not fingerprint resource packs", e);
                pending.setFailure(e.getClass().getSimpleName());
                pending.setPhase(ScanReport.Phase.FAILED);
                return;
            }
            if (generation.get() != mine) {
                pending.setPhase(ScanReport.Phase.CANCELLED);
                return;
            }
            ExplorerScanner scanner = new ExplorerScanner(
                    resources, print, heuristic, includeBroken, maxModels);
            this.active = scanner;
            this.report = scanner.report();

            ExplorerScanner.Result result = scanner.run(phase ->
                    CmvMod.LOGGER.debug("Explorer scan phase: {}", phase));
            if (result == null || generation.get() != mine) {
                return;
            }
            // Publishing on the client thread keeps the screen from reading a
            // half-swapped set of fields mid-frame.
            McCompat.mc().execute(() -> {
                if (generation.get() != mine) {
                    return;
                }
                this.models = result.models();
                this.browsable = GraphBuilder.collapseToDistinctModels(result.models());
                this.resolver = result.resolver();
                this.source = result.source();
                this.fingerprint = result.fingerprint().value();
                this.packIds = result.fingerprint().packIds();
                this.counts = countByType(this.browsable);
                this.active = null;
                previews.bind(result.source(), result.resolver(), result.fingerprint().value());
                CmvMod.LOGGER.info(
                        "Model explorer: {} models to browse ({} routes to them) "
                                + "from {} references in {} ms",
                        this.browsable.size(), result.models().size(),
                        result.report().rawModelsFound(),
                        result.report().durationMillis());
            });
        });
    }

    /**
     * Called when Minecraft finishes reloading resources.
     *
     * <p>Deliberately does not rescan. A reload is exactly when the player is
     * least interested in the viewer and most interested in the game being
     * responsive, and rescanning here would add the mod's cost on top of the
     * game's own. Instead the results are marked stale; the next time the
     * viewer opens, the fingerprint check notices and rebuilds.</p>
     */
    public void onResourceReload() {
        cancelActive();
        previews.releaseAll();
        fingerprint = "";
    }

    private void cancelActive() {
        ExplorerScanner scanner = active;
        if (scanner != null) {
            scanner.cancel();
            active = null;
        }
        generation.incrementAndGet();
    }

    private ExecutorService executor() {
        ExecutorService current = worker;
        if (current == null || current.isShutdown()) {
            synchronized (this) {
                if (worker == null || worker.isShutdown()) {
                    worker = Executors.newSingleThreadExecutor(WORKER_FACTORY);
                }
                current = worker;
            }
        }
        return current;
    }

    private void shutdownWorker() {
        synchronized (this) {
            if (worker != null) {
                worker.shutdownNow();
                worker = null;
            }
        }
    }

    private static Map<ItemType, Integer> countByType(List<LogicalModel> models) {
        Map<ItemType, Integer> counts = new EnumMap<>(ItemType.class);
        counts.put(ItemType.ALL, models.size());
        for (LogicalModel model : models) {
            counts.merge(model.type(), 1, Integer::sum);
        }
        return counts;
    }

    // ----------------------------------------------------------------- query

    /** Applies a query to the current results. Cheap enough to call per frame. */
    /**
     * The explorer's list for a query.
     *
     * <p>Queries the collapsed list, not the full one. Querying the full one
     * put the same skin on screen once per item definition that reaches it —
     * an Azure Greatsword card for the diamond sword and another for the
     * netherite sword, which is the same picture twice. The catalogue still
     * gets {@link #models()}, because it needs every route to decide how a skin
     * is applied and to which items.</p>
     */
    public List<LogicalModel> view(ExplorerQuery query) {
        return query.apply(browsable);
    }

    /** Looks up one model by its canonical id, for reopening a detail screen. */
    public LogicalModel byCanonicalId(String canonicalId) {
        for (LogicalModel model : models) {
            if (model.canonicalId().equals(canonicalId)) {
                return model;
            }
        }
        return null;
    }
}
