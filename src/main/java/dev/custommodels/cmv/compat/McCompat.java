package dev.custommodels.cmv.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import dev.custommodels.cmv.core.CmvMod;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ===========================================================================
 * THE PORTING LAYER. If this mod fails to compile, look here first.
 * ===========================================================================
 *
 * <p>This class is the <em>only</em> file in the mod that mentions
 * {@link Identifier}, and one of only two that touch volatile client APIs (the
 * other being {@code gui/GuiTheme}). Everything else works in plain strings.
 * That is deliberate: if a Minecraft version moves something, the blast radius
 * stays here.</p>
 *
 * <h2>Renames already applied for 26.2</h2>
 * <ul>
 *   <li>{@code ResourceLocation} became {@code Identifier} in the first
 *       unobfuscated build (25w45a), including in method names.</li>
 *   <li>{@code Minecraft.setScreen} moved to {@code Minecraft.gui.setScreen}
 *       in 26.2.</li>
 *   <li>{@code DataComponents.UNBREAKABLE} became a {@code Unit} in 1.21.5.</li>
 *   <li>Registry lookups moved from {@code get} to {@code getValue}.</li>
 * </ul>
 *
 * <h2>Still unverified</h2>
 * <p>The {@code Identifier} import below is the single most likely thing to be
 * wrong: the class was renamed for certain, but it may also have moved to
 * {@code net.minecraft.util} when utility classes were reorganised. If the
 * import fails, delete it, click the red symbol and press Alt/Option+Enter —
 * fixing it here fixes it everywhere, because nothing else imports it.</p>
 */
public final class McCompat {

    private McCompat() {
    }

    public static Minecraft mc() {
        return Minecraft.getInstance();
    }

    /** 26.2 moved screen management out of {@code Minecraft} and onto {@code Gui}. */
    public static void setScreen(Screen screen) {
        mc().gui.setScreen(screen);
    }

    public static void closeScreen() {
        setScreen(null);
    }

    public static ResourceManager resources() {
        return mc().getResourceManager();
    }

