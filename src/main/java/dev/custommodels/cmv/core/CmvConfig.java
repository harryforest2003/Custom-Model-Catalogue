package dev.custommodels.cmv.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.custommodels.cmv.compat.McCompat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** User settings, stored as JSON in {@code config/cmv/config.json}. */
public final class CmvConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static CmvConfig instance;

    /**
     * Bumped when a default changes in a way an existing file would mask.
     *
     * <p>Gson keeps whatever is already in the file, so lowering a default does
     * nothing for anyone who has run the mod before — which is everyone whose
     * settings need the new value most. On a version bump the preview settings
     * are reset once and the file rewritten.</p>
     */
    private static final int CURRENT_VERSION = 4;

    /** Version of the defaults this file was written against. */
    public int configVersion = 0;


    /**
     * List models whose file or textures are missing, with warnings attached.
     *
     * <p>On by default, which is the opposite of what a browser would want but
     * the right answer for an inspector: a model that a pack references and
     * does not ship is exactly the thing someone opens this to find. Set it to
     * false to see only models that actually render.</p>
     */
    public boolean showBrokenEntries = true;

    /**
     * Ceiling on how many models are kept after a scan.
     *
     * <p>A backstop, not a tuning knob: a pack big enough to reach it would
     * otherwise take the game down with it, and a truncated list the player can
     * actually browse beats a crash. The count is reported when it bites.</p>
     */
    public int maxModels = 120000;




    // ------------------------------------------------------ model explorer

    /**
     * Allow the low-confidence filename pass to group models that the item
     * definitions left separate. Groups it forms are always marked as guesses.
     */
    public boolean groupByFilename = true;

    /**
     * Slowly rotate the preview on each visible card.
     *
     * <p>Off by default. A rotation is a strip of pre-rendered angles, so a
     * spinning card costs a dozen renders where a still costs one. With
     * previews now rendered at the display's own resolution that turned a grid
     * of forty cards into several hundred full-size renders, which is what made
     * half of them appear never to load. The detail view still rotates.</p>
     */
    public boolean animateCards = false;

    /**
     * Largest card preview render, in real pixels.
     *
     * <p>The actual size is the card's box multiplied by the GUI scale, capped
     * here. It is a cap rather than a size because the right value depends on
     * the window, and guessing it once at 48 pixels is what made every preview
     * a blurry upscale.</p>
     */
    public int maxCardPixels = 160;

    /**
     * Largest detail preview render, in real pixels. Only one exists at a time.
     *
     * <p>288 rather than 384: measured across three hundred models from a real
     * pack, 384 costs 3.38 ms a frame against 1.96 ms at 288, and the
     * difference is not visible once the image is scaled into the panel.</p>
     */
    public int maxDetailPixels = 288;

    /**
     * The detail view's render size once the view stops moving.
     *
     * <p>{@link #maxDetailPixels} is what a <em>moving</em> preview is allowed,
     * and it has to be small because a spin asks for a fresh render every three
     * degrees. Standing still asks for one render and then nothing, so it can
     * afford to be rendered at the size it is actually displayed at — which is
     * the whole difference between a detailed model looking detailed and
     * looking like porridge. At a GUI scale of four the old cap was being blown
     * up about four times.</p>
     *
     * <p>1280 rather than "no limit". The limit is not the render, which
     * happens once on a worker; it is that the rasterizer keeps its scratch
     * buffers per thread and grows them to the largest frame that thread has
     * ever drawn. At 2048 that is about forty megabytes a worker, held for the
     * rest of the session. 1280 covers a one-to-one render of the panel at any
     * ordinary window and scale, and costs about fifteen.</p>
     */
    public int maxStillPixels = 1280;

    /** Automatically rotate the model in the detail view. */
    public boolean autoRotateDetail = true;

    /**
     * Show the detail view's model on your own figure rather than on its own.
     *
     * <p>Off, so the card opens on the render it has always opened on. Kept
     * here beside the other two detail-view switches rather than per card: the
     * question a player is answering is "how do I want to look at models", and
     * made per card it would be a switch they had to flip again on every model
     * they opened.</p>
     */
    public boolean figureInDetail = false;

    /**
     * Play a model's frames in the detail view: a bow drawing, a crossbow
     * loading, a rod casting.
     *
     * <p>On. It costs one render per frame, once — the same renders the state
     * buttons already produce when clicked, and cached the same way — so a
     * model that has been through its cycle once replays it for nothing.
     * Clicking a state button stops it, on the principle that a control the
     * player just used should not be overridden a second later.</p>
     */
    public boolean cycleDetailStates = true;

    /**
     * Play animated textures in the detail view: fire, shimmer, glowing gems.
     *
     * <p>On. It only affects a model that actually has one — 8,613 of the
     * textures in the packs this was built against declare an animation — and
     * only the single preview the detail view is showing. The grid draws
     * stills, deliberately: a page of forty animating cards is forty times the
     * work for a picture the size of a thumbnail.</p>
     */
    public boolean animateDetailTextures = true;

    /**
     * Frames in an exported animation, which is its smoothness and its size.
     *
     * <p>Smoothness only, now that the speed is set separately: the frames are
     * spread over a full turn, so this is how many degrees each step is worth
     * — 72 of them is five degrees apiece. It used to decide the speed as
     * well, because the delay written into the file was fixed, so asking for a
     * smoother animation produced a slower one and asking for a smaller file
     * produced a blur.</p>
     */
    public int exportFrames = 72;

    /**
     * How long an exported animation takes to turn right round, in seconds.
     *
     * <p>Eight. The detail view itself turns once every twenty, which is the
     * right speed to study something at and too slow for a file somebody is
     * going to watch loop; the export used to take two, which is ten times the
     * view's speed.</p>
     *
     * <p>Duration is free, unlike smoothness: a longer animation is the same
     * number of frames held for longer, so the file is not one byte bigger.
     * Only the frame count costs anything.</p>
     */
    public float exportSpinSeconds = 8.0F;

    /** Pixel size of exported images and animations. */
    public int exportSize = 256;

    /**
     * How tightly the model grids are packed.
     *
     * <p>The old fixed card size showed ten models at a time on a
     * 1080p screen at GUI scale 3, which is not a useful way to look at a pack
     * of fifty thousand. Each step trades a line of text for more cards.</p>
     */
    public String gridDensity = Density.COMPACT.name();

    /** Card geometry for each density, and how much text fits on one. */
    public enum Density {
        /** Preview, name, category and base item. */
        COMFORTABLE("Comfortable", 92, 128, 3),
        /**
         * Preview and name.
         *
         * <p>Sized so that on a 1080p screen at GUI scale 3 the catalogue fits
         * three rows of cards above the hotbar, or two above the full
         * inventory — which is the pair of layouts the interface offers.</p>
         */
        COMPACT("Compact", 56, 64, 1),
        /** Preview only; the name is in the tooltip. */
        DENSE("Dense", 44, 48, 0);

        private final String label;
        public final int cardWidth;
        public final int cardHeight;
        /** Lines of text drawn under the preview. */
        public final int textLines;

        Density(String label, int cardWidth, int cardHeight, int textLines) {
            this.label = label;
            this.cardWidth = cardWidth;
            this.cardHeight = cardHeight;
            this.textLines = textLines;
        }

        public String label() {
            return label;
        }

        public Density next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    public Density density() {
        try {
            return Density.valueOf(gridDensity);
        } catch (IllegalArgumentException e) {
            return Density.COMPACT;
        }
    }

    public void setDensity(Density value) {
        this.gridDensity = value.name();
        save();
    }

    /**
     * Which colour theme the mod's screens draw in.
     *
     * <p>Stored by name rather than as the enum so an unknown value from a
     * newer build, or a hand-edited file, falls back to the default instead of
     * failing to parse the whole config.</p>
     */
    public String guiPalette = dev.custommodels.cmv.gui.GuiTheme.Palette.PINK.name();

    public dev.custommodels.cmv.gui.GuiTheme.Palette palette() {
        return dev.custommodels.cmv.gui.GuiTheme.Palette.of(guiPalette);
    }

    public void setPalette(dev.custommodels.cmv.gui.GuiTheme.Palette value) {
        this.guiPalette = value.name();
        this.customTheme = false;
        dev.custommodels.cmv.gui.GuiTheme.apply(value);
        save();
    }

    /**
     * What each namespace last cost in the item atlas.
     *
     * <p>Remembered because a namespace that has been taken out of the packs
     * contributes nothing and so cannot be measured any more — and its old
     * cost is exactly what somebody deciding whether to put it back wants to
     * know.</p>
     */
    public java.util.Map<String, Long> namespacePixels = new java.util.HashMap<>();

    /**
     * Recent resource pack selections, newest first.
     *
     * <p>Kept so the client's habit of switching every pack off after a failed
     * reload can be undone. Written rarely: only when the selection actually
     * changes, and never when it holds fewer than two entries, which is what it
     * looks like immediately after the thing this guards against.</p>
     */
    public java.util.List<java.util.List<String>> packHistory = new java.util.ArrayList<>();

    /**
     * The two colours the theme is built from.
     *
     * <p>Primary is the accent — selection, focus, the active chip. Secondary
     * chooses the hue the panels, cards and inventory are darkened from. Stored
     * as plain integers so the file stays readable and a value out of range
     * cannot stop the rest of the config parsing.</p>
     */
    public boolean customTheme = false;
    public int guiPrimary = 0xFF2D95;
    public int guiSecondary = 0x8B5CF6;

    /** Puts the saved appearance into effect, whichever kind it is. */
    public void applyTheme() {
        if (customTheme) {
            dev.custommodels.cmv.gui.GuiTheme.applyCustom(guiPrimary, guiSecondary);
        } else {
            dev.custommodels.cmv.gui.GuiTheme.apply(palette());
        }
    }

    public void setCustomTheme(int primary, int secondary) {
        this.guiPrimary = primary & 0xFFFFFF;
        this.guiSecondary = secondary & 0xFFFFFF;
        this.customTheme = true;
        dev.custommodels.cmv.gui.GuiTheme.applyCustom(guiPrimary, guiSecondary);
        save();
    }

    /**
     * Whether a card applies its appearance or opens its details.
     *
     * <p>One switch for the whole interface rather than a pair of tabs. The
     * grid is the same grid either way — the same models, the same filters,
     * the same page — and which of the two things a click does is a mood the
     * player is in, not a place they navigate to. Kept between sessions
     * because it is a preference, not a position.</p>
     */
    public boolean applyMode = true;

    /** Ask before applying a wrap. Off makes browsing quicker. */
    public boolean confirmWraps = true;

    /** Show the model-resolution debug section in the detail view. */
    public boolean debugResolution = false;

    /**
     * Whether the pack-health page is offered in the sidebar.
     *
     * <p>Off, because it is not a page a player can act on: it lists models
     * their packs do not ship, which is somebody else's bug. Whoever built the
     * pack can act on it, and they are the one who will turn this on.</p>
     */
    public boolean packHealth = false;

    /**
     * Packs the scanner is to leave alone, by pack id — {@code vanilla},
     * {@code file/Some Pack.zip}.
     *
     * <p>Every model found has to be parsed, resolved, grouped and named, and a
     * player who is only ever going to skin swords pays for the four thousand
     * definitions in a pack they are not interested in. Excluding one makes the
     * scan shorter and the grid shorter, and nothing else: the pack stays
     * enabled and the game keeps drawing everything in it exactly as before.
     * This is about what the mod looks at, not about what Minecraft loads.</p>
     */
    public java.util.List<String> ignoredPacks = new java.util.ArrayList<>();

    /**
     * Whether the unified pack rebuilds itself when the pack set changes.
     *
     * <p>On, because the alternative is remembering to run a command after
     * every pack you install, and forgetting means skins that silently do not
     * work. Turned off by {@code /cmv unify off}, which is what that command
     * has to mean — removing the pack and having it come straight back would
     * not be off.</p>
     */
    public boolean autoUnify = true;

    /**
     * Whether a pack the game refused for its declared format is repaired.
     *
     * <p>On, because the alternative is a pack that sits in the list saying
     * "broken or incompatible" and does nothing, when the only thing wrong with
     * it is a version number written before this version existed. The pack's
     * contents are not touched and the original declaration is kept inside it;
     * see {@link dev.custommodels.cmv.unified.PackFormatFix}.</p>
     */
    public boolean autoFixPackFormat = true;

    /**
     * Whether the game's own menu wording is put back when a pack removes it.
     *
     * <p>On. A pack that points {@code menu.returnToGame} at nothing leaves the
     * button out of the pause screen with no label, which is not a look anyone
     * chose — it is the cost of a server skinning its own interface, paid by
     * every menu the player owns. Restated from the generated pack, so nothing
     * the pack ships is touched.</p>
     */
    public boolean restoreVanillaMenus = true;

    /**
     * Whether the game's own interface textures are put back over a pack's.
     *
     * <p>On. A server pack paints its branding into the buttons and the hotbar,
     * which is its own screens' business and follows the player onto the title
     * screen and the pause menu, where it is nobody's choice. Restored from
     * above rather than removed, so the pack keeps everything it ships.</p>
     */
    public boolean restoreVanillaInterface = true;

    /**
     * Whether a worn model is drawn as its model on the figure's head.
     *
     * <p>On, because a model meant for the head is otherwise invisible in the
     * one view whose job is to show you wearing it — Minecraft draws the
     * armour texture there instead of the item's model. Off restores the
     * game's own behaviour: the helmet looks like a helmet. Nothing you own is
     * affected either way; this is only ever about the detail card's own
     * figure. See {@code HeadSkins}.</p>
     */
    public boolean headSkins = true;

    /**
     * Where {@code /cmvcat facts update} fetches an item catalogue from.
     *
     * <p>A base address; the three endpoints under it are asked for by name.
     * Any server that publishes the same shape can be pointed at by changing
     * this, and emptying it switches the whole thing off. Nothing is fetched
     * unless the command is run, and what a fetch leaves behind goes on
     * working if this address never answers again — see {@link ItemFacts}.</p>
     */
    public String itemFactsUrl = "https://items.blossomcraft.org";

    /**
     * Use the names and crates a fetched catalogue gave, where it has them.
     *
     * <p>Off switches the document out of the way without deleting it, so the
     * pack's own inferred names come back and can be compared.</p>
     */
    public boolean itemFacts = true;

    /**
     * Namespaces the scan is to leave alone, e.g. {@code modelengine}.
     *
     * <p>A pack is not always all of one thing. An ItemsAdder export bundles
     * every plugin on a server, and the ones that describe entities rather than
     * items put a definition on screen for every limb of every mob: in the pack
     * measured, {@code modelengine} alone was 4,357 of 13,350 wraps, named
     * "Head" a hundred and eighty-nine times. Those are not appearances anyone
     * can put on a sword, but they are in the same file as the ones that are,
     * so ignoring the pack is not the answer — the namespace is.</p>
     *
     * <p>Empty by default. Nothing is hidden until asked for, because a
     * namespace this mod has never heard of is not evidence of anything.</p>
     */
    public java.util.List<String> ignoredNamespaces = new java.util.ArrayList<>();

    /** True when this pack is not to be scanned. */
    public boolean ignoresPack(String packId) {
        return ignoredPacks != null && ignoredPacks.contains(packId);
    }

    /** True when this namespace is not to be scanned. */
    public boolean ignoresNamespace(String namespace) {
        return ignoredNamespaces != null && ignoredNamespaces.contains(namespace);
    }

    /** Flips whether a namespace is scanned. Returns true if it is now ignored. */
    public boolean toggleIgnoredNamespace(String namespace) {
        if (ignoredNamespaces == null) {
            ignoredNamespaces = new java.util.ArrayList<>();
        }
        boolean nowIgnored;
        if (ignoredNamespaces.remove(namespace)) {
            nowIgnored = false;
        } else {
            ignoredNamespaces.add(namespace);
            nowIgnored = true;
        }
        save();
        return nowIgnored;
    }

    /** Flips whether a pack is scanned. Returns true if it is now ignored. */
    public boolean toggleIgnoredPack(String packId) {
        if (ignoredPacks == null) {
            ignoredPacks = new java.util.ArrayList<>();
        }
        boolean nowIgnored;
        if (ignoredPacks.remove(packId)) {
            nowIgnored = false;
        } else {
            ignoredPacks.add(packId);
            nowIgnored = true;
        }
        save();
        return nowIgnored;
    }

    /**
     * Preview quality. Lower settings reduce supersampling and rotation
     * smoothness, for very large packs or weak machines.
     */
    public String previewQuality = PreviewQuality.HIGH.name();

    /** How much work previews are allowed to do. */
    public enum PreviewQuality {
        LOW(1, 64, 160, false),
        MEDIUM(2, 128, 224, false),
        HIGH(2, 160, 288, false);

        public final int supersample;
        public final int cardSize;
        public final int detailSize;
        public final boolean animate;

        PreviewQuality(int supersample, int cardSize, int detailSize, boolean animate) {
            this.supersample = supersample;
            this.cardSize = cardSize;
            this.detailSize = detailSize;
            this.animate = animate;
        }

        public PreviewQuality next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    public int maxCardPixels() {
        return Math.max(32, Math.min(512, maxCardPixels));
    }

    public int maxDetailPixels() {
        return Math.max(64, Math.min(1024, maxDetailPixels));
    }

    public int maxStillPixels() {
        // Never below the moving cap: a view that sharpened into something
        // blurrier than it was while turning would be worse than not
        // sharpening at all.
        return Math.max(maxDetailPixels(), Math.max(128, Math.min(2048, maxStillPixels)));
    }

    public PreviewQuality quality() {
        try {
            return PreviewQuality.valueOf(previewQuality);
        } catch (IllegalArgumentException e) {
            return PreviewQuality.HIGH;
        }
    }

    public void setQuality(PreviewQuality value) {
        this.previewQuality = value.name();
        this.maxCardPixels = value.cardSize;
        this.maxDetailPixels = value.detailSize;
        this.animateCards = value.animate;
        save();
    }

    public static CmvConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }


    /**
     * Brings an older file up to the current defaults.
     *
     * <p>Only the preview settings are touched, and only once. Everything the
     * player chose deliberately is left alone.</p>
     */
    private void migrate() {
        if (configVersion >= CURRENT_VERSION) {
            return;
        }
        if (configVersion < 4) {
            // Preview caches are sized from the heap now, and the model cap
            // exists so an enormous pack degrades instead of crashing.
            maxModels = 120000;
        }
        if (configVersion < 3) {
            // Preview sizes and the grid density were retuned after profiling.
            maxDetailPixels = 288;
            gridDensity = Density.COMPACT.name();
        }
        if (configVersion < 2) {
            // Previews used to be rendered at a fixed 48 pixels and blitted into
            // a box three times that size, and every card span up a dozen extra
            // renders. Both are now sized from the display, so the old values
            // have to go or the change never reaches anyone who has run this
            // before.
            maxCardPixels = 160;
            maxDetailPixels = 384;
            animateCards = false;
        }
        configVersion = CURRENT_VERSION;
        save();
    }

    private static Path file() {
        return McCompat.configDir().resolve("config.json");
    }

    private static CmvConfig load() {
        Path path = file();
        if (Files.exists(path)) {
            try {
                CmvConfig loaded = GSON.fromJson(Files.readString(path), CmvConfig.class);
                if (loaded != null) {
                    loaded.migrate();
                    return loaded;
                }
            } catch (IOException | RuntimeException e) {
                CmvMod.LOGGER.warn("Could not read config, using defaults", e);
            }
        }
        CmvConfig fresh = new CmvConfig();
        fresh.configVersion = CURRENT_VERSION;
        fresh.save();
        return fresh;
    }

    public void save() {
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this));
        } catch (IOException e) {
            CmvMod.LOGGER.warn("Could not save config", e);
        }
    }
}
