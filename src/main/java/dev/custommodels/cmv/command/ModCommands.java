package dev.custommodels.cmv.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.custommodels.cmv.compat.McCompat;
import dev.custommodels.cmv.core.CmvConfig;
import dev.custommodels.cmv.wrap.CatalogueScreen;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * Client-side commands. These never reach the server, so they work in single
 * player and on servers alike without needing operator permission.
 *
 * <p>Every command is marked {@code attended}. Fabric API 0.152.0 added this
 * for a good reason: without it a server can send a clickable chat component
 * that silently fires a client command, which for an item editor would be an
 * obvious abuse vector. Marking them attended means the player has to confirm
 * anything they did not type themselves.</p>
 */
public final class ModCommands {

    private ModCommands() {
    }

    /** Shows every enabled pack and whether the model scan reads it. */
    private static int listPacks() {
        CmvConfig config = CmvConfig.get();
        info("Packs the model scan reads — /cmv packs <name> to turn one off:");
        for (String packId : McCompat.selectedPackIds()) {
            boolean ignored = config.ignoresPack(packId);
            McCompat.sendChat(Component.literal(
                            (ignored ? "  skipped  " : "  scanned  ")
                                    + McCompat.packDisplayName(packId))
                    .withStyle(ignored ? ChatFormatting.DARK_GRAY : ChatFormatting.GREEN));
        }
        return 1;
    }

    /**
     * Shows which namespaces the scan reads, largest first.
     *
     * <p>Sorted by how many models each contributes, because the ones worth
     * turning off are the ones filling the grid, and a pack that bundles a
     * server's whole plugin set has a long tail nobody needs to read.</p>
     */
    /**
     * Sets the fetched document is certain about, listed or made.
     *
     * <p>Nothing is built from a document that describes no sets, which is
     * every catalogue that does not publish them. Nothing is built from a set
     * that arrived incomplete, or whose pieces are not in the packs installed
     * here. Everything else is reported with the reason, so a set that did not
     * appear says why rather than going missing.</p>
     */
    private static int statedSets(boolean make) {
        java.util.Map<String, dev.custommodels.cmv.core.ItemFacts.SetFacts> described =
                dev.custommodels.cmv.core.ItemFacts.sets();
        if (described.isEmpty()) {
            info("The item-facts document describes no sets, so none are built.");
            return 1;
        }
        dev.custommodels.cmv.wrap.WrapCatalogue catalogue =
                dev.custommodels.cmv.explorer.scan.ExplorerIndex.get().catalogue();

        // key -> model, built once from the catalogue rather than searched per
        // piece: a set page's worth of lookups over every wrap is not free.
        java.util.Map<String, String> byKey = new java.util.HashMap<>();
        for (dev.custommodels.cmv.wrap.WrapTarget target : catalogue.all()) {
            target.customModelDataByItem().forEach((item, entries) -> {
                for (dev.custommodels.cmv.wrap.WrapTarget.CustomModelDataEntry entry : entries) {
                    if (entry.numeric()) {
                        byKey.putIfAbsent(
                                dev.custommodels.cmv.core.ItemFacts.key(item, entry.number()),
                                target.model().defaultState().modelId());
                    }
                }
            });
        }

        java.util.List<dev.custommodels.cmv.collection.StatedSets.Plan> plans =
                dev.custommodels.cmv.collection.StatedSets.plan(described, byKey::get);
        long ready = plans.stream().filter(
                dev.custommodels.cmv.collection.StatedSets.Plan::definitive).count();
        if (!make) {
            info(ready + " of " + plans.size() + " described sets are complete here."
                    + (ready > 0 ? "  Run /cmvcat facts sets make to add them." : ""));
            for (dev.custommodels.cmv.collection.StatedSets.Plan plan : plans) {
                if (!plan.definitive()) {
                    info("  skipped " + plan.name() + " - " + plan.why());
                }
            }
            return 1;
        }
        int made = dev.custommodels.cmv.collection.StatedSets.apply(
                dev.custommodels.cmv.collection.SkinSets.get(), plans, "From the server");
        info("Added " + made + " set(s) under \"From the server\".");
        return 1;
    }