    public static Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    public static Path configDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("cmvcatalogue");
    }

    /**
     * Resource packs currently enabled, in load order. Minecraft reports user
     * packs as {@code file/Name.zip}; built-in sources such as {@code vanilla}
     * appear here too and are harmlessly skipped by the pack indexer, which
     * cannot find a file on disk for them.
     */
    public static List<String> selectedPackIds() {
        List<String> ids = new ArrayList<>();
        try {
            ids.addAll(mc().options.resourcePacks);
        } catch (RuntimeException e) {
            CmvMod.LOGGER.error("Failed to scan item definitions", e);
        }
        return ids;
    }

    /**
     * Turns a resource pack on and reloads, putting it above everything else.
     *
     * <h2>The mistake this exists to not repeat</h2>
     *
     * <p>The first version of this added the pack id to
     * {@code options.resourcePacks} and called {@code reloadResourcePacks}.
     * That does nothing. The 26.2 bytecode is unambiguous: the reload calls
     * {@code PackRepository.reload()} and then {@code openAllSelected()}, and
     * {@code reload()} rebuilds its selection from the repository's <em>own</em>
     * selected ids — it never reads the options list. So the reload ran with the
     * old selection, the generated pack was never opened, and the log showed a
     * pack that was enabled on paper and absent from every reload. The options
     * list is the saved copy of the selection, not the selection itself.</p>
     *
     * <p>Appended last, because later packs override earlier ones. That is the
     * one position from which a generated pack can settle a disagreement
     * between two others.</p>
     *
     * @return true when the selection changed and a reload was started
     */
    public static boolean enablePackOnTop(String packId) {
        try {
            var repository = mc().getResourcePackRepository();
            // Discover it: the folder was written moments ago and the repository
            // has not looked at the directory since.
            rediscoverKeepingSelection(repository);
            if (!repository.isAvailable(packId)) {
                CmvMod.LOGGER.warn("Pack {} is not on disk", packId);
                return false;
            }
            List<String> selected = new ArrayList<>(repository.getSelectedIds());
            boolean alreadyOnTop = !selected.isEmpty()
                    && selected.get(selected.size() - 1).equals(packId);
            if (alreadyOnTop) {
                return false;
            }
            selected.remove(packId);
            selected.add(packId);
            repository.setSelected(selected);
            saveSelection(repository.getSelectedIds());
            CmvMod.LOGGER.info("Enabled {} on top of {}", packId, selected);
            mc().reloadResourcePacks();
            return true;
        } catch (RuntimeException e) {
            CmvMod.LOGGER.warn("Could not enable {}", packId, e);
            return false;
        }
    }

    /**
     * Puts an exact selection back, dropping any pack that no longer exists.
     *
     * <p>For recovering from the client having emptied the list. Ids that are
     * gone are skipped rather than refused, because half a recovery beats
     * none, and vanilla is put back at the front if the snapshot somehow lost
     * it.</p>
     *
     * @return how many packs were applied
     */
    public static int applySelection(List<String> wanted) {
        try {
            var repository = mc().getResourcePackRepository();
            List<String> selected = new ArrayList<>();
            for (String id : wanted) {
                if (repository.isAvailable(id)) {
                    selected.add(id);
                } else {
                    CmvMod.LOGGER.warn("Not restoring {}: it is no longer there", id);
                }
            }
            if (selected.isEmpty()) {
                return 0;
            }
            if (!selected.contains("vanilla")) {
                selected.add(0, "vanilla");
            }
            repository.setSelected(selected);
            saveSelection(repository.getSelectedIds());
            CmvMod.LOGGER.info("Restored the pack selection: {}", selected);
            mc().reloadResourcePacks();
            return selected.size();
        } catch (RuntimeException e) {
            CmvMod.LOGGER.warn("Could not restore the pack selection", e);
            return 0;
        }
    }

    /** Turns a pack off, if it is on. Reloads only when something changed. */
    public static boolean disablePack(String packId) {
        try {
            var repository = mc().getResourcePackRepository();
            List<String> selected = new ArrayList<>(repository.getSelectedIds());
            if (!selected.remove(packId)) {
                return false;
            }
            repository.setSelected(selected);
            saveSelection(repository.getSelectedIds());
            CmvMod.LOGGER.info("Disabled {}", packId);
            mc().reloadResourcePacks();
            return true;
        } catch (RuntimeException e) {
            CmvMod.LOGGER.warn("Could not disable {}", packId, e);
            return false;
        }
    }

    /**
     * Writes the repository's selection back to options, so it survives a
     * restart. The repository is the truth while the game runs; this is only
     * the record of it.
     */
    private static void saveSelection(Collection<String> selectedIds) {
        mc().options.resourcePacks.clear();
        mc().options.resourcePacks.addAll(selectedIds);
        mc().options.save();
    }

    /**
     * Reloads resource packs without changing which are selected.
     *
     * <p>For when the files under an already-enabled pack have changed: the
     * selection is right, but the game is still holding the old contents. The
     * caller decides this is worth the stall, which with a large pack set is
     * tens of seconds.</p>
     */
    public static void reloadPacks() {
        try {
            mc().reloadResourcePacks();
        } catch (RuntimeException e) {
            CmvMod.LOGGER.warn("Could not reload resource packs", e);
        }
    }

    /**
     * A pack's own name for itself, from its {@code pack.mcmeta}.
     *
     * <p>Preferred to the file name, which is whatever the person who
     * downloaded it happened to call the zip. Falls back to the file name, and
     * then to the raw id, so this always returns something to show.</p>
     */
    /**
     * The client resource format this game wants, e.g. 88 for 26.2.
     *
     * <p>The major only. A pack declaring the major is compatible with every
     * minor of it — {@code max_format: 88} covers 88.*, which is what a pack
     * that has not been tested against a specific minor should say.</p>
     */
    /**
     * Re-reads the resourcepacks folder without reloading resources.
     *
     * <p>Enough for a pack whose metadata changed while it was not loaded: the
     * repository forgets what it read and looks again, so the pack screen stops
     * describing it as broken. A full resource reload would be the heavier
     * answer to a question nothing has asked yet — the pack is still off.</p>
     */
    public static void rediscoverPacks() {
        try {
            rediscoverKeepingSelection(mc().getResourcePackRepository());
        } catch (RuntimeException e) {
            CmvMod.LOGGER.warn("Could not re-read the resource pack folder", e);
        }
    }

    /**
     * Re-reads the folder without losing anything that was switched on.
     *
     * <h2>The fault this exists to prevent</h2>
     *
     * <p>{@code PackRepository.reload()} rebuilds its selection through
     * {@code rebuildSelected}, which keeps only the ids it can still find — read
     * out of the 26.2 bytecode, where the old selection is mapped to ids and
     * passed through {@code getAvailablePacks}. Anything unreadable at that
     * instant is dropped silently, and a pack this mod has just rewritten, or
     * one the operating system has not finished flushing, is exactly that. The
     * player then finds their packs turned off, having asked for nothing.</p>
     *
     * <p>So the selection is taken first and put back afterwards: every id that
     * survives discovery is restored in its original order, and only ids that
     * genuinely no longer exist are allowed to fall out. Nothing is saved to
     * disk here — the caller decides that — so a pack briefly unreadable is not
     * written out of the options file either.</p>
     */
    private static void rediscoverKeepingSelection(
            net.minecraft.server.packs.repository.PackRepository repository) {
        List<String> before = new ArrayList<>(repository.getSelectedIds());
        repository.reload();
        List<String> after = new ArrayList<>(repository.getSelectedIds());
        if (after.containsAll(before)) {
            return;
        }
        List<String> restored = new ArrayList<>(before.size());
        for (String id : before) {
            if (repository.isAvailable(id)) {
                restored.add(id);
            } else {
                CmvMod.LOGGER.warn("Resource pack {} is gone; leaving it off", id);
            }
        }
        // Anything discovery added that was not selected before is left alone.
        for (String id : after) {
            if (!restored.contains(id)) {
                restored.add(id);
            }
        }
        if (!restored.equals(after)) {
            CmvMod.LOGGER.info("Keeping {} resource packs that a re-read dropped",
                    restored.size() - after.size());
            repository.setSelected(restored);
        }
    }

    /**
     * Vanilla's own copy of every interface file a resource pack has replaced.
     *
     * <p>Keyed by archive path, so the result drops straight into a generated
     * pack. A pack sitting above the others then puts the game's own buttons,
     * hotbar and bars back without anything being taken out of the pack that
     * replaced them — which matters, because that pack is loaded and its
     * archive is open.</p>
     *
     * <p>Only files vanilla itself ships are considered: the bottom of the
     * stack has to be the built-in pack, or there is nothing to restore and the
     * file is the pack's own to provide. Any sidecar {@code .mcmeta} travels
     * with it, since a GUI sprite's nine-slice scaling lives there and a
     * texture without it is stretched rather than tiled.</p>
     */
    public static java.util.Map<String, byte[]> vanillaInterfaceFiles() {
        java.util.Map<String, byte[]> out = new java.util.LinkedHashMap<>();
        try {
            var stacks = resources().listResourceStacks("textures/gui",
                    id -> id.getNamespace().equals("minecraft"));
            for (var entry : stacks.entrySet()) {
                List<Resource> stack = entry.getValue();
                if (stack.size() < 2) {
                    continue;
                }
                Resource vanilla = stack.get(0);
                Resource winner = stack.get(stack.size() - 1);
                if (vanilla.sourcePackId().startsWith("file/")
                        || !winner.sourcePackId().startsWith("file/")) {
                    // Nothing underneath from the game itself, or nothing above
                    // from a pack: either way there is nothing to put back.
                    continue;
                }
                Identifier id = entry.getKey();
                String path = "assets/" + id.getNamespace() + "/" + id.getPath();
                try (java.io.InputStream in = vanilla.open()) {
                    out.put(path, in.readAllBytes());
                }
                copyMeta(id, path, out);
            }
        } catch (java.io.IOException | RuntimeException e) {
            CmvMod.LOGGER.warn("Could not read the game's own interface files", e);
            return java.util.Map.of();
        }
        return out;
    }

    /** Brings a texture's sidecar along, when the game ships one. */
    private static void copyMeta(Identifier id, String path,
                                 java.util.Map<String, byte[]> out) {
        Identifier meta = Identifier.fromNamespaceAndPath(
                id.getNamespace(), id.getPath() + ".mcmeta");
        List<Resource> stack = resourceStack(meta);
        if (stack.isEmpty()) {
            return;
        }
        try (java.io.InputStream in = stack.get(0).open()) {
            out.put(path + ".mcmeta", in.readAllBytes());
        } catch (java.io.IOException | RuntimeException e) {
            CmvMod.LOGGER.debug("No readable sidecar for {}", id, e);
        }
    }

    /** The language the game is set to, and en_us behind it as the fallback. */
    public static List<String> activeLanguageCodes() {
        List<String> codes = new ArrayList<>(2);
        try {
            String selected = mc().getLanguageManager().getSelected();
            if (selected != null && !selected.isEmpty()) {
                codes.add(selected);
            }
        } catch (RuntimeException e) {
            // Before the language manager exists there is still a fallback.
        }
        if (!codes.contains("en_us")) {
            codes.add("en_us");
        }
        return codes;
    }

    /**
     * Every pack's version of one language file, lowest first.
     *
     * <p>Lowest is vanilla, which is what makes this useful: it is the only
     * place the game's own wording can be read back once a pack has replaced
     * it. Unreadable layers are skipped rather than failing the lot — a pack
     * with a malformed language file should cost its own strings and nothing
     * else.</p>
     */
    public static List<java.util.Map<String, String>> languageLayers(String code) {
        List<java.util.Map<String, String>> layers = new ArrayList<>();
        Identifier id = Identifier.fromNamespaceAndPath("minecraft", "lang/" + code + ".json");
        for (Resource resource : resourceStack(id)) {
            try (java.io.Reader reader = resource.openAsReader()) {
                var object = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
                java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
                for (var entry : object.entrySet()) {
                    if (entry.getValue().isJsonPrimitive()) {
                        values.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
                layers.add(values);
            } catch (java.io.IOException | RuntimeException e) {
                CmvMod.LOGGER.debug("Could not read {} from a pack", id, e);
            }
        }
        return layers;
    }

    public static int currentPackFormat() {
        try {
            return net.minecraft.SharedConstants.getCurrentVersion()
                    .packVersion(net.minecraft.server.packs.PackType.CLIENT_RESOURCES)
                    .major();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /**
     * File names of the packs the game refused for their declared format.
     *
     * <p>Only {@code file/} packs, which are the ones in the resourcepacks
     * folder — the built-in and mod-supplied sources have no file to repair and
     * are not the player's to change. A refused pack is also one the game has
     * not opened, which is what makes it safe to rewrite.</p>
     */
    public static List<String> incompatiblePackFiles() {
        List<String> out = new ArrayList<>();
        try {
            for (var pack : mc().getResourcePackRepository().getAvailablePacks()) {
                String id = pack.getId();
                if (id == null || !id.startsWith("file/")
                        || pack.getCompatibility().isCompatible()) {
                    continue;
                }
                String name = id.substring("file/".length());
                if (name.endsWith(".zip")) {
                    out.add(name);
                }
            }
        } catch (RuntimeException e) {
            return List.of();
        }
        return out;
    }

    public static String packDisplayName(String packId) {
        try {
            var pack = mc().getResourcePackRepository().getPack(packId);
            if (pack != null) {
                String title = pack.getTitle().getString().trim();
                if (!title.isEmpty()) {
                    return title;
                }
            }
        } catch (RuntimeException e) {
            // Falls through to the file name below.
        }
        String name = packId.startsWith("file/") ? packId.substring(5) : packId;
        return name.endsWith(".zip") ? name.substring(0, name.length() - 4) : name;
    }

    /**
     * A pack's file name, without the folder or the extension.
     *
     * <p>What the player calls it. Preferred to the pack's own title where
     * space is tight: a title may be several lines long and full of colour
     * codes — one real pack calls itself
     * {@code §4❤ §c§lLOVERFELLA§r §b§lNETWORK§r §4❤\nOfficial Server Resource Pack}
     * — and "lf skins" is what its file is called and what its owner says.</p>
     */
    public static String packFileName(String packId) {
        if (packId == null || packId.isEmpty()) {
            return "";
        }
        String name = packId.startsWith("file/") ? packId.substring(5) : packId;
        return name.endsWith(".zip") ? name.substring(0, name.length() - 4) : name;
    }

    /** Every pack's own version of one resource, lowest priority first. */
    public static List<Resource> resourceStack(Identifier id) {
        try {
            return resources().getResourceStack(id);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * Every item definition, with <em>every</em> pack's version of it rather
     * than only the one that won.
     *
     * <p>This is what makes merging possible without opening a single zip: the
     * resource manager already keeps each pack's copy, and each one knows which
     * pack it came from. An entry with more than one user pack in it is a file
     * two packs disagree about.</p>
     */
    public static Map<Identifier, List<Resource>> itemDefinitionStacks() {
        try {
            return resources().listResourceStacks("items", id -> id.getPath().endsWith(".json"));
        } catch (RuntimeException e) {
            CmvMod.LOGGER.error("Failed to list item definition stacks", e);
            return Map.of();
        }
    }

    /**
     * One item definition file, flattened to plain strings so that the scanner
     * never has to name a Minecraft type.
     *
     * @param namespace e.g. {@code minecraft}
     * @param path      e.g. {@code items/brown_dye.json}
     * @param id        e.g. {@code minecraft:items/brown_dye.json}
     */
    public record ItemDefinition(String namespace, String path, String id, Resource resource) {

        /** The archive path this file occupies inside the pack. */
        public String archivePath() {
            return "assets/" + namespace + "/" + path;
        }

        /** The base item id, e.g. {@code minecraft:brown_dye}. */
        public String baseItemId() {
            String p = path;
            if (p.startsWith("items/")) {
                p = p.substring("items/".length());
            }
            if (p.endsWith(".json")) {
                p = p.substring(0, p.length() - ".json".length());
            }
            return namespace + ":" + p;
        }

        public String sourcePackId() {
            try {
                return resource.sourcePackId();
            } catch (RuntimeException e) {
                return "unknown";
            }
        }
    }

    /** Lists every item definition JSON visible to the resource manager. */
    public static List<ItemDefinition> listItemDefinitions() {
        return listItemDefinitions(resources());
    }

    /**
     * Lists item definitions from a specific resource manager.
     *
     * <p>The scanner captures a manager once and passes it here, so that a
     * resource reload part-way through a scan cannot make the scan read half
     * of one pack set and half of another. Safe to call from a worker thread:
     * a resource manager is built during reload and only read afterwards.</p>
     */
    public static List<ItemDefinition> listItemDefinitions(ResourceManager manager) {
        List<ItemDefinition> out = new ArrayList<>();
        try {
            Map<Identifier, Resource> found =
                    manager.listResources("items", id -> id.getPath().endsWith(".json"));
            for (Map.Entry<Identifier, Resource> entry : found.entrySet()) {
                Identifier id = entry.getKey();
                out.add(new ItemDefinition(
                        id.getNamespace(), id.getPath(), id.toString(), entry.getValue()));
            }
        } catch (RuntimeException e) {
            CmvMod.LOGGER.error("Failed to list item definitions", e);
        }
        return out;
    }

    /**
     * Every tag on an item, as plain id strings.
     *
     * <p>Item tags are the game's own answer to "what kind of thing is this",
     * which makes them the right source for the explorer's type filter. They
     * are bound during registry load and are empty before a world has been
     * entered, so callers must cope with an empty set rather than assume one.</p>
     *
     * <p>Returns strings so that the classification rules can live in the
     * explorer's Minecraft-free graph package.</p>
     */
    public static java.util.Set<String> tagsOf(String itemId) {
        Item item = itemById(itemId).orElse(null);
        if (item == null) {
            return java.util.Set.of();
        }
        try {
            java.util.Set<String> tags = new java.util.LinkedHashSet<>();
            // Via the registry rather than Item.builtInRegistryHolder(), which
            // 26.2 deprecates.
            BuiltInRegistries.ITEM.wrapAsHolder(item).tags()
                    .forEach(tag -> tags.add(tag.location().toString()));
            return tags;
        } catch (RuntimeException e) {
            // Tags are unbound before a world loads; that is not an error.
            return java.util.Set.of();
        }
    }

    /**
     * A type read from an item's data components, for items no tag covers.
     * Components are present from registry load, so this works on the title
     * screen where tags do not.
     */
    public static dev.custommodels.cmv.explorer.graph.ItemType typeFromComponents(String itemId) {
        Item item = itemById(itemId).orElse(null);
        if (item == null) {
            return null;
        }
        try {
            var components = item.components();
            if (components.has(DataComponents.FOOD) || components.has(DataComponents.CONSUMABLE)) {
                return dev.custommodels.cmv.explorer.graph.ItemType.FOOD;
            }
            if (components.has(DataComponents.EQUIPPABLE)) {
                return dev.custommodels.cmv.explorer.graph.ItemType.ARMOUR;
            }
            if (components.has(DataComponents.TOOL)) {
                return dev.custommodels.cmv.explorer.graph.ItemType.TOOL;
            }
            if (components.has(DataComponents.WEAPON)) {
                return dev.custommodels.cmv.explorer.graph.ItemType.SWORD;
            }
            if (item instanceof net.minecraft.world.item.BlockItem) {
                return dev.custommodels.cmv.explorer.graph.ItemType.BLOCK;
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    /**
     * Opens Minecraft's own resource pack screen, returning to {@code returnTo}
     * when it closes.
     *
     * <p>Vanilla's screen is used rather than a copy of it. Choosing and
     * ordering packs is a job the game already does properly, including the
     * reload that has to follow, and a mod's reimplementation would only be a
     * worse version that drifts out of date.</p>
     */
    public static void openPackScreen(Screen returnTo) {
        try {
            setScreen(new net.minecraft.client.gui.screens.packs.PackSelectionScreen(
                    mc().getResourcePackRepository(),
                    repository -> {
                        mc().reloadResourcePacks();
                        setScreen(returnTo);
                    },
                    gameDir().resolve("resourcepacks"),
                    Component.literal("Resource Packs")));
        } catch (RuntimeException | LinkageError e) {
            CmvMod.LOGGER.warn("Could not open the resource pack screen", e);
        }
    }

    /**
     * Whether an id names an item that actually exists in the registry.
     *
     * <p>This matters more than it looks. An item definition at
     * {@code assets/minecraft/items/bow.json} describes the vanilla bow. One at
     * {@code assets/lf/items/count_heart_of_the_sea.json} describes no item at
     * all: since 1.21.4 a definition in any other namespace is addressed by an
     * item's {@code minecraft:item_model} component, and which item carries
     * that component is decided by the server, not by the pack. Claiming
     * {@code lf:count_heart_of_the_sea} as a "base item" would be inventing a
     * fact, so the explorer checks first and says "referenced by item_model"
     * instead.</p>
     */
    public static boolean itemExists(String id) {
        return itemById(id).isPresent();
    }

    /**
     * The window's GUI scale.
     *
     * <p>Needed because a preview is rendered to an image and then blitted into
     * a box measured in GUI pixels. At scale 3 an 80-pixel box covers 240 real
     * pixels, so rendering at 80 and letting the blit stretch it is a threefold
     * upscale — which is exactly why the previews looked soft.</p>
     */
    public static int guiScale() {
        try {
            return Math.max(1, mc().getWindow().getGuiScale());
        } catch (RuntimeException e) {
            return 2;
        }
    }

    /**
     * The pixel size to render a preview at so it lands sharp in a box of the
     * given width in GUI pixels.
     *
     * @param cap upper bound, so a huge window cannot ask for a huge render
     */
    public static int previewPixels(int guiPixels, int cap) {
        return Math.max(16, Math.min(cap, guiPixels * guiScale()));
    }

    /** True when a resource pack id refers to a pack the player installed. */
    public static boolean isUserPack(String packId) {
        return packId != null && packId.startsWith("file/");
    }

    /** Builds an identifier for this mod's namespace, e.g. attribute modifier ids. */
    public static Identifier modId(String path) {
        return Identifier.fromNamespaceAndPath("cmvcatalogue", path);
    }

    public static Optional<Item> itemById(String id) {
        try {
            Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
            return item == null ? Optional.empty() : Optional.of(item);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Builds the stack needed to preview one custom model: the base item
     * carrying a {@code custom_model_data} component whose string list holds
     * the select-case value.
     *
     * <p>Note for 26.1 and later: an {@code ItemStack} cannot be constructed
     * before a world is loaded, hence the guard. Callers are all GUI screens,
     * so this only bites if something scans during startup.</p>
     */
    public static ItemStack buildStack(String baseItemId, String customModelData) {
        if (mc().level == null) {
            return ItemStack.EMPTY;
        }
        Item item = itemById(baseItemId).orElse(null);
        if (item == null) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(item);
        if (customModelData != null && !customModelData.isEmpty()) {
            stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(
                    List.of(), List.of(), List.of(customModelData), List.of()));
        }
        return stack;
    }

    /** The local player's name, or empty when there is no player. */
    public static String playerName() {
        try {
            return mc().player == null ? "" : mc().player.getGameProfile().name();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** True when the player may fabricate items, i.e. creative mode. */
    public static boolean canGiveItems() {
        // Reading the ability flag rather than calling isCreative(): fields
        // survive the method renames that keep catching us out, and
        // instabuild is precisely what "can fabricate items" means.
        return mc().player != null && mc().player.getAbilities().instabuild;
    }

    /**
     * Writes a stack into an inventory slot using the creative-mode packet.
     *
     * <p>This is the only route by which a client-side mod can produce a real
     * item, and it works in creative alone. Outside creative the stack would
     * exist only on the client and vanish at the next sync, so callers check
     * {@link #canGiveItems()} and fall back to copying a give command.</p>
     *
     * @param containerSlot slot in container space: 9-35 main inventory,
     *                      36-44 hotbar
     */
    public static boolean setCreativeSlot(ItemStack stack, int containerSlot) {
        if (!canGiveItems()) {
            return false;
        }
        try {
            mc().gameMode.handleCreativeModeItemAdd(stack.copy(), containerSlot);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Puts a stack in the first free inventory slot.
     *
     * <p>Prefers the hotbar, then the main inventory, and only falls back to
     * the held slot when there is nowhere free — overwriting whatever the
     * player is holding is the behaviour this exists to avoid.</p>
     *
     * @return the inventory slot used, or -1 if the item could not be given
     */
    public static int giveToFreeSlot(ItemStack stack) {
        if (!canGiveItems() || stack.isEmpty() || mc().player == null) {
            return -1;
        }
        var inventory = mc().player.getInventory();
        int target = -1;
        for (int slot = 0; slot < 36; slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                target = slot;
                break;
            }
        }
        if (target < 0) {
            target = inventory.getSelectedSlot();
        }
        inventory.setItem(target, stack.copy());
        // Container space: the hotbar is 36-44 and the main inventory 9-35,
        // which is not the order the inventory itself uses.
        int containerSlot = target < 9 ? 36 + target : target;
        return setCreativeSlot(stack, containerSlot) ? target : -1;
    }


    public static void copyToClipboard(String text) {
        mc().keyboardHandler.setClipboard(text);
    }

    public static void sendChat(Component message) {
        if (mc().player != null) {
            mc().player.sendSystemMessage(message);
        }
    }

    public static void playClick() {
        try {
            mc().getSoundManager().play(
                    net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                            net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 1.0F));
        } catch (RuntimeException e) {
            // Sound is cosmetic; never let it break a screen.
        }
    }
}
