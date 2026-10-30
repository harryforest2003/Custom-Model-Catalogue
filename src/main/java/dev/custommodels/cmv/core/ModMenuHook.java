package dev.custommodels.cmv.core;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.custommodels.cmv.wrap.CatalogueScreen;

/**
 * Offers the settings page where Mod Menu looks for one.
 *
 * <h2>Why this is a separate class</h2>
 *
 * <p>It is the only thing in the mod that mentions Mod Menu, and Mod Menu is
 * compiled against and not depended on. Fabric loads an entrypoint class only
 * when something asks for that entrypoint, and the only thing that asks for
 * this one is Mod Menu itself — so with Mod Menu absent this class is never
 * loaded, never verified, and its missing supertypes never matter. Putting the
 * same two methods on an existing class would have made that class unloadable
 * without Mod Menu installed, which is most installs.</p>
 *
 * <h2>What it opens</h2>
 *
 * <p>The same settings page the sidebar shows, not a second interface onto the
 * same values. There is one page and two ways in: a second one written against
 * Mod Menu's own widgets would be a second place for a setting to be forgotten
 * when it was added.</p>
 *
 * <p>The screen Mod Menu hands over is the one to come back to, which is what
 * its {@code parent} is for. The catalogue screen has its own idea of where
 * closing goes, so the parent is passed to it and it is left to decide.</p>
 */
public final class ModMenuHook implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return CatalogueScreen::settings;
    }
}
