package dev.custommodels.cmv.core;

import dev.custommodels.cmv.compat.McCompat;
import dev.custommodels.cmv.explorer.scan.ExplorerIndex;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.reloader.SimpleReloadListener;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;

/**
 * Notices when the player's resource packs have changed.
 *
 * <h2>Why this is its own file</h2>
 *
 * <p>It is the one thing in this mod that a Minecraft version can change the
 * <em>shape</em> of rather than the spelling. Fabric API reworked resource
 * reloading: 26.x has {@code ResourceLoader} and a {@code SimpleReloadListener}
 * with prepare and apply stages, while 1.21.x has {@code ResourceManagerHelper}
 * and a listener with an id and a single reload method. No rename bridges
 * that.</p>
 *
 * <p>So it lives alone, small enough that a version can supply its own copy —
 * see {@code versions/<version>/java} — without duplicating anything else.
 * Everything around it is shared.</p>
 */
public final class ReloadHook {

    private ReloadHook() {
    }

    public static void register() {

        try {
            // PORTING NOTE: Fabric API 26.x deprecated ResourceManagerHelper and
            // SimpleSynchronousResourceReloadListener in favour of the
            // resource.v1 package used here.
            ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                    McCompat.modId("explorer_invalidate"),
                    new SimpleReloadListener<Void>() {
                        @Override
                        protected Void prepare(PreparableReloadListener.SharedState state) {
                            // Nothing to prepare. The whole hook is a flag flip;
                            // doing real work during a reload is precisely what
                            // this feature avoids.
                            return null;
                        }

                        @Override
                        protected void apply(Void prepared,
                                             PreparableReloadListener.SharedState state) {
                            ExplorerIndex.get().onResourceReload();
                            dev.custommodels.cmv.packs.PackFormatFix.requestCheck();
                            // Only a request. Rebuilding the unified pack means
                        }
                    });
        } catch (RuntimeException | LinkageError e) {
            // Without the hook the explorer still works, it just has to be
            // refreshed by hand after a pack change. Not worth failing startup.
            CmvMod.LOGGER.warn("Could not register resource reload hook", e);
        }
    }
}
