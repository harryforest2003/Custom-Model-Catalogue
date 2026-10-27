package dev.custommodels.cmv.core;

import com.mojang.blaze3d.platform.InputConstants;
import dev.custommodels.cmv.compat.McCompat;
import dev.custommodels.cmv.wrap.CatalogueScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/**
 * Registers key bindings.
 *
 * <p>Using Fabric's key mapping helper rather than a custom input hook is what
 * makes these appear in Options -> Controls -> Key Binds, grouped under their
 * own category and remappable like any vanilla control.</p>
 *
 * <p>PORTING NOTE: this file changed shape in 26.1. Fabric API renamed
 * {@code keybinding.v1.KeyBindingHelper} to {@code keymapping.v1.KeyMappingHelper}
 * and {@code registerKeyBinding} to {@code registerKeyMapping}; separately,
 * Minecraft turned the key category from a bare translation string into a
 * registered {@code KeyMapping.Category} object.</p>
 */
public final class Keybinds {

    /**
     * Registered in {@link #register()} rather than in a field initialiser, so
     * that registration happens at a controlled point during client startup
     * instead of whenever this class first happens to be loaded.
     */
    public static KeyMapping.Category category;

    public static KeyMapping openScreen;

    private Keybinds() {
    }

    /**
     * The key that opens the screen, as the player has it bound.
     *
     * <p>Read live rather than hardcoded, so the instructions on the home page
     * stay true for someone who has rebound it. Falls back to the default if
     * asked before registration.</p>
     */
    public static String openKeyName() {
        if (openScreen == null) {
            return "M";
        }
        if (openScreen.isUnbound()) {
            return "the key you bind in Options → Controls";
        }
        return openScreen.getTranslatedKeyMessage().getString();
    }

    public static void register() {
        // Translation key derived from this is
        // "key.category.<MOD_ID>.main", which the lang file has to match: when
        // the mod was given its own namespace this key moved with it, and the
        // category showed up in Options as the raw key until the lang file
        // caught up.
        category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(CmvMod.MOD_ID, "main"));

        // One key, because there is now one screen. The explorer and the
        // catalogue used to be separate and had a key each; merging them into tabs
        // left two bindings opening the same window on different tabs, which is
        // a keybind to learn for something a click already does. M is kept
        // because it is the one already in people's hands.
        openScreen = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.cmv.open_screen",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_M,
                category));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) {
                return;
            }
            while (openScreen.consumeClick()) {
                // Opens on the home page: the key carries no intent with it, so
                // it lands on the page that explains what the mod is for and
                // reports whether the packs read cleanly. The catalogue is one
                // click away along the top.
                McCompat.setScreen(CatalogueScreen.home());
            }
        });
    }
}