    private static int listNamespaces() {
        CmvConfig config = CmvConfig.get();
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (var model : dev.custommodels.cmv.explorer.scan.ExplorerIndex.get().browsable()) {
            counts.merge(model.namespace(), 1, Integer::sum);
        }
        for (String namespace : config.ignoredNamespaces) {
            counts.putIfAbsent(namespace, 0);
        }
        if (counts.isEmpty()) {
            info("Nothing scanned yet — run /cmv rescan first.");
            return 1;
        }
        info("Namespaces the model scan reads — /cmv namespaces <name> to turn one off:");
        counts.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(30)
                .forEach(entry -> {
                    boolean ignored = config.ignoresNamespace(entry.getKey());
                    McCompat.sendChat(Component.literal(
                                    (ignored ? "  skipped  " : "  scanned  ")
                                            + entry.getKey() + "   " + entry.getValue())
                            .withStyle(ignored ? ChatFormatting.DARK_GRAY
                                    : ChatFormatting.GREEN));
                });
        return 1;
    }

    /** Turns one namespace's scanning on or off. */
    private static int toggleNamespace(String wanted) {
        String namespace = wanted.trim().toLowerCase(java.util.Locale.ROOT);
        if (namespace.isEmpty()) {
            info("Give a namespace, e.g. /cmv namespaces modelengine");
            return 0;
        }
        boolean nowIgnored = CmvConfig.get().toggleIgnoredNamespace(namespace);
        info(nowIgnored
                ? namespace + " will be skipped."
                : namespace + " will be read again.");

        // Nothing is rewritten on disk. This catalogue only reads packs, so a
        // filtered namespace is simply one it stops reading — which takes
        // effect on the next scan.
        info("Run /cmv rescan to apply the filter.");
        return 1;
    }

