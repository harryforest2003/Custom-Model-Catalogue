package dev.custommodels.cmv.core;

import dev.custommodels.cmv.command.ModCommands;
import dev.custommodels.cmv.compat.McCompat;
import dev.custommodels.cmv.explorer.scan.ExplorerIndex;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint. This mod is client-only and does nothing on a server.
 *
 * <h2>What runs, and when</h2>
 *
 * <p>Startup registers key bindings, commands, one resource-reload hook and one
 * tick handler. That is the whole of the mod's permanent footprint: no file
 * watcher and no background thread until a viewer is actually opened.</p>
 *
 * <p>The tick handler is there for cosmetic skins that belong to one particular
 * item, which have to be told each tick which stack that is — see
 * the explorer. Nothing is read until a screen asks for it, and
 * a return, twenty times a second, which is as close to nothing as a registered
 * handler gets.</p>
 *
 * <p>The reload hook is deliberately the cheapest thing that will do the job.
 * It marks the explorer's cached results stale and releases the textures it was
 * holding; it does not scan. Reloading resources is already one of the slowest
 * things the client does, and a mod that piles its own work on top of it makes
 * every pack change and every F3+T feel worse. The next time the viewer opens,
 * it notices the packs have changed and rebuilds then, when the player is
 * waiting for exactly that.</p>
 */
public final class CmvMod implements ClientModInitializer {

    public static final String MOD_ID = "cmvcatalogue";
    public static final Logger LOGGER = LoggerFactory.getLogger("CustomModelViewer");

    @Override
    public void onInitializeClient() {
        CmvConfig.get();
        // Before any screen can draw, so the saved theme is the first one seen
        // rather than a frame of the default.
        CmvConfig.get().applyTheme();
        Keybinds.register();
        ModCommands.register();

        ReloadHook.register();

        // The one thing that does need a tick: noticing that the game has
        // switched every pack off after a failed reload, so the Home tab can
        // offer to put them back. It gives up immediately when nothing moved.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            PackHistory.tick();
            dev.custommodels.cmv.packs.PackFormatFix.tick();
        });

        // Deliberately no model scan on join. Joining a world is the worst
        // possible moment to spend a second walking resource packs on the
        // client thread, and nothing needs the results until a viewer opens.
        // The old behaviour did scan here; it was a real stall on join.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            // Leaving a world is a good moment to hand back anything the
            // explorer was still holding, in case a screen was closed oddly.
            ExplorerIndex.get().onResourceReload();
        });

        // A card taken off the screen writes on its own; this is for a
        // placement changed and then left there while the game was shut down,
        // which the client does not always route through a screen closing.
        ClientLifecycleEvents.CLIENT_STOPPING.register(
                client -> FigurePlacements.close());

        LOGGER.info("Custom Model Catalogue ready");
    }

    /**
     * Invalidates the explorer's caches when resource packs are reloaded.
     *
     * <p>This must happen: after a reload the previous {@code ResourceManager}
     * has closed its pack files, so anything still reading through it would
     * fail. Releasing here is also what stops preview textures from an old pack
     * lingering in video memory after the player switches packs.</p>
     */
}
