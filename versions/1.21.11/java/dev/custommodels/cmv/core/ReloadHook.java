package dev.custommodels.cmv.core;

import dev.custommodels.cmv.compat.McCompat;
import dev.custommodels.cmv.explorer.scan.ExplorerIndex;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * The same hook, for the Fabric API this version ships.
 *
 * <p>Replaces the shared {@link ReloadHook} when building for 1.21.11, because
 * this is a difference in shape rather than in spelling: here a reload listener
 * is one method and an id, where 26.x splits it into a prepare stage and an
 * apply stage. The mod sees no difference — {@code register()} either way — and
 * nothing else in the source has to know which version it is being built
 * for.</p>
 */
public final class ReloadHook {

    private ReloadHook() {
    }

    public static void register() {
        try {
            ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                    new SimpleSynchronousResourceReloadListener() {
                        @Override
                        public Identifier getFabricId() {
                            return McCompat.modId("explorer_invalidate");
                        }

                        @Override
                        public void onResourceManagerReload(ResourceManager manager) {
                            // A flag flip and a request, nothing more: doing real
                            // work inside a reload is what this hook exists to
                            // avoid.
                            ExplorerIndex.get().onResourceReload();
                            dev.custommodels.cmv.packs.PackFormatFix.requestCheck();
                        }
                    });
        } catch (RuntimeException | LinkageError e) {
            // Without the hook the catalogue still works, it just has to be
            // refreshed by hand after a pack change. Not worth failing startup.
            CmvMod.LOGGER.warn("Could not register resource reload hook", e);
        }
    }
}