    /**
     * Turns one pack's scanning on or off, by display name or by id.
     *
     * <p>Matched loosely because the thing the player can see is the name in
     * the pack screen, not the {@code file/Whatever.zip} id the game uses.</p>
     */
    private static int togglePack(String wanted) {
        String needle = wanted.trim().toLowerCase(java.util.Locale.ROOT);
        String match = null;
        for (String packId : McCompat.selectedPackIds()) {
            String name = McCompat.packDisplayName(packId).toLowerCase(java.util.Locale.ROOT);
            if (name.equals(needle) || packId.toLowerCase(java.util.Locale.ROOT).equals(needle)) {
                match = packId;
                break;
            }
            if (match == null && (name.contains(needle)
                    || packId.toLowerCase(java.util.Locale.ROOT).contains(needle))) {
                match = packId;
            }
        }
        if (match == null) {
            info("No enabled pack matches \"" + wanted + "\".  /cmv packs lists them.");
            return 1;
        }
        boolean ignored = CmvConfig.get().toggleIgnoredPack(match);
        info(McCompat.packDisplayName(match)
                + (ignored ? " will no longer be scanned." : " will be scanned again.")
                + "  Rescanning.");
        dev.custommodels.cmv.explorer.scan.ExplorerIndex.get().refresh();
        return 1;
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {

            // ---- Model explorer ------------------------------------------
            // Separate from the browser above: that one lists custom model data
            // entries as inventory slots, this one inspects resolved models.
            dispatcher.register(ClientCommands.literal("cataloguemodels")
                    .requires(FabricClientCommandSource::attended)
                    .executes(context -> openExplorer(null))
                    .then(ClientCommands.argument("search", StringArgumentType.greedyString())
                            .executes(context -> openExplorer(
                                    StringArgumentType.getString(context, "search")))));

            dispatcher.register(ClientCommands.literal("cmvcat")
                    .requires(FabricClientCommandSource::attended)
                    .executes(context -> openExplorer(null))
                    .then(ClientCommands.literal("rescan")
                            .executes(context -> {
                                dev.custommodels.cmv.explorer.scan.ExplorerIndex.get().refresh();
                                info("Rescanning resource packs in the background.");
                                return 1;
                            }))

                    // What the scanner looks at, which is not what Minecraft
                    // loads: a pack left out here is still drawn by the game,
                    // it just stops costing a scan and stops filling the grid.
                    .then(ClientCommands.literal("packs")
                            .executes(context -> listPacks())
                            .then(ClientCommands.argument("pack",
                                            StringArgumentType.greedyString())
                                    .executes(context -> togglePack(
                                            StringArgumentType.getString(context, "pack")))))

                    // The same idea one level finer. One pack can hold both the
                    // appearances a player wants and a plugin's entity parts,
                    // and only the namespace tells them apart.
                    .then(ClientCommands.literal("namespaces")
                            .executes(context -> listNamespaces())
                            .then(ClientCommands.argument("namespace",
                                            StringArgumentType.greedyString())
                                    .executes(context -> toggleNamespace(
                                            StringArgumentType.getString(context, "namespace")))))

                    // Builds the item-facts document from a server catalogue.
                    // The only command here that touches a network, and it says
                    // what it wrote rather than doing it quietly.
                    .then(ClientCommands.literal("facts")
                            .executes(context -> {
                                info(dev.custommodels.cmv.core.ItemFacts.count()
                                        + " items described, from "
                                        + (dev.custommodels.cmv.core.ItemFacts.source().isEmpty()
                                                ? "nowhere yet"
                                                : dev.custommodels.cmv.core.ItemFacts.source())
                                        + (dev.custommodels.cmv.core.ItemFacts.updated().isEmpty()
                                                ? "" : ", updated "
                                                        + dev.custommodels.cmv.core.ItemFacts.updated()));
                                return 1;
                            })
                            .then(ClientCommands.literal("update")
                                    .executes(context -> {
                                        String url = CmvConfig.get().itemFactsUrl;
                                        info("Fetching the item catalogue from " + url);
                                        dev.custommodels.cmv.core.ItemFactsFetch.update(url,
                                                outcome -> McCompat.mc().execute(() -> {
                                                    info(outcome.message());
                                                    if (outcome.ok()) {
                                                        // The names are read while
                                                        // the catalogue is built, so
                                                        // the grid has to be built
                                                        // again to show them.
                                                        dev.custommodels.cmv.explorer.scan
                                                                .ExplorerIndex.get().refresh();
                                                    }
                                                }));
                                        return 1;
                                    }))
                            // Sets, but only the ones the document is sure of.
                            .then(ClientCommands.literal("sets")
                                    .executes(context -> statedSets(false))
                                    .then(ClientCommands.literal("make")
                                            .executes(context -> statedSets(true))))
                            .then(ClientCommands.literal("forget")
                                    .executes(context -> {
                                        dev.custommodels.cmv.core.ItemFacts.reload();
                                        info("Read the item-facts document again.");
                                        return 1;
                                    })))

                    .then(ClientCommands.argument("search", StringArgumentType.greedyString())
                            .executes(context -> openExplorer(
                                    StringArgumentType.getString(context, "search")))));

            // ---- The catalogue -----------------------------------------------
            // Deliberately not requiring creative or an operator: the whole
            // point is customising items you already own while playing.
            dispatcher.register(ClientCommands.literal("catalogue")
                    .requires(FabricClientCommandSource::attended)
                    .executes(context -> openCatalogue()));

            dispatcher.register(ClientCommands.literal("cat")
                    .requires(FabricClientCommandSource::attended)
                    .executes(context -> openCatalogue()));

        });
    }


    private static int openExplorer(String search) {
        McCompat.setScreen(CatalogueScreen.browsing(search));
        return 1;
    }

    private static int openCatalogue() {
        McCompat.setScreen(new CatalogueScreen());
        return 1;
    }

    private static void info(String message) {
        McCompat.sendChat(Component.literal(message).withStyle(ChatFormatting.GRAY));
    }

}
