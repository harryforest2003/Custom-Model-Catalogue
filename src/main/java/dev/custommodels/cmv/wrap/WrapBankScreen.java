package dev.custommodels.cmv.wrap;

import dev.custommodels.cmv.compat.McCompat;
import dev.custommodels.cmv.packs.ServerPackImport;
import dev.custommodels.cmv.core.CmvConfig;
import dev.custommodels.cmv.core.Keybinds;
import dev.custommodels.cmv.explorer.scan.AtlasBudget;
import dev.custommodels.cmv.explorer.graph.ExplorerQuery;
import dev.custommodels.cmv.explorer.graph.ItemType;
import dev.custommodels.cmv.explorer.graph.LogicalModel;
import dev.custommodels.cmv.explorer.graph.ResolvedName;
import dev.custommodels.cmv.explorer.render.PreviewService;
import dev.custommodels.cmv.explorer.scan.ExplorerIndex;
import dev.custommodels.cmv.explorer.scan.ScanReport;
import dev.custommodels.cmv.explorer.ui.DetailScreen;
import dev.custommodels.cmv.gui.GuiTheme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Wrap Bank: put an appearance from the loaded resource packs onto an item
 * you already own.
 *
 * <h2>Why there is no slot to put items into</h2>
 *
 * <p>The obvious design is a container slot the player drops an item into. It
 * is also the design that loses items: a slot means a stack held by the
 * interface rather than the inventory, and every way the interface can end —
 * closing, dying, disconnecting, a full inventory on the way back — is a way
 * for that stack to go missing. So the Wrap Bank never takes possession of
 * anything. The player's own inventory is drawn along the bottom, clicking a
 * slot <em>selects</em> it, and the wrap is written in place. There is no
 * moment at which an item is anywhere other than where the player left it.</p>
 *
 * <h2>Performance</h2>
 *
 * <p>Opening this screen runs no scan. It builds a view over the model database
 * the explorer already produced, and shares the same preview service, so the
 * cards here reuse thumbnails the explorer may already have rendered.</p>
 */
public final class WrapBankScreen extends Screen {

    private static final int SIDEBAR_W = 132;

    /**
     * How wide the navigation column is right now.
     *
     * <p>Zero in single-item mode. The column is tabs for choosing what to look
     * at, and in that mode there is only one thing to look at: the wraps that
     * fit the item that was handed over. A column of choices that all lead back
     * to the same grid is a column of wasted screen.</p>
     */
    private int sidebarWidth() {
        return oneItem ? 0 : SIDEBAR_W;
    }
    private static final int HEADER_H = 34;
    private static final int FILTER_H = 26;
    /**
     * The "your item" strip.
     *
     * <p>Trimmed from 46. Every pixel here is one the card grid does not get,
     * and at GUI scale 3 the whole screen is only 360 tall.</p>
     */
    private static final int ITEM_STRIP_H = 32;
    private static final int FOOTER_H = 18;
    private static final int NAV_ROW_H = 18;

    /**
     * The two states of the star on a card.
     *
     * <p>Drawn as text because the mod has no texture atlas of its own and the
     * font covers these: the client ships a unihex provider for the whole
     * basic multilingual plane, so a star is as available as a letter.</p>
     */
    private static final String STAR_ON = "\u2605";
    private static final String STAR_OFF = "\u2606";
    private static final int PAD = 12;

    private static final int CARD_GAP = 6;

    /** Card geometry, read from the density setting each frame. */
    private int cardW = 64;
    private int cardH = 86;
    private int cardTextLines = 1;

    private static final int SLOT = 18;

    /** Room between the armour column and the inventory grid. */
    private static final int ARMOUR_GAP = 6;

    /**
     * The armour slots, head first.
     *
     * <p>Read out of the 26.2 jar rather than assumed: {@code Inventory} maps
     * indices past its own list through {@code EQUIPMENT_SLOT_MAPPING}, built
     * as {@code EquipmentSlot.getIndex(36)}, and the humanoid armour slots
     * carry indices 0-3 in the order feet, legs, chest, head. So 36 is the
     * boots and 39 the helmet, and drawing head-at-the-top means counting
     * down.</p>
     */
    private static final int[] ARMOUR_SLOTS = {39, 38, 37, 36};

    /**
     * The offhand, which the game files just past the armour.
     *
     * <p>Read from {@code Inventory.EQUIPMENT_SLOT_MAPPING} in the 26.2 jar,
     * where 40 is {@code OFFHAND}. Drawn beside the hotbar rather than under
     * the armour: it is the other hand, and that is the row the hands are
     * on.</p>
     */
    private static final int OFFHAND_SLOT = 40;

    /** The grey silhouettes vanilla draws in an empty armour slot. */
    private static final Identifier[] ARMOUR_ICONS = {
            InventoryMenu.EMPTY_ARMOR_SLOT_HELMET,
            InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE,
            InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS,
            InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS,
    };

    /** Which of the three pages is showing. */
    private enum Tab {
        /**
         * Applying a look to the selected item.
         *
         * <p>There used to be a second tab for cosmetic skins. Both listed the
         * same models against the same item and differed only in how the look
         * was recorded — which is not a decision worth handing to the player,
         * because one of the two is simply better whenever it is available.
         * A real wrap is tried first and the client-side skin is the fallback,
         * chosen on the outcome of the write rather than on a guess.</p>
         */
        /**
         * How to use the mod, then what the packs hold and what is wrong with them.
         *
         * <p>Where the screen opens, because it answers the two questions a
         * player has before they have asked anything: what this is for, and
         * whether the thing they are about to browse is all there. The
         * instructions sit above the diagnostics because the diagnostics only
         * matter once something has gone wrong.</p>
         */
        HOME("Home", "How to use the mod, and what the packs contain"),
        /**
         * The model explorer, which used to be a screen of its own.
         *
         * <p>It was merged in because the two were ninety per cent the same
         * interface — a category sidebar, a search box, filter chips, a paged
         * card grid — and the tab that used to sit here, an "Item Browser"
         * whose whole job was handing you a base item, was a thin slice of what
         * the explorer already did. One grid, three things to do with it.</p>
         */
        BANK("Wrap Bank", "Every model the packs hold"),
        /**
         * The player's own lists.
         *
         * <p>A list of names rather than a grid of pictures, because that is
         * what it is for: finding the set you meant among the ones you made.
         * Opening one puts its members in the same grid as everything else, so
         * the tabs above still decide whether clicking a card applies it or
         * opens its details.</p>
         */
        SETS("Sets", "Your own lists. Click one to open it");

        final String label;
        final String blurb;

        Tab(String label, String blurb) {
            this.label = label;
            this.blurb = blurb;
        }
    }

    private final ExplorerIndex index = ExplorerIndex.get();

    private WrapCatalogue catalogue;
    private Tab tab = Tab.HOME;
    /**
     * Whether a card applies its appearance or opens its details.
     *
     * <p>The old pair of tabs, turned into the one switch it always was. Both
     * pages that show a grid share it, and it outlives the screen.</p>
     */
    /**
     * Never. Kept as one named constant rather than deleted, because the
     * screen still reads it in a dozen places to decide what to show, and a
     * catalogue's answer to all of them is the same: nothing is being applied.
     */
    private final boolean applying = false;

    /**
     * What the game says an item is, for deciding which models belong on it.
     * One instance, because it remembers its answers and they do not change
     * while a world is open.
     */
    private static final dev.custommodels.cmv.explorer.scan.ItemClassifier CLASSIFIER =
            new dev.custommodels.cmv.explorer.scan.ItemClassifier();

    private int selectedSlot = -1;
    private String query = "";
    private ItemType category = ItemType.ALL;
    private WrapCatalogue.Order order = WrapCatalogue.Order.NAME;
    private java.util.Map<ItemType, Integer> typeCounts = java.util.Map.of();
    private boolean compatibleOnly = true;
    /** The player's own lists: favourites, and the sets they have made. */
    private final dev.custommodels.cmv.collection.SkinSets sets =
            dev.custommodels.cmv.collection.SkinSets.get();
    /** The set whose members the grid is showing, or null for everything. */
    private dev.custommodels.cmv.collection.SkinSets.Set openSet;
    /** True when the grid is showing the favourites rather than a set. */
    private boolean openFavourites;
    /**
     * Building the open set rather than using it.
     *
     * <p>The grid shows everything while this is on, and a card's click puts it
     * in the set or takes it out instead of applying it. Filling a set any
     * other way is the menu, once per model, which is a lot of menu for
     * twenty models.</p>
     */
    private boolean collecting;
    /** The open set's members, for asking per card per frame. */
    private java.util.Set<String> collectingIndex = java.util.Set.of();
    private int setsScroll;
    /** What the name box is currently naming, or null when it is not shown. */
    private Naming naming;
    private EditBox nameBox;
    /**
     * The little menu, used both for filing a model into a set and for filing
     * a set under a heading. One implementation because they are the same
     * thing: a short list of names, one of which gets picked.
     */
    private String menuTitle = "";
    private List<MenuRow> menuRows = List.of();
    private int menuX;
    private int menuY;
    private int menuScroll;
    /**
     * What has been typed into the menu to narrow it.
     *
     * <p>Held as a plain string with the menu taking the keyboard directly,
     * rather than as a text widget. A widget would have to be created, placed,
     * focused and taken away again each time a menu opens, and what it is for
     * is a few letters of a name.</p>
     */
    private String menuFilter = "";
    /** What the row at the bottom makes, given whatever has been typed. */
    private java.util.function.Consumer<String> menuCreate;
    private String menuCreateLabel = "";

    private record MenuRow(String label, boolean ticked, Runnable action) {
    }

    /** A rename or a create, waiting on the name box. */
    private record Naming(String prompt, java.util.function.Consumer<String> accept) {
    }

    private int page;
    private int columns = 1;
    private int rows = 1;
    private final dev.custommodels.cmv.gui.Pager pager = new dev.custommodels.cmv.gui.Pager();

    private List<WrapTarget> visible = List.of();
    /** The browser tab lists models rather than wraps, so it needs its own list. */
    private List<LogicalModel> browseModels = List.of();
    private ExplorerQuery browseQuery = ExplorerQuery.EMPTY;
    private LogicalModel hoveredModel;
    private boolean showStats;
    private String pendingSearch;
    private List<String> packs = List.of();
    /** Empty means every pack. Shared by both tabs, like the sort order. */
    private String packFilter = "";
    private WrapTarget hovered;
    private EditBox searchBox;

    private String status = "";
    private long statusUntil;
    private WrapTarget pendingConfirm;
    /**
     * How much of the inventory a cosmetic rule covers. One item by default:
     * anything wider is a surprise when the player only meant the sword they
     * were looking at.
     */
    /**
     * How much of the inventory a client-side rule covers.
     *
     * <p>Fixed at the one item the player picked. It was a control on the
     * filter row and is not one any more: the other two settings widen a rule
     * to every item of a kind, which is a surprise waiting to happen for the
     * sake of a case nobody asked for, and this is the value it was left on
     * anyway.</p>
     */

    private final List<Hit> hits = new ArrayList<>();
    private final List<Hit> navHits = new ArrayList<>();
    private final List<SlotHit> slotHits = new ArrayList<>();
    private final List<String> chipLabels = new ArrayList<>();

    private record Hit(int x, int y, int width, int height, Runnable action) {
    }

    private record SlotHit(int x, int y, int slot) {
    }

    /**
     * In single-item mode: the item being wrapped, where the result goes, and
     * what to go back to.
     *
     * <p>Empty in every other mode, which is what every check below reads. The
     * Bank normally works on a slot of the player's inventory, because that is
     * the only place a client-side mod may change an item at all. An editor
     * that already holds the stack it is working on, and has its own way of
     * writing it back, needs neither the slot nor the inventory strip — so it
     * hands the item over, takes the wrapped copy back, and does its own
     * saving.</p>
     */
    private ItemStack loose = ItemStack.EMPTY;
    private java.util.function.Consumer<ItemStack> looseSink;
    private Screen looseParent;

    /**
     * One item and no choosing: no navigation column, no inventory strip.
     *
     * <p>Set both by an editor handing an item over and by the key pressed on a
     * slot, because in both cases the item is already decided and the chrome
     * that exists to decide it is in the way.</p>
     */
    private boolean oneItem;

    /** Opens straight onto the model index, for the commands and a search. */
    public static WrapBankScreen browsing(String search) {
        WrapBankScreen screen = new WrapBankScreen();
        screen.tab = Tab.BANK;
        screen.pendingSearch = search;
        return screen;
    }

    /**
     * Opens on the home page, for the key bind.
     *
     * <p>The key is the one way in that carries no intent with it — a command
     * asked for something specific, a click on an item asked to change that
     * item, but the key was just pressed. So it lands on the page that says
     * what the mod does rather than on a grid of fifty thousand models.</p>
     */
    public static WrapBankScreen home() {
        WrapBankScreen screen = new WrapBankScreen();
        screen.tab = Tab.HOME;
        return screen;
    }

    public WrapBankScreen() {
        super(Component.literal("Wrap Bank"));
    }

    /**
     * Keeps the pack the current server sent, top right of the home page.
     *
     * <p>Greyed rather than hidden when there is nothing downloaded: a button
     * that appears only sometimes is one nobody learns is there, and the
     * tooltip is where the reason goes.</p>
     */
    private void drawImportButton(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        String label = "Keep server pack";
        int width = this.font.width(label) + 16;
        int x = this.width - PAD - width;
        int y = 18;
        boolean ready = ServerPackImport.available();
        boolean hover = inside(mouseX, mouseY, x, y, width, 14);

        GuiTheme.rect(graphics, x, y, x + width, y + 14,
                hover && ready ? GuiTheme.CHIP_HOVER : GuiTheme.CHIP_FACE);
        GuiTheme.border(graphics, x, y, width, 14,
                ready ? (hover ? GuiTheme.ACCENT_BLUE : GuiTheme.BORDER) : GuiTheme.BORDER);
        GuiTheme.text(graphics, this.font, label, x + 8, y + 3,
                !ready ? GuiTheme.TEXT_MUTED
                        : hover ? GuiTheme.TEXT_HEADING : GuiTheme.TEXT_DIM, false);

        if (ready) {
            hits.add(new Hit(x, y, width, 14, this::importServerPack));
        }
        if (hover) {
            importTooltip = ready
                    ? "Copies the pack this server sent into your resource packs, "
                            + "names it after the server and updates it for this version."
                    : "Nothing downloaded. Join a server with resource packs set to "
                            + "Enabled, then come back.";
        }
    }

    private void importServerPack() {
        ServerPackImport.Report report = ServerPackImport.run();
        importResult = switch (report.outcome()) {
            case IMPORTED -> "Kept as " + report.file() + " \u2014 " + report.detail();
            case ALREADY_HERE -> report.file() + " \u2014 " + report.detail();
            case NOTHING_TO_IMPORT, FAILED -> report.detail();
        };
        if (report.worked()) {
            // It is in the folder but not switched on, and the index only reads
            // packs the game has loaded, so say what is left to do.
            index.refresh();
            AtlasBudget.invalidate();
        }
        rebuildHome();
    }

    /** The item's registry id, or empty — all this screen wanted of the applier. */
    private static String itemIdOf(net.minecraft.world.item.ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        return java.util.Optional.ofNullable(
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()))
                .map(Object::toString)
                .orElse("");
    }

    @Override
    protected void init() {
        index.openViewer();
        // Default to whatever is in hand: it is almost always the thing the
        // player came here to change.
        if (oneItem && selectedStack().isEmpty()) {
            // Better than a grid of everything, which is what an unrecognised item
            // quietly turns into.
            showStatus("No item to wrap here.");
        }
        if (selectedSlot < 0 && !oneItem && McCompat.mc().player != null) {
            selectedSlot = McCompat.mc().player.getInventory().getSelectedSlot();
        }

        this.searchBox = new EditBox(this.font, sidebarWidth() + PAD + 6, HEADER_H + 6,
                Math.max(140, Math.min(260, this.width - sidebarWidth() - 300)), 16,
                Component.literal("Search"));
        this.searchBox.setMaxLength(100);
        this.searchBox.setHint(Component.literal("Search name, set, item, model..."));
        this.searchBox.setResponder(text -> {
            query = text;
            page = 0;
            // Not rebuilt here. Matching a query against the model index means
            // scanning the search text of 57,826 models, which measures at
            // 18.7ms — longer than a frame, on every letter typed. Deferred to
            // the tick after typing stops, which changes nothing about what is
            // found and a great deal about how it feels to type.
            searchPendingSince = System.currentTimeMillis();
        });
        addRenderableWidget(this.searchBox);

        // Shown only while something is being named. One box rather than a
        // dialog: naming a set is a word, and a modal for a word is a lot of
        // interface for very little.
        this.nameBox = new EditBox(this.font, sidebarWidth() + PAD + 6, HEADER_H + 30,
                Math.max(140, Math.min(220, this.width - sidebarWidth() - 300)), 16,
                Component.literal("Name"));
        this.nameBox.setMaxLength(dev.custommodels.cmv.collection.SkinSets.MAX_NAME_LENGTH);
        this.nameBox.visible = false;
        addRenderableWidget(this.nameBox);
        if (pendingSearch != null && !pendingSearch.isBlank()) {
            this.searchBox.setValue(pendingSearch);
        }
        pendingSearch = null;
        rebuild();
        setInitialFocus(searchBox);
    }

    @Override
    public void removed() {
        index.closeViewer();
        super.removed();
    }

    /** Set when a namespace was switched on or off while the screen was open. */
    private boolean namespacesChanged;

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------- building

    /**
     * Rebuilds the visible list.
     *
     * <p>The catalogue is rebuilt only when the underlying scan changes, which
     * is what keeps opening this screen free. Filtering and searching run over
     * the finished list.</p>
     */
    private void rebuild() {
        // Both tabs offer the pack filter, so the list of packs is worked out
        // for both rather than only where it used to be shown.
        rebuildHome();
        packs = ExplorerQuery.packsOf(index.browsable());
        if (!packFilter.isEmpty() && !packs.contains(packFilter)) {
            // The pack it was set to is no longer loaded.
            packFilter = "";
        }

        // Which list, not what is in it. The index swaps the whole list when a
        // scan finishes and never edits it in place, so its identity changes
        // exactly when the catalogue would need rebuilding — where hashing it
        // walked all 57,826 models, and this runs on every keystroke in the
        // search box.
        String fingerprint = String.valueOf(System.identityHashCode(index.models()));
        if (catalogue == null || !fingerprint.equals(catalogue.fingerprint())) {
            catalogue = WrapCatalogue.build(index.models(),
                    McCompat::itemExists, CLASSIFIER::typeOf, fingerprint);
        }

        if (!applying) {
            // Browsing runs off the explorer's own query rather than the
            // wrap catalogue: it is about the models, not about what can be
            // applied to the item in your hand.
            browseQuery = browseQuery.withText(query).withType(category)
                    .withSourcePack(packFilter);
            browseModels = ExplorerQuery.sort(index.view(browseQuery), order);
            browseModels = keepMembers(browseModels,
                    model -> model.defaultState().modelId());
            typeCounts = index.counts();
            visible = List.of();
            clampPage();
            return;
        }

        String itemId = itemIdOf(selectedStack());
        List<WrapTarget> base = itemId.isEmpty()
                ? catalogue.all()
                : catalogue.compatibleWith(itemId, compatibleOnly);

        // Counts come from the list before the category filter, so the sidebar
        // shows what each category would give rather than only the current one.
        if (!packFilter.isEmpty()) {
            List<WrapTarget> fromPack = new ArrayList<>(base.size());
            for (WrapTarget target : base) {
                if (packFilter.equals(target.model().sourcePackId())) {
                    fromPack.add(target);
                }
            }
            base = fromPack;
        }

        base = keepMembers(base, target -> target.model().defaultState().modelId());
        typeCounts = catalogue.countsByType(base);
        browseModels = List.of();
        visible = WrapCatalogue.sort(
                catalogue.search(catalogue.ofType(base, category), query), order);
        clampPage();
    }

    /**
     * Narrows a list to the members of the open set, in the set's own order.
     *
     * <p>The order is the player's, not the sort's: a set is a list they wrote,
     * and reordering it by name would throw away the one piece of information
     * they put into it by hand. Sorting still applies everywhere else.</p>
     *
     * <p>An id the packs no longer have simply does not appear. The set keeps
     * it — a pack turned off for an evening should not empty a list — so it
     * comes back when the pack does.</p>
     */
    private <T> List<T> keepMembers(List<T> all, java.util.function.Function<T, String> idOf) {
        java.util.Set<String> members = openMembers();
        if (members == null) {
            return all;
        }
        java.util.Map<String, T> found = new java.util.LinkedHashMap<>();
        for (T item : all) {
            found.putIfAbsent(idOf.apply(item), item);
        }
        List<T> out = new ArrayList<>(members.size());
        for (String id : members) {
            T item = found.get(id);
            if (item != null) {
                out.add(item);
            }
        }
        return out;
    }

    /**
     * What is on the selected item, worked out once for the whole frame.
     *
     * <p>It used to be asked per card. The answer is about the item, not the
     * card, and getting it is not free: reading the wrap tag copies the stack's
     * custom data, and the client-side half builds two strings. Forty cards at
     * sixty frames a second made that two and a half thousand deep copies of an
     * item's NBT every second, for one answer that was the same every time —
     * the same shape as the anchor bug that once cost fifteen frames a second.
     * </p>
     */
    private String appliedTargetId = "";

    /** Whether what is applied is a wrap on the item rather than a skin over it. */
    private boolean appliedIsWrap;

    private ItemStack selectedStack() {
        if (!loose.isEmpty()) {
            return loose;
        }
        if (McCompat.mc().player == null || selectedSlot < 0) {
            return ItemStack.EMPTY;
        }
        return McCompat.mc().player.getInventory().getItem(selectedSlot);
    }

    private int perPage() {
        return Math.max(1, rows * columns);
    }

    private int shownCount() {
        return applying ? visible.size() : browseModels.size();
    }

    private int totalPages() {
        return Math.max(1, (shownCount() + perPage() - 1) / perPage());
    }

    private void clampPage() {
        page = Math.max(0, Math.min(page, totalPages() - 1));
    }

    /**
     * Moves to another page, abandoning renders queued for the one being left.
     *
     * <p>Same reasoning as the explorer: a page is a fixed, known set of cards,
     * so the renderer is never asked for more than one page of work at a
     * time.</p>
     */
    private void goToPage(int target) {
        int clamped = Math.max(0, Math.min(target, totalPages() - 1));
        if (clamped == page) {
            return;
        }
        page = clamped;
        index.previews().discardPending();
        McCompat.playClick();
    }

    /**
     * Whether the strip naming the item being changed is on screen.
     *
     * <p>Asked in both places that need to agree: the one that draws it, and
     * the one that decides where the grid starts. They did not — the height
     * was reserved on every page and the strip only drawn on the ones that
     * apply, which left a strip of nothing above the grid everywhere else.</p>
     */
    private boolean showsItemStrip() {
        return applying && tab != Tab.HOME && !oneItem;
    }

    /** True when the grid is showing one set rather than everything. */
    private boolean inSet() {
        return openSet != null || openFavourites;
    }

    private int gridTop() {
        // An open set takes a row above the grid to say so, and the grid gives
        // up the height for it — which reflows the columns and rows on its own,
        // since both are worked out from here.
        return HEADER_H + filterHeight()
                + (showsItemStrip() ? ITEM_STRIP_H : 0) + 4
                + (inSet() && tab != Tab.HOME ? 18 : 0);
    }

    private int gridBottom() {
        return pagerTop() - 4;
    }

    private int pagerTop() {
        return this.height - FOOTER_H - inventoryHeight() - 8
                - dev.custommodels.cmv.gui.Pager.height() - 6;
    }

    /**
     * How much room the inventory strip takes.
     *
     * <p>The full grid is four rows, which on a 1080p screen at GUI scale 3
     * left one row of cards above it. Only the hotbar is shown by default,
     * since the item being changed is usually held; the rest is a click away.</p>
     */
    private int inventoryHeight() {
        // Browsing and the diagnostics are not about the item in your hand,
        // so the strip would be a row of slots that does nothing. The grid
        // takes the space instead.
        if (!showsItemStrip()) {
            return 0;
        }
        // Always the whole inventory. Collapsing it to the hotbar saved three
        // rows of slots and, at the sizes the grid actually uses, bought no
        // extra row of cards — so it was a control that cost a look at your
        // own inventory and gave nothing back.
        return SLOT * 4 + 6;
    }

    // ------------------------------------------------------------ rendering

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                   float partialTick) {
        GuiTheme.setScreenSize(this.width, this.height);
        // Kept so a control whose action is built once, away from the draw,
        // can still open its menu under the pointer.
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        importTooltip = null;
        // Measured off-thread and only when out of date, so this is a no-op on
        // all but the first frame after a scan.
        if (tab == Tab.HOME) {
            AtlasBudget.request(index);
        }
        hits.clear();
        navHits.clear();
        slotHits.clear();

        CmvConfig.Density density = CmvConfig.get().density();
        cardW = density.cardWidth;
        cardH = density.cardHeight;
        cardTextLines = density.textLines;

        // Built once and reused: the layout needs the count before anything is
        // drawn and the row needs the labels afterwards, and working them out
        // twice meant two passes of font measurement every frame.
        // Before the chips, which offer to take it off, and before the grid,
        // which marks the card it is on. One read of the item's data a frame.
        appliedTargetId = "";
        frameChips = buildChips();
        chipRows = chipRowCount();
        int gridWidth = this.width - sidebarWidth() - PAD * 2;
        columns = Math.max(1, (gridWidth + CARD_GAP) / (cardW + CARD_GAP));
        rows = Math.max(1, (gridBottom() - gridTop() + CARD_GAP) / (cardH + CARD_GAP));
        clampPage();

        index.previews().beginFrame();

        GuiTheme.rect(graphics, 0, 0, this.width, this.height, GuiTheme.DARK_BG);
        GuiTheme.rect(graphics, 0, 0, sidebarWidth(), this.height, GuiTheme.SIDEBAR);
        GuiTheme.rect(graphics, sidebarWidth(), 0, sidebarWidth() + 1, this.height, GuiTheme.BORDER);

        ScanReport report = index.report();
        if (report.isRunning()) {
            drawScanning(graphics, report);
        } else {
            drawGrid(graphics, mouseX, mouseY);
        }

        // Not merely zero-width: its labels are drawn at a fixed inset and its rows
        // at a fixed origin, so a column of no width still left its text behind.
        if (!oneItem) {
            drawSidebar(graphics, mouseX, mouseY);
        }
        drawHeader(graphics, mouseX, mouseY);
        drawFilterRow(graphics, mouseX, mouseY);
        if (showsItemStrip()) {
            drawItemStrip(graphics, mouseX, mouseY);
            drawInventory(graphics, mouseX, mouseY);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        drawFooter(graphics);

        if (menuOpen()) {
            drawMenu(graphics, mouseX, mouseY);
        }
        if (showStats) {
            drawStatsPanel(graphics);
        }
        drawTooltip(graphics, mouseX, mouseY);
        if (picker.isOpen()) {
            GuiTheme.rect(graphics, 0, 0, this.width, this.height, GuiTheme.SCRIM);
            picker.draw(graphics, this.font, mouseX, mouseY);
        }
        if (importTooltip != null && !menuOpen() && pendingConfirm == null && !picker.isOpen()) {
            GuiTheme.tooltip(graphics, this.font,
                    wrapTooltip(importTooltip), mouseX, mouseY);
        }
        drawStatus(graphics);
    }

    /**
     * The scan report, on I. Carried over from the explorer, where it was the
     * answer to "did it actually find everything".
     */
    private void drawStatsPanel(GuiGraphicsExtractor graphics) {
        List<String> lines = new ArrayList<>(index.report().summaryLines());
        lines.add("");
        lines.add("Previews: " + index.previews().statistics());
        for (String pack : index.packIds()) {
            lines.add("  " + pack);
        }
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, this.font.width(line));
        }
        width += 20;
        int height = lines.size() * 11 + 16;
        int x = this.width - width - PAD;
        int y = HEADER_H + filterHeight() + 8;
        GuiTheme.rect(graphics, x, y, x + width, y + height, GuiTheme.DARK_PANEL);
        GuiTheme.border(graphics, x, y, width, height, GuiTheme.ACCENT_BLUE);
        int lineY = y + 8;
        for (String line : lines) {
            GuiTheme.text(graphics, this.font, line, x + 10, lineY, GuiTheme.TEXT_DIM, false);
            lineY += 11;
        }
    }

    private void drawSidebar(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        GuiTheme.rect(graphics, 0, 0, sidebarWidth(), HEADER_H, GuiTheme.SIDEBAR);
        GuiTheme.text(graphics, this.font,
                tab.label.toUpperCase(Locale.ROOT),
                12, 12, GuiTheme.TEXT_HEADING, false);
        GuiTheme.rect(graphics, 0, HEADER_H - 1, sidebarWidth(), HEADER_H, GuiTheme.BORDER);

        int y = HEADER_H + 6;
        for (Tab candidate : Tab.values()) {
            boolean selected = tab == candidate;
            boolean hover = inside(mouseX, mouseY, 0, y, sidebarWidth(), NAV_ROW_H);
            GuiTheme.navItem(graphics, this.font, 0, y, sidebarWidth(), NAV_ROW_H,
                    candidate.label, "", selected, hover, true);
            final Tab target = candidate;
            navHits.add(new Hit(0, y, sidebarWidth(), NAV_ROW_H, () -> {
                if (target == Tab.SETS) {
                    // Back to the list of names, out of whatever was open.
                    closeSet();
                }
                tab = target;
                page = 0;
                rebuild();
            }));
            y += NAV_ROW_H;
        }

        // Categories, taken from the base item's own registry tags. This
        // replaced an inferred set list: on a pack filed by event and
        // contributor rather than by collection the inference was not worth
        // navigating by, and a set the player wrote is on its own tab.
        y += 6;
        GuiTheme.text(graphics, this.font, "CATEGORY", 12, y, GuiTheme.ACCENT_BLUE, false);
        GuiTheme.rect(graphics, 12, y + 10, sidebarWidth() - 12, y + 11, GuiTheme.BORDER);
        y += 16;

        for (ItemType type : ItemType.tabOrder()) {
            if (y + NAV_ROW_H > this.height - FOOTER_H) {
                break;
            }
            int count = typeCounts.getOrDefault(type, 0);
            if (count == 0 && type != ItemType.ALL && type != category) {
                continue;
            }
            boolean selected = category == type;
            boolean hover = inside(mouseX, mouseY, 0, y, sidebarWidth(), NAV_ROW_H);
            GuiTheme.navItem(graphics, this.font, 0, y, sidebarWidth(), NAV_ROW_H,
                    type.displayName(), count > 0 ? String.valueOf(count) : "",
                    selected, hover, count > 0 || type == ItemType.ALL);
            final ItemType target = type;
            navHits.add(new Hit(0, y, sidebarWidth(), NAV_ROW_H, () -> {
                category = target;
                page = 0;
                rebuild();
            }));
            y += NAV_ROW_H;
        }
    }


    private void drawHeader(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        GuiTheme.rect(graphics, sidebarWidth() + 1, 0, this.width, HEADER_H, GuiTheme.DARK_BG);
        GuiTheme.rect(graphics, sidebarWidth() + 1, HEADER_H - 1, this.width, HEADER_H,
                GuiTheme.BORDER);

        String heading = category == ItemType.ALL
                ? tab.label.toUpperCase(Locale.ROOT)
                : category.displayName().toUpperCase(Locale.ROOT);
        GuiTheme.text(graphics, this.font, heading, sidebarWidth() + PAD + 2, 8,
                GuiTheme.TEXT_HEADING, false);
        GuiTheme.text(graphics, this.font, tab.blurb, sidebarWidth() + PAD + 2, 20,
                GuiTheme.TEXT_MUTED, false);

        // What matters is the state of the scan: this mod never writes.
        String state;
        int stateColour;
        {
            ScanReport report = index.report();
            if (report.isRunning()) {
                state = report.phase().label() + "...";
                stateColour = GuiTheme.ACCENT_BLUE;
            } else if (report.phase() == ScanReport.Phase.FAILED) {
                state = "Scan failed";
                stateColour = GuiTheme.TEXT_ERROR;
            } else {
                state = index.packIds().size() + " packs   |   "
                        + String.format("%,d duplicates removed", report.duplicatesRemoved());
                stateColour = report.warningCount() > 0
                        ? GuiTheme.TEXT_WARN : GuiTheme.TEXT_DIM;
                if (report.warningCount() > 0) {
                    state += "   |   " + report.warningCount() + " warnings";
                }
            }
        }
        GuiTheme.textRight(graphics, this.font, state, this.width - PAD, 8, stateColour);
        if (tab == Tab.HOME) {
            // The model count says nothing on this page, so the line it was
            // using carries the one action the page offers instead.
            drawImportButton(graphics, mouseX, mouseY);
        } else {
            GuiTheme.textRight(graphics, this.font,
                    String.format(applying ? "%,d wraps" : "%,d models", shownCount()),
                    this.width - PAD, 20, GuiTheme.TEXT_DIM);
        }
    }

    /** Set while the pointer is over the import button, cleared each frame. */
    private String importTooltip;

    /** The last import's outcome, shown under APPEARANCE until something else changes. */
    private String importResult = "";

    /**
     * The filters, as labels and whether each is doing anything.
     *
     * <p>Separate from drawing them because the number of rows they need
     * decides where the grid starts, and that has to be known before anything
     * is drawn.</p>
     */
    /** The chips for this frame, worked out once in {@code extractRenderState}. */
    private List<String[]> frameChips = List.of();

    private List<String[]> buildChips() {
        List<String[]> chips = new ArrayList<>();
        // Order and pack come first and are offered on both tabs: they are the
        // two questions people actually arrive with — "what is new" and "what
        // came from which pack" — and they mean the same thing either side.
        chips.add(new String[]{"Order: " + order.label(), "1"});
        chips.add(new String[]{packFilter.isEmpty()
                ? "Pack: all" : "Pack: " + shortPack(packFilter),
                packFilter.isEmpty() ? "0" : "1"});
        if (tab != Tab.SETS || inSet()) {
            // The same narrowing the sets tab does, without having to go there.
            // It opens the menu rather than cycling: with a hundred sets,
            // cycling is not choosing, and the menu can be searched.
            chips.add(new String[]{inSet() ? "Set: " + openName() : "Set: all",
                    inSet() ? "1" : "0"});
        }
        if (!applying) {
            // The explorer's own filters. These are about the models — where
            // they came from, what state they carry — and mean nothing to the
            // other two tabs, which filter by what fits the item in your hand.
            chips.add(new String[]{"States: " + browseQuery.states().label(),
                    browseQuery.states() != ExplorerQuery.StateFilter.ANY ? "1" : "0"});
        } else {
            chips.add(new String[]{compatibleOnly ? "Compatible only" : "All wraps",
                    compatibleOnly ? "1" : "0"});
        }
        if (category != ItemType.ALL) {
            chips.add(new String[]{"Category: " + category.displayName(), "1"});
        }
        chips.add(new String[]{"View: " + CmvConfig.get().density().label(), "1"});
        if (oneItem && !appliedTargetId.isEmpty()) {
            // Remove normally lives on the item strip, and the one-item screen
            // has none: there is nothing to choose between. Taking off what is
            // already on the item is not choosing, so it comes here instead —
            // without it, a wrap applied from an editor could be changed but
            // never taken off.
            chips.add(new String[]{appliedIsWrap ? "Remove wrap" : "Remove skin", "0"});
        }
        return chips;
    }

    /** Where the filter chips start, below the search box they used to share. */
    private int chipRowTop() {
        return HEADER_H + FILTER_H;
    }

    /**
     * How much height the filters take, counted in rows.
     *
     * <p>Measured once a frame rather than each time it is asked, because
     * everything below the filters asks — and answering means measuring every
     * chip's label.</p>
     */
    private int chipRows = 1;

    private int filterHeight() {
        return FILTER_H + chipRows * 20 + 2;
    }

    /**
     * How many rows the filters take at this width.
     *
     * <p>They used to be one row that stopped drawing when it ran out of
     * width, which quietly dropped whichever filter was last — and the ones at
     * the end are the ones that are there because the player turned something
     * on.</p>
     */
    private int chipRowCount() {
        if (tab == Tab.HOME) {
            // None of them mean anything to a page of diagnostics, and the
            // page would rather have the height.
            return 0;
        }
        int x = sidebarWidth() + PAD + 6;
        int rows = 1;
        for (String[] chip : frameChips) {
            int width = this.font.width(chip[0]) + 14;
            if (x + width > this.width - PAD && x > sidebarWidth() + PAD + 6) {
                rows++;
                x = sidebarWidth() + PAD + 6;
            }
            x += width + 4;
        }
        return rows;
    }

    private void drawFilterRow(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        // The one switch that decides what every card does keeps its place
        // beside the search box. The filters go underneath, where they have a
        // row to themselves and can wrap onto another.
        int topX = sidebarWidth() + PAD + 6
                + (searchBox == null ? 160 : searchBox.getWidth()) + 8;
        // No "clicking: applies" switch any more: a card has one behaviour
        // here, and it opens the model.
        // Beside the switch on every page, the diagnostics included — that page
        // is a report on the last scan, so the way to take another belongs on
        // it more than anywhere else.
        topChip(graphics, mouseX, mouseY, topX, "Rescan", false, () -> {
            index.refresh();
            page = 0;
            rebuild();
        });

        if (tab == Tab.HOME) {
            chipLabels.clear();
            drawFilterEdge(graphics);
            return;
        }
        int y = chipRowTop();
        int x = sidebarWidth() + PAD + 6;
        List<String[]> chips = frameChips;

        chipLabels.clear();
        for (String[] chip : chips) {
            chipLabels.add(chip[0]);
        }
        for (int i = 0; i < chips.size(); i++) {
            String label = chips.get(i)[0];
            boolean active = "1".equals(chips.get(i)[1]);
            int width = this.font.width(label) + 14;
            if (x + width > this.width - PAD && x > sidebarWidth() + PAD + 6) {
                // Wrapped rather than dropped.
                x = sidebarWidth() + PAD + 6;
                y += 20;
            }
            boolean hover = inside(mouseX, mouseY, x, y, width, 16);
            GuiTheme.chip(graphics, x, y, width, 16, active, hover);
            GuiTheme.text(graphics, this.font, label, x + 7, y + 4,
                    active ? GuiTheme.TEXT_HEADING : GuiTheme.TEXT_DIM, false);

            final int which = i;
            final int chipX = x;
            final int chipY = y;
            hits.add(new Hit(x, y, width, 16, () -> {
                CmvConfig config = CmvConfig.get();
                // The category chip only exists when a category is selected, so
                // the indices after it shift. Resolving by label keeps the
                // handler honest rather than depending on that arithmetic.
                String chosen = chipLabels.get(which);
                if (chosen.startsWith("Compatible") || chosen.startsWith("All wraps")) {
                    compatibleOnly = !compatibleOnly;
                } else if (chosen.startsWith("Order:")) {
                    order = order.next();
                } else if (chosen.startsWith("Pack:")) {
                    packFilter = cycle(packs, packFilter);
                } else if (chosen.startsWith("Set:")) {
                    openSetFilterMenu(chipX, chipY + 18);
                } else if (chosen.startsWith("Category:")) {
                    category = ItemType.ALL;
                } else if (chosen.startsWith("View:")) {
                    config.setDensity(config.density().next());
                } else if (chosen.startsWith("States:")) {
                    browseQuery = browseQuery.withStates(browseQuery.states().next());
                }
                page = 0;
                rebuild();
            }));
            x += width + 4;
        }
        drawFilterEdge(graphics);
    }

    /** A chip on the row the search box is on. Returns where the next one goes. */
    private int topChip(GuiGraphicsExtractor graphics, int mouseX, int mouseY, int x,
                        String label, boolean active, Runnable action) {
        int y = HEADER_H + 6;
        int width = this.font.width(label) + 14;
        boolean hover = inside(mouseX, mouseY, x, y, width, 16);
        GuiTheme.chip(graphics, x, y, width, 16, active, hover);
        GuiTheme.text(graphics, this.font, label, x + 7, y + 4,
                active || hover ? GuiTheme.TEXT_HEADING : GuiTheme.TEXT_DIM, false);
        hits.add(new Hit(x, y, width, 16, action));
        return x + width + 4;
    }

    /** The line under the filters, wherever they happen to end. */
    private void drawFilterEdge(GuiGraphicsExtractor graphics) {
        GuiTheme.rect(graphics, sidebarWidth() + 1, HEADER_H + filterHeight() - 2, this.width,
                HEADER_H + filterHeight() - 1, GuiTheme.BORDER);
    }

    /** The "your item" strip: what is selected, what is on it, and what can be done. */
    private void drawItemStrip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int top = HEADER_H + filterHeight() - 1;
        int left = sidebarWidth() + 1;
        GuiTheme.rect(graphics, left, top, this.width, top + ITEM_STRIP_H, GuiTheme.DARK_PANEL);
        GuiTheme.rect(graphics, left, top + ITEM_STRIP_H, this.width,
                top + ITEM_STRIP_H + 1, GuiTheme.BORDER);

        ItemStack stack = selectedStack();
        int slotX = left + PAD;
        int slotY = top + 7;
        GuiTheme.rect(graphics, slotX, slotY, slotX + SLOT, slotY + SLOT, GuiTheme.DARK_SUNKEN);
        GuiTheme.border(graphics, slotX, slotY, SLOT, SLOT,
                stack.isEmpty() ? GuiTheme.BORDER : GuiTheme.ACCENT_BLUE);
        if (!stack.isEmpty()) {
            GuiTheme.item(graphics, stack, slotX + 1, slotY + 1);
            GuiTheme.itemDecorations(graphics, this.font, stack, slotX + 1, slotY + 1);
        }

        int textX = slotX + SLOT + 8;
        if (stack.isEmpty()) {
            GuiTheme.text(graphics, this.font,
                    "Click a slot below to choose the item you want to change.",
                    textX, slotY + 1, GuiTheme.TEXT_DIM, false);
            GuiTheme.text(graphics, this.font,
                    "Nothing is moved out of your inventory.",
                    textX, slotY + 11, GuiTheme.TEXT_MUTED, false);
            return;
        }

        // Two lines rather than three: the item on one, what is on it on the
        // other, with the raw id left to the tooltip.
        GuiTheme.text(graphics, this.font,
                trim(stack.getHoverName().getString() + "   " + itemIdOf(stack),
                        this.width - textX - 120),
                textX, slotY + 1, GuiTheme.TEXT_BRIGHT, false);

        // What this model is reached by, which is what a catalogue is for.
        GuiTheme.text(graphics, this.font,
                "Filtering the grid to models this item can show", textX, slotY + 11,
                GuiTheme.TEXT_MUTED, false);
    }

    /**
     * The player's own inventory, drawn read-only.
     *
     * <p>Clicking selects a slot. Nothing here moves, swaps or picks up an
     * item; there is no cursor stack and no way for one to exist, which is what
     * makes the duplication and loss cases in the brief unreachable rather than
     * merely handled.</p>
     */
    private void drawInventory(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (McCompat.mc().player == null) {
            return;
        }
        int height = inventoryHeight();
        int top = this.height - FOOTER_H - height;
        int gridWidth = 9 * SLOT;
        // Armour to the left, offhand to the right, and the three centred as
        // one block so the grid does not appear off-centre.
        int blockWidth = SLOT + ARMOUR_GAP + gridWidth + ARMOUR_GAP + SLOT;
        int blockLeft = sidebarWidth() + 1 + (this.width - sidebarWidth() - 1 - blockWidth) / 2;
        int armourX = blockLeft;
        int left = blockLeft + SLOT + ARMOUR_GAP;
        int offhandX = left + gridWidth + ARMOUR_GAP;

        GuiTheme.rect(graphics, sidebarWidth() + 1, top - 4, this.width, this.height - FOOTER_H,
                GuiTheme.DARK_PANEL);
        GuiTheme.rect(graphics, sidebarWidth() + 1, top - 4, this.width, top - 3, GuiTheme.BORDER);

        var inventory = McCompat.mc().player.getInventory();
        int count = 36;
        for (int i = 0; i < count; i++) {
            // The first three rows are the main inventory (slots 9-35) and the
            // last is the hotbar, matching the inventory screen.
            int slot = i < 27 ? i + 9 : i - 27;
            int column = i % 9;
            int row = i / 9;
            int x = left + column * SLOT;
            int y = top + row * SLOT + (row == 3 ? 4 : 0);
            drawSlot(graphics, mouseX, mouseY, x, y, inventory.getItem(slot), slot, null);
        }

        // Head at the top, boots at the bottom. The last one takes the same
        // four-pixel drop the hotbar row does, so the two columns start and
        // finish on the same lines rather than missing each other by the gap.
        for (int i = 0; i < ARMOUR_SLOTS.length; i++) {
            int slot = ARMOUR_SLOTS[i];
            int y = top + i * SLOT + (i == ARMOUR_SLOTS.length - 1 ? 4 : 0);
            drawSlot(graphics, mouseX, mouseY, armourX, y, inventory.getItem(slot), slot,
                    ARMOUR_ICONS[i]);
        }

        // Level with the hotbar, taking its four-pixel drop, because what it
        // holds is the other half of what the hotbar holds.
        drawSlot(graphics, mouseX, mouseY, offhandX, top + 3 * SLOT + 4,
                inventory.getItem(OFFHAND_SLOT), OFFHAND_SLOT,
                InventoryMenu.EMPTY_ARMOR_SLOT_SHIELD);
    }

    /**
     * One inventory slot: its background, what is in it, and its click region.
     *
     * @param emptyIcon silhouette to show when the slot is empty, or null for
     *                  the ordinary slots, which have none
     */
    private void drawSlot(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                          int x, int y, ItemStack stack, int slot, Identifier emptyIcon) {
        boolean selected = slot == selectedSlot;
        boolean hover = inside(mouseX, mouseY, x, y, SLOT, SLOT);

        GuiTheme.rect(graphics, x, y, x + SLOT, y + SLOT,
                selected ? GuiTheme.ACCENT_DIM
                        : (hover ? GuiTheme.PANEL_ALT : GuiTheme.DARK_SUNKEN));
        if (selected) {
            GuiTheme.border(graphics, x, y, SLOT, SLOT, GuiTheme.ACCENT_BLUE);
        }
        if (!stack.isEmpty()) {
            GuiTheme.item(graphics, stack, x + 1, y + 1);
            GuiTheme.itemDecorations(graphics, this.font, stack, x + 1, y + 1);
        } else if (emptyIcon != null) {
            // The same call the inventory screen makes for a slot with a
            // no-item icon: GUI_TEXTURED, sixteen by sixteen.
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, emptyIcon, x + 1, y + 1, 16, 16);
        }
        slotHits.add(new SlotHit(x, y, slot));
    }

    private void drawGrid(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        hovered = null;
        hoveredModel = null;
        if (tab == Tab.HOME) {
            drawHome(graphics, mouseX, mouseY);
            return;
        }
        if (tab == Tab.SETS && !inSet()) {
            drawSetsList(graphics, mouseX, mouseY);
            return;
        }
        if (openSet != null || openFavourites) {
            drawOpenSetBar(graphics, mouseX, mouseY);
        }
        if (!applying) {
            drawModelGrid(graphics, mouseX, mouseY);
            return;
        }
        if (visible.isEmpty()) {
            drawEmpty(graphics);
            return;
        }
        int left = sidebarWidth() + PAD;
        int top = gridTop();
        int first = page * perPage();
        int last = Math.min(visible.size(), first + perPage());

        CmvConfig config = CmvConfig.get();
        int previewSize = McCompat.previewPixels(cardW - 12, config.maxCardPixels());
        for (int i = first; i < last; i++) {
            int slot = i - first;
            int x = left + (slot % columns) * (cardW + CARD_GAP);
            int y = top + (slot / columns) * (cardH + CARD_GAP);
            WrapTarget target = visible.get(i);
            boolean hover = inside(mouseX, mouseY, x, y, cardW, cardH);
            if (hover) {
                hovered = target;
            }
            drawCard(graphics, target, x, y, hover, previewSize, mouseX, mouseY);
        }
        pager.draw(graphics, this.font,
                sidebarWidth() + (this.width - sidebarWidth()) / 2, pagerTop(),
                page, totalPages(), visible.size(),
                catalogue == null ? visible.size() : catalogue.all().size(),
                mouseX, mouseY);
    }

    /**
     * The browser tab's grid: the pack's models, not the wraps for one item.
     *
     * <p>Shares the chrome above and below with the other tabs — same sidebar,
     * same search, same pager — which is the point of having merged the two
     * screens rather than keeping a second one that looked almost identical.</p>
     */
    private void drawModelGrid(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (browseModels.isEmpty()) {
            drawEmpty(graphics);
            return;
        }
        int left = sidebarWidth() + PAD;
        int top = gridTop();
        int first = page * perPage();
        int last = Math.min(browseModels.size(), first + perPage());

        CmvConfig config = CmvConfig.get();
        int previewSize = McCompat.previewPixels(cardW - 12, config.maxCardPixels());
        for (int i = first; i < last; i++) {
            int slot = i - first;
            int x = left + (slot % columns) * (cardW + CARD_GAP);
            int y = top + (slot / columns) * (cardH + CARD_GAP);
            LogicalModel model = browseModels.get(i);
            boolean hover = inside(mouseX, mouseY, x, y, cardW, cardH);
            if (hover) {
                hoveredModel = model;
            }
            drawModelCard(graphics, model, x, y, hover, previewSize, config.animateCards,
                    mouseX, mouseY);
        }
        pager.draw(graphics, this.font,
                sidebarWidth() + (this.width - sidebarWidth()) / 2, pagerTop(),
                page, totalPages(), browseModels.size(), index.browsable().size(),
                mouseX, mouseY);
    }

    private void drawModelCard(GuiGraphicsExtractor graphics, LogicalModel model,
                               int x, int y, boolean hover, int previewSize, boolean animate,
                               int mouseX, int mouseY) {
        GuiTheme.card(graphics, x, y, cardW, cardH, hover, false);

        int box = cardW - 12;
        int px = x + 6;
        int py = y + 6;
        GuiTheme.rect(graphics, px, py, px + box, py + box, GuiTheme.DARK_SUNKEN);

        PreviewService.CardPreview preview =
                index.previews().card(model, previewSize, animate);
        if (preview != null) {
            int inset = hover ? 1 : 4;
            GuiTheme.texture(graphics, preview.texture(), px + inset, py + inset,
                    box - inset * 2, box - inset * 2, preview.v0(), preview.v1());
        } else {
            String note = model.isRenderable() ? "..." : "no geometry";
            GuiTheme.text(graphics, this.font, note,
                    px + box / 2 - this.font.width(note) / 2, py + box / 2 - 4,
                    model.isRenderable() ? GuiTheme.TEXT_MUTED : GuiTheme.TEXT_ERROR, false);
        }

        if (cardTextLines > 0) {
            int textY = y + 6 + box + 4;
            String states = model.hasMultipleStates()
                    ? model.states().size() + " states" : "";
            GuiTheme.text(graphics, this.font, trim(model.displayName(), cardW - 12),
                    x + 6, textY, GuiTheme.TEXT_BRIGHT, false);
            if (cardTextLines > 1) {
                int typeWidth = cardW - 12
                        - (states.isEmpty() ? 0 : this.font.width(states) + 4);
                GuiTheme.text(graphics, this.font,
                        trim(model.type().displayName(), typeWidth),
                        x + 6, textY + 11, GuiTheme.ACCENT_BLUE, false);
                if (!states.isEmpty()) {
                    GuiTheme.textRight(graphics, this.font, states, x + cardW - 6,
                            textY + 11, GuiTheme.TEXT_DIM);
                }
            }
        }
        if (!model.warnings().isEmpty()) {
            GuiTheme.text(graphics, this.font, "!", x + cardW - 11, y + 5,
                    GuiTheme.TEXT_WARN, false);
        }
        drawStar(graphics, model, x, y, hover, mouseX, mouseY);
    }

    private void drawCard(GuiGraphicsExtractor graphics, WrapTarget target,
                          int x, int y, boolean hover, int previewSize,
                          int mouseX, int mouseY) {
        boolean applied = !appliedTargetId.isEmpty()
                && target.id().equals(appliedTargetId);
        GuiTheme.card(graphics, x, y, cardW, cardH, hover, applied);

        int box = cardW - 12;
        int px = x + 6;
        int py = y + 6;
        GuiTheme.rect(graphics, px, py, px + box, py + box, GuiTheme.DARK_SUNKEN);

        PreviewService.CardPreview preview = index.previews()
                .card(target.model(), previewSize, CmvConfig.get().animateCards);
        if (preview != null) {
            int inset = hover ? 1 : 4;
            GuiTheme.texture(graphics, preview.texture(), px + inset, py + inset,
                    box - inset * 2, box - inset * 2, preview.v0(), preview.v1());
        } else {
            // A model the pack ships broken still gets a card, because its paths
            // and compatibility are the useful part.
            String note = target.model().isRenderable() ? "..." : "no preview";
            GuiTheme.text(graphics, this.font, note,
                    px + box / 2 - this.font.width(note) / 2, py + box / 2 - 4,
                    target.model().isRenderable() ? GuiTheme.TEXT_MUTED : GuiTheme.TEXT_WARN,
                    false);
        }

        if (cardTextLines > 0) {
            int textY = y + 6 + box + 4;
            GuiTheme.text(graphics, this.font, trim(target.displayName(), cardW - 12),
                    x + 6, textY,
                    applied ? GuiTheme.TEXT_HEADING : GuiTheme.TEXT_BRIGHT, false);

            if (cardTextLines > 1) {
                GuiTheme.text(graphics, this.font,
                        trim(target.type().displayName(), cardW - 12),
                        x + 6, textY + 11, GuiTheme.ACCENT_BLUE, false);
            }
            if (cardTextLines > 2) {
                String third = !applying && !target.compatibleItems().isEmpty()
                        ? shortId(target.compatibleItems().get(0))
                        : (target.name().isStated() ? "named by pack" : "");
                if (!third.isEmpty()) {
                    GuiTheme.text(graphics, this.font, trim(third, cardW - 12),
                            x + 6, textY + 22, GuiTheme.TEXT_MUTED, false);
                }
            }
        }
        if (applied) {
            // Worth a marker at every density: it is the difference between
            // "this is what is on my item" and "this is one of eight hundred".
            GuiTheme.text(graphics, this.font, "ON", x + cardW - 6 - this.font.width("ON"),
                    y + 5, GuiTheme.GOOD, false);
        }
        drawStar(graphics, target.model(), x, y, hover, mouseX, mouseY);
    }

    /**
     * The favourite toggle in a card's corner.
     *
     * <p>Only drawn on a card the cursor is over, or one already starred. A
     * grid of forty cards each wearing an empty outline is a grid of forty
     * things asking to be clicked.</p>
     */
    private void drawStar(GuiGraphicsExtractor graphics, LogicalModel model,
                          int x, int y, boolean hover, int mouseX, int mouseY) {
        String modelId = model.defaultState().modelId();
        if (modelId.isEmpty()) {
            return;
        }
        boolean starred = collecting ? inOpenList(modelId) : sets.isFavourite(modelId);
        // While collecting, every card says whether it is in — that is the
        // whole picture being edited, and hiding it until hover would mean
        // waving the cursor over the grid to read it.
        if (!starred && !hover && !collecting) {
            return;
        }
        int starX = x + 5;
        int starY = y + 4;
        boolean over = inside(mouseX, mouseY, starX - 2, starY - 2, 12, 12);
        GuiTheme.text(graphics, this.font, starred ? STAR_ON : STAR_OFF, starX, starY,
                starred ? GuiTheme.TEXT_WARN : (over ? GuiTheme.TEXT_BRIGHT
                        : GuiTheme.TEXT_MUTED), false);
        // Registered before the card's own click is considered, so the corner
        // stars rather than applies.
        if (collecting) {
            // The whole card is the target here, registered by the caller, so
            // the star is a marker rather than a second thing to hit.
            return;
        }
        hits.add(new Hit(starX - 2, starY - 2, 12, 12, () -> {
            boolean was = sets.isFavourite(modelId);
            sets.toggleFavourite(modelId);
            boolean now = sets.isFavourite(modelId);
            // Reported from what actually happened rather than from what was
            // asked for, because at the limit the answer is neither.
            showStatus(now ? "Added to favourites"
                    : was ? "Removed from favourites" : "Favourites are full");
            rebuild();
        }));
    }

    // ----------------------------------------------------------- diagnostics

    /**
     * What the packs contain, and what is wrong with them.
     *
     * <p>Everything here was already being collected and had nowhere to go: the
     * scan counts what it read and what it could not, every model keeps the
     * warnings raised while it was built, and the pack list is what the game
     * says is loaded. It was reachable only as a debug overlay, which is the
     * wrong place for the answer to "why does that skin not work" — the most
     * common question these packs raise, and one the mod can usually
     * answer.</p>
     */
    private void drawHome(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int left = sidebarWidth() + PAD;
        int right = this.width - PAD;
        int top = gridTop();
        int bottom = this.height - FOOTER_H - 4;
        int y = top - homeScroll;
        for (HomeRow row : homeRows) {
            if (row.heading()) {
                y = homeHeading(graphics, left, y, right, top, bottom, row.text());
            } else if (row.action() != null) {
                y = homeControl(graphics, left, y, right, top, bottom, mouseX, mouseY, row);
            } else {
                y = homeLine(graphics, left, y, top, bottom, row.text(), row.colour());
            }
        }
        homeContentHeight = y + homeScroll - top;
    }

    /**
     * One line of the home page, worked out when it changes rather than per frame.
     *
     * <p>{@code action} is null for the reading matter, which is most of it. A
     * row that has one is drawn as a control and takes a click.</p>
     */
    private record HomeRow(String text, int colour, boolean heading, Runnable action) {
        HomeRow(String text, int colour, boolean heading) {
            this(text, colour, heading, null);
        }
    }

    private List<HomeRow> homeRows = List.of();

    /**
     * Gathers what the diagnostics page shows.
     *
     * <p>Built when something changes rather than while drawing. Counting how
     * many models came from each pack means walking the index once per pack,
     * which is fine once and is tens of thousands of comparisons a frame if it
     * is done where it is drawn.</p>
     */
    private void rebuildHome() {
        ScanReport report = index.report();
        List<HomeRow> rows = new ArrayList<>();

        // The instructions come first because this is where the screen opens.
        rows.add(new HomeRow("GETTING STARTED", 0, true));
        rows.add(new HomeRow("1.  Press " + Keybinds.openKeyName()
                + " to open this screen, then open the Catalogue tab.",
                GuiTheme.TEXT_DIM, false));
        rows.add(new HomeRow("2.  The sidebar splits every model the packs hold by what "
                + "item it is filed under.", GuiTheme.TEXT_DIM, false));
        rows.add(new HomeRow("3.  Click a card to open the model: its states, textures, "
                + "geometry, and image and animation export.", GuiTheme.TEXT_DIM, false));
        rows.add(new HomeRow("4.  Hold an item to narrow the grid to models that item "
                + "can show.", GuiTheme.TEXT_DIM, false));
        rows.add(new HomeRow("", 0, false));
        rows.add(new HomeRow("Nothing here changes an item. Sets are your own lists, made "
                + "from the right-click menu.", GuiTheme.TEXT_MUTED, false));
        rows.add(new HomeRow("", 0, false));
        rows.add(new HomeRow("KEYS", 0, true));
        rows.add(new HomeRow("R  rescan the packs      I  index statistics      "
                + "Esc  close      Right-click a card  add to a set",
                GuiTheme.TEXT_DIM, false));
        rows.add(new HomeRow("", 0, false));
        rows.add(new HomeRow("", 0, false));
        rows.add(new HomeRow("APPEARANCE", 0, true));
        rows.add(new HomeRow(String.format("Primary colour   \u2014   #%06X   "
                        + "(selection, focus, the active tab)", GuiTheme.primary()),
                GuiTheme.TEXT_BRIGHT, false, this::openPrimaryPicker));
        rows.add(new HomeRow(String.format("Secondary colour   \u2014   #%06X   "
                        + "(panels, cards and the inventory, darkened from this)",
                        GuiTheme.secondary()),
                GuiTheme.TEXT_BRIGHT, false, this::openSecondaryPicker));
        rows.add(new HomeRow("Start from a ready-made pair", GuiTheme.TEXT_DIM, false,
                this::openPaletteMenu));

        rows.add(new HomeRow("", 0, false));
        rows.add(new HomeRow("THE SCAN", 0, true));
        if (report.isRunning()) {
            rows.add(new HomeRow(report.phase().label() + "...", GuiTheme.ACCENT_BLUE, false));
        }
        for (String line : report.summaryLines()) {
            rows.add(new HomeRow(line, GuiTheme.TEXT_DIM, false));
        }
        if (!report.failure().isEmpty()) {
            rows.add(new HomeRow("Failed: " + report.failure(), GuiTheme.TEXT_ERROR, false));
        }

        rows.add(new HomeRow("", 0, false));
        rows.add(new HomeRow("PACKS BEING READ", 0, true));
        List<String> packIds = index.packIds();
        if (packIds.isEmpty()) {
            rows.add(new HomeRow("None. Enable a resource pack and press R.",
                    GuiTheme.TEXT_WARN, false));
        }
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (LogicalModel model : index.browsable()) {
            counts.merge(model.sourcePackId(), 1, Integer::sum);
        }
        for (String pack : packIds) {
            boolean ignored = CmvConfig.get().ignoresPack(pack);
            rows.add(new HomeRow(shortPack(pack) + "  \u2014  "
                    + counts.getOrDefault(pack, 0) + " models"
                    + (ignored ? "   (not scanned)" : ""),
                    ignored ? GuiTheme.TEXT_MUTED : GuiTheme.TEXT_DIM, false));
        }

        List<String> warnings = report.warnings();
        rows.add(new HomeRow("", 0, false));
        rows.add(new HomeRow("WHAT IS WRONG  (" + warnings.size() + ")", 0, true));
        if (warnings.isEmpty()) {
            rows.add(new HomeRow(
                    "Nothing. Every model the packs point at was found and read.",
                    GuiTheme.GOOD, false));
        }
        // Bounded: a pack can raise thousands, and a screen listing them all is
        // a screen nobody reads.
        int shown = Math.min(warnings.size(), HOME_WARNINGS);
        for (int i = 0; i < shown; i++) {
            rows.add(new HomeRow(warnings.get(i), GuiTheme.TEXT_WARN, false));
        }
        if (warnings.size() > shown) {
            rows.add(new HomeRow("and " + (warnings.size() - shown) + " more, in the log",
                    GuiTheme.TEXT_MUTED, false));
        }

        rows.add(new HomeRow("", 0, false));
        rows.add(new HomeRow("ITEM ATLAS", 0, true));
        addAtlasRows(rows);
        addNamespaceRows(rows);

        rows.add(new HomeRow("", 0, false));
        rows.add(new HomeRow("RESOURCE PACKS", 0, true));
        if (!dev.custommodels.cmv.core.PackHistory.canRestore()) {
            // Saying so beats leaving a blank where a button might have been:
            // the answer to "why is there no restore button" is that there is
            // nothing fuller to go back to.
            rows.add(new HomeRow(McCompat.selectedPackIds().size()
                    + " packs on. Nothing fuller has been seen, so there is nothing "
                    + "to put back yet.", GuiTheme.TEXT_MUTED, false));
        } else {
            rows.add(new HomeRow("Fewer packs are on than last time this looked. "
                    + "The game switches them all off when a reload fails.",
                    GuiTheme.TEXT_WARN, false));
            rows.add(new HomeRow("Put back "
                            + dev.custommodels.cmv.core.PackHistory.candidateSize()
                            + " remembered packs",
                    GuiTheme.TEXT_BRIGHT, false, () -> {
                int restored = dev.custommodels.cmv.core.PackHistory.restore();
                importResult = restored > 0
                        ? "Turned " + restored + " packs back on."
                        : "Nothing could be restored; the files are gone.";
                rebuildHome();
            }));
        }

        if (!importResult.isEmpty()) {
            rows.add(new HomeRow("", 0, false));
            rows.add(new HomeRow("SERVER PACK", 0, true));
            rows.add(new HomeRow(importResult, GuiTheme.TEXT_DIM, false));
            rows.add(new HomeRow("It is in your resource packs but not switched on. "
                    + "Enable it in Options \u2192 Resource Packs.",
                    GuiTheme.TEXT_MUTED, false));
        }

        rows.add(new HomeRow("", 0, false));
        rows.add(new HomeRow("PREVIEWS", 0, true));
        rows.add(new HomeRow(index.previews().statistics(), GuiTheme.TEXT_DIM, false));
        homeRows = List.copyOf(rows);
    }

    private static final int HOME_WARNINGS = 40;
    private int homeScroll;
    private int homeContentHeight;

    /**
     * A home row that does something: bordered like a chip so it reads as
     * pressable, and only clickable while it is actually on screen — the list
     * scrolls, and a hit registered for a row above the viewport would be a
     * click target sitting invisibly under the header.
     */
    private int homeControl(GuiGraphicsExtractor graphics, int left, int y, int right,
                            int top, int bottom, int mouseX, int mouseY, HomeRow row) {
        // The full width of the panel. A fixed 220 was narrower than the
        // longest label on every screen size, so the text it was there to show
        // was the part that got cut.
        int width = Math.max(80, right - left - 12);
        if (y + 15 >= top && y <= bottom) {
            boolean hover = inside(mouseX, mouseY, left + 6, y, width, 15);
            GuiTheme.rect(graphics, left + 6, y, left + 6 + width, y + 15,
                    hover ? GuiTheme.CHIP_HOVER : GuiTheme.CHIP_FACE);
            GuiTheme.border(graphics, left + 6, y, width, 15,
                    hover ? GuiTheme.ACCENT_BLUE : GuiTheme.BORDER);
            GuiTheme.text(graphics, this.font, trim(row.text(), width - 12), left + 12, y + 4,
                    hover ? GuiTheme.TEXT_HEADING : row.colour(), false);
            hits.add(new Hit(left + 6, y, width, 15, row.action()));
        }
        return y + 19;
    }

    private int homeHeading(GuiGraphicsExtractor graphics, int left, int y, int right,
                            int top, int bottom, String text) {
        if (y + 14 >= top && y <= bottom) {
            GuiTheme.text(graphics, this.font, text, left + 2, y, GuiTheme.ACCENT_BLUE, false);
            GuiTheme.rect(graphics, left + 2, y + 10, right - 2, y + 11, GuiTheme.BORDER);
        }
        return y + 16;
    }

    /**
     * What the item atlas will cost, and whether it is about to be a problem.
     *
     * <p>Worth its own section because the failure it predicts is the worst one
     * this mod can see coming: when the sprites do not fit, the game switches
     * off every resource pack you have and writes that to disk. A number here
     * is the only warning there is.</p>
     */
    /**
     * Every namespace in the item atlas, with what it costs and a way out.
     *
     * <p>The figure beside each one is the space it actually occupies, so the
     * expensive ones are obvious rather than guessed at. Switching one off
     * removes it from the pack files themselves — a filter the game never knew
     * about left the textures in the atlas and saved nothing — and switching it
     * back on restores it from the untouched copy kept aside.</p>
     */
    private void addNamespaceRows(List<HomeRow> rows) {
        AtlasBudget.Result result = AtlasBudget.latest();
        AtlasBudget.Atlas items = result == null ? null : result.items();
        if (items == null || items.byNamespace().isEmpty()) {
            return;
        }
        rows.add(new HomeRow("", 0, false));
        rows.add(new HomeRow("NAMESPACES IN THE ITEM ATLAS", 0, true));
        rows.add(new HomeRow("Click one to take it out of the packs. Applied when this "
                + "screen closes, then the packs reload.", GuiTheme.TEXT_MUTED, false));

        CmvConfig config = CmvConfig.get();
        for (AtlasBudget.PackShare share : items.byNamespace()) {
            // Kept so a namespace that has been taken out can still say what it
            // was worth. Once it is gone it contributes nothing and the atlas
            // can no longer be asked.
            config.namespacePixels.put(share.packId(), share.pixels());
        }
        int shown = 0;
        for (AtlasBudget.PackShare share : items.byNamespace()) {
            if (shown++ >= 14) {
                break;
            }
            String namespace = share.packId();
            boolean ignored = config.ignoresNamespace(namespace);
            double percent = items.pixels() == 0
                    ? 0 : 100.0 * share.pixels() / items.pixels();
            rows.add(new HomeRow(String.format("%-3s %-26s %,12d px  (%.0f%%)",
                    ignored ? "off" : "on", namespace, share.pixels(), percent),
                    ignored ? GuiTheme.TEXT_MUTED : GuiTheme.TEXT_DIM, false, () -> {
                config.toggleIgnoredNamespace(namespace);
                namespacesChanged = true;
                rebuildHome();
            }));
        }
        // Something excluded contributes nothing to the atlas any more, so it
        // cannot appear in the list above; it would otherwise be impossible to
        // find again and turn back on.
        for (String namespace : List.copyOf(config.ignoredNamespaces)) {
            boolean listed = items.byNamespace().stream()
                    .anyMatch(share -> share.packId().equals(namespace));
            if (listed) {
                continue;
            }
            Long was = config.namespacePixels.get(namespace);
            String cost = was == null
                    ? "removed from the packs"
                    : String.format("%,d px freed", was);
            rows.add(new HomeRow(String.format("%-3s %-26s %s", "off", namespace, cost),
                    GuiTheme.TEXT_MUTED, false, () -> {
                config.toggleIgnoredNamespace(namespace);
                namespacesChanged = true;
                rebuildHome();
            }));
        }
        if (namespacesChanged) {
            rows.add(new HomeRow("Changes apply when you close this screen.",
                    GuiTheme.ACCENT_BLUE, false));
        }
    }

    private void addAtlasRows(List<HomeRow> rows) {
        AtlasBudget.Result result = AtlasBudget.latest();
        if (result == null) {
            rows.add(new HomeRow(AtlasBudget.measuring()
                    ? "Measuring..." : "Not measured yet.", GuiTheme.TEXT_MUTED, false));
            return;
        }

        for (AtlasBudget.Atlas atlas : result.atlases()) {
            if (atlas.sprites() == 0) {
                continue;
            }
            int side = atlas.effectiveSide();
            String size = atlas.overflows()
                    ? "needs more than " + AtlasBudget.MAX_SIDE
                    : side + "x" + side + (atlas.liveSide() > 0 ? "" : " (needed)");
            rows.add(new HomeRow(String.format("%-11s %,7d sprites   %s   %.0f%% full   "
                            + "room for %,d more px",
                    atlas.name(), atlas.sprites(), size, atlas.fill() * 100, atlas.room()),
                    atlas.overflows() ? GuiTheme.TEXT_ERROR
                            : atlas.tight() ? GuiTheme.TEXT_WARN : GuiTheme.TEXT_DIM, false));
        }

        AtlasBudget.Atlas items = result.items();
        if (items == null) {
            return;
        }
        if (items.overflows()) {
            rows.add(new HomeRow("The item atlas cannot be stitched. The game switches off "
                    + "every pack when it tries.", GuiTheme.TEXT_ERROR, false));
        } else if (items.tight()) {
            rows.add(new HomeRow("The item atlas is on the largest size the game can build. "
                    + "Adding much more will not fit,", GuiTheme.TEXT_WARN, false));
            rows.add(new HomeRow("and when it does not, the game switches off every pack.",
                    GuiTheme.TEXT_WARN, false));
        } else {
            rows.add(new HomeRow(String.format("Item atlas has room for about %,d more "
                            + "pixels before it has to grow.", items.room()),
                    GuiTheme.GOOD, false));
        }
        rows.add(new HomeRow(String.format("Sizes come from the atlases themselves; the "
                        + "sprite list from the game's own loader. %.2f GB in total.",
                result.atlases().stream().mapToLong(AtlasBudget.Atlas::vram).sum() / 1e9),
                GuiTheme.TEXT_MUTED, false));

        rows.add(new HomeRow("", 0, false));
        rows.add(new HomeRow("WHAT FILLS THE ITEM ATLAS", 0, true));
        int shown = 0;
        for (AtlasBudget.PackShare share : items.byPack()) {
            if (shown++ >= 6) {
                break;
            }
            rows.add(new HomeRow(String.format("%-34s %,13d px  (%.0f%%)",
                    shortPack(share.packId()), share.pixels(),
                    items.pixels() == 0 ? 0 : 100.0 * share.pixels() / items.pixels()),
                    GuiTheme.TEXT_DIM, false));
        }
    }

    private int homeLine(GuiGraphicsExtractor graphics, int left, int y,
                         int top, int bottom, String text, int colour) {
        if (y + 11 >= top && y <= bottom) {
            GuiTheme.text(graphics, this.font, trim(text, this.width - left - PAD - 8),
                    left + 6, y, colour, false);
        }
        return y + 11;
    }

    /**
     * The strip above the grid naming the set it is showing.
     *
     * <p>Without it an open set is indistinguishable from a search that
     * happened to match four things, which is the sort of quiet state that
     * makes an interface feel broken.</p>
     */
    private void drawOpenSetBar(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int left = sidebarWidth() + PAD;
        int y = gridTop() - 18;
        String back = "\u2039 All sets";
        int backWidth = this.font.width(back) + 12;
        boolean hover = inside(mouseX, mouseY, left, y, backWidth, 15);
        GuiTheme.chip(graphics, left, y, backWidth, 15, false, hover);
        GuiTheme.text(graphics, this.font, back, left + 6, y + 4,
                hover ? GuiTheme.TEXT_HEADING : GuiTheme.TEXT_DIM, false);
        hits.add(new Hit(left, y, backWidth, 15, () -> {
            closeSet();
            tab = Tab.SETS;
            page = 0;
            rebuild();
        }));

        String clear = "\u2715 Clear";
        int clearWidth = this.font.width(clear) + 12;
        int clearX = left + backWidth + 4;
        boolean clearHover = inside(mouseX, mouseY, clearX, y, clearWidth, 15);
        GuiTheme.chip(graphics, clearX, y, clearWidth, 15, false, clearHover);
        GuiTheme.text(graphics, this.font, clear, clearX + 6, y + 4,
                clearHover ? GuiTheme.TEXT_HEADING : GuiTheme.TEXT_DIM, false);
        // Separate from the way back, because the two are different wishes: one
        // is "show me my sets", the other is "stop narrowing this grid". Coming
        // in from the filter chip, only the second makes sense.
        hits.add(new Hit(clearX, y, clearWidth, 15, () -> {
            closeSet();
            page = 0;
            rebuild();
        }));

        String add = collecting ? "\u2713 Done" : "+ Add models";
        int addWidth = this.font.width(add) + 12;
        int addX = clearX + clearWidth + 4;
        boolean addHover = inside(mouseX, mouseY, addX, y, addWidth, 15);
        GuiTheme.chip(graphics, addX, y, addWidth, 15, collecting, addHover);
        GuiTheme.text(graphics, this.font, add, addX + 6, y + 4,
                collecting || addHover ? GuiTheme.TEXT_HEADING : GuiTheme.TEXT_DIM, false);
        hits.add(new Hit(addX, y, addWidth, 15, () -> {
            collecting = !collecting;
            refreshCollecting();
            page = 0;
            rebuild();
        }));

        String name = openName();
        int textX = addX + addWidth + 8;
        GuiTheme.text(graphics, this.font, name, textX, y + 4,
                GuiTheme.TEXT_HEADING, false);
        int count = (openFavourites ? sets.favourites().size()
                : openSet == null ? 0 : openSet.size());
        // While collecting, the count is the thing being changed, so it says
        // what a click will do rather than only how many there are.
        String tally = count + (count == 1 ? " model" : " models")
                + (collecting ? "  \u2014  click a card to add or remove it" : "");
        GuiTheme.text(graphics, this.font, tally,
                textX + 4 + this.font.width(name), y + 4, GuiTheme.TEXT_MUTED, false);
    }

    // ---------------------------------------------------------------- sets

    private static final int SET_ROW_H = 16;

    /**
     * The list of sets: headings, and the sets filed under them.
     *
     * <p>Names, not pictures. A grid of previews is the right way to choose a
     * model and the wrong way to choose a list — the question here is "which of
     * my sets did I mean", which a name answers and a thumbnail of one of its
     * members does not.</p>
     *
     * <p>The row controls only appear on the row the cursor is over. Every set
     * carrying a rename, a delete and two arrows at all times would be four
     * times as much interface as content.</p>
     */
    private void drawSetsList(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int left = sidebarWidth() + PAD;
        int right = this.width - PAD;
        int top = gridTop();
        int bottom = this.height - FOOTER_H - 4;

        // Pinned directly under the filters, outside the scroll: making a set
        // is what someone does after looking down the list for one that does
        // not exist yet, which is the moment the button would have scrolled
        // away.
        setsListTop = drawSetsToolbar(graphics, mouseX, mouseY, left, top, right);
        int y = setsListTop - setsScroll;

        // Favourites first and always, because it is the list the star writes
        // to and the one most likely to be wanted.
        y = drawSetRow(graphics, mouseX, mouseY, left + 6, y, right,
                STAR_ON + " Favourites", sets.favourites().size(), setsListTop, bottom,
                openFavourites, this::openFavourites, null);

        for (dev.custommodels.cmv.collection.SkinSets.Group group : sets.groups()) {
            if (group.sets.isEmpty() && group.name.isEmpty()) {
                continue;
            }
            if (!group.name.isEmpty()) {
                y = drawGroupHeading(graphics, mouseX, mouseY, left, y, right,
                        setsListTop, bottom, group);
                if (group.collapsed) {
                    continue;
                }
            }
            for (dev.custommodels.cmv.collection.SkinSets.Set set : List.copyOf(group.sets)) {
                final dev.custommodels.cmv.collection.SkinSets.Set target = set;
                y = drawSetRow(graphics, mouseX, mouseY, left + 6, y, right,
                        set.name.isEmpty() ? "(unnamed)" : set.name, set.size(),
                        setsListTop, bottom, openSet == set, () -> openSet(target), target);
            }
        }

        if (sets.setCount() == 0) {
            String message = "No sets yet. Right-click any card to add it to one.";
            GuiTheme.text(graphics, this.font, message,
                    left + 6, Math.max(setsListTop + 24, y + 8), GuiTheme.TEXT_MUTED, false);
        }
        setsContentHeight = y + setsScroll - setsListTop;
    }

    private int setsContentHeight;
    /** Where the scrolling part of the list starts, below the pinned buttons. */
    private int setsListTop;

    private int drawSetsToolbar(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                int left, int y, int right) {
        if (naming != null) {
            GuiTheme.text(graphics, this.font, naming.prompt(), left + 6, y + 2,
                    GuiTheme.TEXT_DIM, false);
            // The box itself is a widget and draws itself; it sits under the
            // search box, which is where the eye already is.
            return y + 22;
        }
        int x = left + 6;
        x = drawSmallButton(graphics, mouseX, mouseY, x, y, "+ Set",
                () -> beginNaming("Name for the new set", name -> {
                    dev.custommodels.cmv.collection.SkinSets.Set made =
                            sets.createSet(name, "");
                    if (made != null) {
                        openSet(made);
                    }
                }));
        drawSmallButton(graphics, mouseX, mouseY, x, y, "+ Heading",
                () -> beginNaming("Name for the new heading", name -> {
                    if (!name.isBlank()) {
                        sets.createGroup(name);
                    }
                }));
        return y + 22;
    }

    private int drawSmallButton(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                int x, int y, String label, Runnable action) {
        int width = this.font.width(label) + 12;
        boolean hover = inside(mouseX, mouseY, x, y, width, 15);
        GuiTheme.chip(graphics, x, y, width, 15, false, hover);
        GuiTheme.text(graphics, this.font, label, x + 6, y + 4,
                hover ? GuiTheme.TEXT_HEADING : GuiTheme.TEXT_DIM, false);
        hits.add(new Hit(x, y, width, 15, action));
        return x + width + 4;
    }

    private int drawGroupHeading(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                 int left, int y, int right, int top, int bottom,
                                 dev.custommodels.cmv.collection.SkinSets.Group group) {
        if (y + SET_ROW_H < top || y > bottom) {
            return y + SET_ROW_H + 4;
        }
        boolean hover = inside(mouseX, mouseY, left, y, right - left, SET_ROW_H);
        // The whole heading is the fold control, not a small triangle beside
        // it: the row is there, it is not doing anything else, and a heading
        // that folds when you click its name is what everything else does.
        String arrow = group.collapsed ? "\u25B8" : "\u25BE";
        GuiTheme.text(graphics, this.font, arrow, left + 2, y + 4,
                hover ? GuiTheme.TEXT_HEADING : GuiTheme.TEXT_DIM, false);
        GuiTheme.text(graphics, this.font, group.name.toUpperCase(Locale.ROOT),
                left + 12, y + 4, GuiTheme.ACCENT_BLUE, false);
        if (group.collapsed) {
            // Say what is inside it, or a folded heading is just a word.
            int count = group.sets.size();
            GuiTheme.text(graphics, this.font,
                    count + (count == 1 ? " set" : " sets"),
                    left + 16 + this.font.width(group.name.toUpperCase(Locale.ROOT)), y + 4,
                    GuiTheme.TEXT_MUTED, false);
        } else {
            GuiTheme.rect(graphics, left + 2, y + 14, right - 2, y + 15, GuiTheme.BORDER);
        }
        hits.add(new Hit(left, y, right - left - (hover ? 72 : 0), SET_ROW_H,
                () -> sets.toggleCollapsed(group)));
        if (hover) {
            drawRowControls(graphics, mouseX, mouseY, right, y, null,
                    () -> sets.moveGroup(group, -1),
                    () -> sets.moveGroup(group, 1),
                    () -> beginNaming("Rename " + group.name,
                            name -> sets.renameGroup(group, name)),
                    () -> sets.deleteGroup(group));
        }
        return y + SET_ROW_H + 4;
    }

    private int drawSetRow(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                           int left, int y, int right, String label, int count,
                           int top, int bottom, boolean selected, Runnable open,
                           dev.custommodels.cmv.collection.SkinSets.Set set) {
        if (y + SET_ROW_H < top || y > bottom) {
            return y + SET_ROW_H;
        }
        boolean hover = inside(mouseX, mouseY, left, y, right - left, SET_ROW_H);
        if (selected || hover) {
            GuiTheme.rect(graphics, left, y, right, y + SET_ROW_H,
                    selected ? GuiTheme.ACCENT_DIM : GuiTheme.CARD_HOVER);
        }
        GuiTheme.text(graphics, this.font, trim(label, right - left - 90), left + 6, y + 4,
                selected ? GuiTheme.TEXT_HEADING : GuiTheme.TEXT_BRIGHT, false);
        String tally = count + (count == 1 ? " model" : " models");
        GuiTheme.textRight(graphics, this.font, tally, right - (hover ? 62 : 6), y + 4,
                GuiTheme.TEXT_MUTED);
        hits.add(new Hit(left, y, right - left - (hover ? 72 : 0), SET_ROW_H, open));
        if (hover && set != null) {
            drawRowControls(graphics, mouseX, mouseY, right, y,
                    () -> openMoveToMenu(set, mouseX, mouseY),
                    () -> sets.moveSet(set, -1),
                    () -> sets.moveSet(set, 1),
                    () -> beginNaming("Rename " + set.name, name -> sets.renameSet(set, name)),
                    () -> {
                        if (openSet == set) {
                            closeSet();
                        }
                        sets.deleteSet(set);
                    });
        }
        return y + SET_ROW_H;
    }

    /**
     * File, up, down, rename, delete — right-aligned on the row.
     *
     * <p>A heading has no file control of its own, so it passes null and gets
     * the other four in the same places, which keeps the two kinds of row
     * lining up.</p>
     */
    private void drawRowControls(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                 int right, int y, Runnable file, Runnable up, Runnable down,
                                 Runnable rename, Runnable delete) {
        String[] glyphs = {"\u21B3", "\u25B2", "\u25BC", "\u270E", "\u2715"};
        Runnable[] actions = {file, up, down, rename, delete};
        int x = right - 70;
        for (int i = 0; i < glyphs.length; i++) {
            final Runnable action = actions[i];
            if (action == null) {
                x += 14;
                continue;
            }
            boolean over = inside(mouseX, mouseY, x, y + 2, 13, 13);
            boolean destructive = i == glyphs.length - 1;
            GuiTheme.text(graphics, this.font, glyphs[i], x + 3, y + 4,
                    over ? (destructive ? GuiTheme.TEXT_ERROR : GuiTheme.TEXT_HEADING)
                            : GuiTheme.TEXT_MUTED, false);
            hits.add(new Hit(x, y + 2, 13, 13, () -> {
                action.run();
                rebuild();
            }));
            x += 14;
        }
    }

    // ------------------------------------------------------ set operations

    private void openSet(dev.custommodels.cmv.collection.SkinSets.Set set) {
        openSet = set;
        openFavourites = false;
        collecting = false;
        refreshCollecting();
        page = 0;
        rebuild();
    }

    private void openFavourites() {
        openSet = null;
        openFavourites = true;
        // Whatever was being filled, it was not this one.
        collecting = false;
        refreshCollecting();
        page = 0;
        rebuild();
    }

    private void closeSet() {
        openSet = null;
        openFavourites = false;
        collecting = false;
        collectingIndex = java.util.Set.of();
        naming = null;
        if (nameBox != null) {
            nameBox.visible = false;
        }
    }

    /** The ids the open set holds, or null when the grid is not inside one. */
    private java.util.Set<String> openMembers() {
        if (collecting) {
            // Everything, so there is something to add from.
            return null;
        }
        if (openFavourites) {
            return new java.util.LinkedHashSet<>(sets.favourites());
        }
        return openSet == null ? null : new java.util.LinkedHashSet<>(openSet.members);
    }

    /** Whether a model is in whichever list is open. */
    private boolean inOpenList(String modelId) {
        return openFavourites ? sets.isFavourite(modelId) : collectingIndex.contains(modelId);
    }

    private void toggleOpenList(String modelId) {
        if (openFavourites) {
            sets.toggleFavourite(modelId);
        } else if (openSet != null) {
            sets.toggleMember(openSet, modelId);
        }
        refreshCollecting();
    }

    /** Keeps the per-frame membership answer in step with the set. */
    private void refreshCollecting() {
        collectingIndex = openSet == null
                ? java.util.Set.of() : new java.util.HashSet<>(openSet.members);
    }

    private String openName() {
        if (openFavourites) {
            return STAR_ON + " Favourites";
        }
        return openSet == null ? "" : (openSet.name.isEmpty() ? "(unnamed)" : openSet.name);
    }

    private void beginNaming(String prompt, java.util.function.Consumer<String> accept) {
        naming = new Naming(prompt, accept);
        nameBox.setValue("");
        nameBox.visible = true;
        setFocused(nameBox);
        nameBox.setFocused(true);
    }

    private void finishNaming(boolean accept) {
        if (naming == null) {
            return;
        }
        String value = nameBox.getValue();
        java.util.function.Consumer<String> action = naming.accept();
        naming = null;
        nameBox.visible = false;
        nameBox.setFocused(false);
        if (accept) {
            action.accept(value);
        }
        rebuild();
    }

    private void drawEmpty(GuiGraphicsExtractor graphics) {
        int centreX = (sidebarWidth() + this.width) / 2;
        int y = gridTop() + 40;
        String message;
        if (index.models().isEmpty()) {
            message = "No models found. Enable a resource pack and press R to rescan.";
        } else if (!applying) {
            message = "Nothing matches the current filters.";
        } else if (selectedStack().isEmpty() && applying) {
            message = "Select an item below to see the wraps that fit it.";
        } else if (category != ItemType.ALL) {
            message = "No " + category.displayName().toLowerCase(Locale.ROOT)
                    + " wraps fit that item.";
        } else if (compatibleOnly) {
            message = "No wrap in this pack is associated with that item.";
        } else {
            message = "Nothing matches the current search.";
        }
        GuiTheme.text(graphics, this.font, message,
                centreX - this.font.width(message) / 2, y, GuiTheme.TEXT_DIM, false);
        if (compatibleOnly && !selectedStack().isEmpty() && applying) {
            String hint = "Turn off \"Compatible only\" to see every wrap in the pack.";
            GuiTheme.text(graphics, this.font, hint,
                    centreX - this.font.width(hint) / 2, y + 14, GuiTheme.TEXT_MUTED, false);
        }
    }

    private void drawScanning(GuiGraphicsExtractor graphics, ScanReport report) {
        int centreX = (sidebarWidth() + this.width) / 2;
        int y = gridTop() + 40;
        String title = "Reading resource packs";
        GuiTheme.text(graphics, this.font, title, centreX - this.font.width(title) / 2, y,
                GuiTheme.TEXT_HEADING, false);
        GuiTheme.text(graphics, this.font, report.phase().label(),
                centreX - this.font.width(report.phase().label()) / 2, y + 14,
                GuiTheme.TEXT_DIM, false);
        GuiTheme.progress(graphics, centreX - 100, y + 30, 200, 4, report.progress());
    }

    private void drawFooter(GuiGraphicsExtractor graphics) {
        int y = this.height - FOOTER_H;
        GuiTheme.rect(graphics, 0, y, this.width, this.height, GuiTheme.DARK_PANEL);
        GuiTheme.rect(graphics, 0, y, this.width, y + 1, GuiTheme.BORDER);
        // The footer is where the "why can't I" answer lives, so in cosmetic
        // mode it has to stop reporting the write path's verdict — that verdict
        // is irrelevant when nothing is being written.
        String reason;
        int reasonColour;
        if (tab == Tab.HOME) {
            reason = "What the packs contain and what is wrong with them.   "
                    + "R rescan";
            reasonColour = GuiTheme.TEXT_DIM;
        } else {
            reason = "Click a model for its details, textures and export options.   "
                    + "R rescan   |   I stats";
            reasonColour = GuiTheme.TEXT_DIM;
        }
        GuiTheme.text(graphics, this.font, trim(reason, this.width - 120), 12, y + 5,
                reasonColour, false);
        GuiTheme.textRight(graphics, this.font, "Esc close", this.width - 12, y + 5,
                GuiTheme.TEXT_MUTED);
    }

    /** The most rows a menu shows before it starts scrolling. */
    private static final int MENU_ROWS = 9;

    /** Title and search line, above the first row. */
    private static final int MENU_HEADER_H = 33;
    /** Between the last row and the rule above the create row. */
    private static final int MENU_GAP = 5;
    private static final int MENU_PAD = 4;

    /** Where the create row sits, and how tall the box has to be for it. */
    public record MenuLayout(int height, int createY) {
    }

    /**
     * The menu's vertical arrangement, from the number of rows in it.
     *
     * <p>One place, because the create row's position and the height of the box
     * were worked out separately and drifted: the row ended up three pixels
     * over the last set in the list. Anything that has to agree is derived from
     * the same arithmetic here.</p>
     */
    public static MenuLayout menuLayout(int bodyRows, boolean hasCreate) {
        int afterRows = MENU_HEADER_H + bodyRows * SET_ROW_H;
        int createY = hasCreate ? afterRows + MENU_GAP : afterRows;
        int height = (hasCreate ? createY + SET_ROW_H : afterRows) + MENU_PAD;
        return new MenuLayout(height, createY);
    }

    /**
     * A short list of names, one of which gets picked.
     *
     * <p>Scrolls rather than growing. Someone with thirty sets would otherwise
     * get a menu taller than the window, whose top and bottom are both off
     * screen — and the number of sets is exactly the thing this feature
     * encourages.</p>
     */
    private void drawMenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        List<MenuRow> rows = filteredRows();
        int shown = Math.min(rows.size(), MENU_ROWS);
        // An empty result still takes a row, for the line that says so.
        int bodyRows = Math.max(shown, 1);
        boolean hasCreate = menuCreate != null;
        int width = 176;

        MenuLayout layout = menuLayout(bodyRows, hasCreate);
        int height = layout.height();
        int x = Math.max(2, Math.min(menuX, this.width - width - 4));
        int y = Math.max(2, Math.min(menuY, this.height - height - 4));
        int rowsTop = y + MENU_HEADER_H;
        int createY = y + layout.createY();

        GuiTheme.rect(graphics, x, y, x + width, y + height, GuiTheme.DARK_PANEL);
        GuiTheme.border(graphics, x, y, width, height, GuiTheme.ACCENT_BLUE);
        GuiTheme.text(graphics, this.font, menuTitle, x + 6, y + 5,
                GuiTheme.ACCENT_BLUE, false);
        if (rows.size() != menuRows.size() || menuRows.size() > MENU_ROWS) {
            GuiTheme.textRight(graphics, this.font, rows.size() + " of " + menuRows.size(),
                    x + width - 6, y + 5, GuiTheme.TEXT_MUTED);
        }

        // The filter line. Typing goes straight here while the menu is up.
        int filterY = y + 16;
        GuiTheme.rect(graphics, x + 4, filterY, x + width - 4, filterY + 13,
                GuiTheme.DARK_SUNKEN);
        GuiTheme.text(graphics, this.font,
                menuFilter.isEmpty() ? "Type to search..." : menuFilter + "_",
                x + 8, filterY + 3,
                menuFilter.isEmpty() ? GuiTheme.TEXT_MUTED : GuiTheme.TEXT_BRIGHT, false);

        int first = Math.max(0, Math.min(menuScroll, Math.max(0, rows.size() - shown)));
        menuScroll = first;
        int rowY = rowsTop;
        for (int i = first; i < first + shown; i++) {
            MenuRow row = rows.get(i);
            boolean hover = inside(mouseX, mouseY, x + 2, rowY, width - 4, SET_ROW_H);
            if (hover) {
                GuiTheme.rect(graphics, x + 2, rowY, x + width - 2, rowY + SET_ROW_H,
                        GuiTheme.CARD_HOVER);
            }
            GuiTheme.text(graphics, this.font, row.ticked() ? "\u2714" : " ", x + 6, rowY + 4,
                    GuiTheme.GOOD, false);
            GuiTheme.text(graphics, this.font, trim(row.label(), width - 30), x + 18,
                    rowY + 4, hover ? GuiTheme.TEXT_HEADING : GuiTheme.TEXT_BRIGHT, false);
            final Runnable action = row.action();
            hits.add(new Hit(x + 2, rowY, width - 4, SET_ROW_H, () -> {
                action.run();
                closeMenu();
                rebuild();
            }));
            rowY += SET_ROW_H;
        }
        if (rows.isEmpty()) {
            GuiTheme.text(graphics, this.font, "No match", x + 8, rowY + 4,
                    GuiTheme.TEXT_MUTED, false);
        }

        // The create row, which uses what has been typed as the name. Typing a
        // name that does not exist yet and pressing it is the quickest way to
        // make one, and with hundreds of sets it is also the most likely.
        if (hasCreate) {
            GuiTheme.rect(graphics, x + 4, createY - 3, x + width - 4, createY - 2,
                    GuiTheme.BORDER);
            String label = menuFilter.isBlank()
                    ? "+ " + menuCreateLabel + "..."
                    : "+ " + menuCreateLabel + " \"" + menuFilter.strip() + "\"";
            boolean hover = inside(mouseX, mouseY, x + 2, createY, width - 4, SET_ROW_H);
            if (hover) {
                GuiTheme.rect(graphics, x + 2, createY, x + width - 2, createY + SET_ROW_H,
                        GuiTheme.CARD_HOVER);
            }
            GuiTheme.text(graphics, this.font, trim(label, width - 14), x + 8, createY + 4,
                    hover ? GuiTheme.TEXT_HEADING : GuiTheme.ACCENT_BLUE, false);
            final java.util.function.Consumer<String> create = menuCreate;
            final String typed = menuFilter.strip();
            hits.add(new Hit(x + 2, createY, width - 4, SET_ROW_H, () -> {
                closeMenu();
                create.accept(typed);
                rebuild();
            }));
        }
    }

    /** The rows whose names contain what has been typed. */
    private List<MenuRow> filteredRows() {
        if (menuFilter.isBlank()) {
            return menuRows;
        }
        String needle = menuFilter.strip().toLowerCase(Locale.ROOT);
        List<MenuRow> out = new ArrayList<>();
        for (MenuRow row : menuRows) {
            if (row.label().toLowerCase(Locale.ROOT).contains(needle)) {
                out.add(row);
            }
        }
        return out;
    }

    private boolean menuOpen() {
        return !menuRows.isEmpty();
    }

    private void closeMenu() {
        menuRows = List.of();
        menuTitle = "";
        menuScroll = 0;
        menuFilter = "";
        menuCreate = null;
        menuCreateLabel = "";
    }

    private void openMenu(String title, List<MenuRow> rows, String createLabel,
                          java.util.function.Consumer<String> create, int x, int y) {
        menuTitle = title;
        menuRows = rows;
        menuCreateLabel = createLabel;
        menuCreate = create;
        menuX = x;
        menuY = y;
        menuScroll = 0;
        menuFilter = "";
    }

    private int lastMouseX;
    private int lastMouseY;

    /**
     * The theme picker.
     *
     * <p>Reuses the same popup as the set menus, so it filters and scrolls like
     * they do. Applying repoints the palette immediately, but the home rows
     * cached their colours when they were built, so they are rebuilt too or the
     * page would keep the old ones until something else changed.</p>
     */
    private final dev.custommodels.cmv.gui.ColourPicker picker =
            new dev.custommodels.cmv.gui.ColourPicker();

    private void openPrimaryPicker() {
        picker.place(this.width, this.height);
        picker.open("Primary colour", GuiTheme.primary(), chosen -> {
            CmvConfig.get().setCustomTheme(chosen, GuiTheme.secondary());
            rebuildHome();
        });
    }

    private void openSecondaryPicker() {
        picker.place(this.width, this.height);
        picker.open("Secondary colour", GuiTheme.secondary(), chosen -> {
            CmvConfig.get().setCustomTheme(GuiTheme.primary(), chosen);
            rebuildHome();
        });
    }

    private void openPaletteMenu() {
        List<MenuRow> rows = new ArrayList<>();
        for (GuiTheme.Palette option : GuiTheme.Palette.values()) {
            rows.add(new MenuRow(option.label,
                    !GuiTheme.isCustom() && GuiTheme.palette() == option, () -> {
                // Seeds the two colours from the preset rather than switching to
                // a fixed scheme, so a ready-made pair is a starting point that
                // can then be nudged rather than a separate kind of setting.
                CmvConfig.get().setPalette(option);
                CmvConfig.get().setCustomTheme(GuiTheme.primary(), GuiTheme.secondary());
                rebuildHome();
                closeMenu();
            }));
        }
        openMenu("Theme", rows, "", null, lastMouseX + 4, lastMouseY + 4);
    }

    private void addToNewSet(String name, String modelId) {
        dev.custommodels.cmv.collection.SkinSets.Set made = sets.createSet(name, "");
        if (made != null) {
            sets.toggleMember(made, modelId);
        }
    }

    /**
     * The sets, as a filter over whatever grid is open.
     *
     * <p>Reaches exactly the state the sets tab reaches, so a set narrowed to
     * from here and one opened from the list are the same thing afterwards —
     * including the strip above the grid that says which set it is.</p>
     */
    private void openSetFilterMenu(int x, int y) {
        List<MenuRow> rows = new ArrayList<>();
        rows.add(new MenuRow("All sets", !inSet(), () -> {
            closeSet();
            page = 0;
        }));
        rows.add(new MenuRow(STAR_ON + " Favourites", openFavourites, this::openFavourites));
        for (dev.custommodels.cmv.collection.SkinSets.Set set : sets.allSets()) {
            final dev.custommodels.cmv.collection.SkinSets.Set target = set;
            rows.add(new MenuRow(set.name.isEmpty() ? "(unnamed)" : set.name,
                    openSet == set, () -> openSet(target)));
        }
        // Nothing to create here: this is a filter, and a set made from a
        // filter would have nothing in it.
        openMenu("Filter by set", rows, "", null, x, y);
    }

    /** Which lists this model is in, and the chance to make another. */
    private void openAddToMenu(String modelId, int x, int y) {
        List<MenuRow> rows = new ArrayList<>();
        rows.add(new MenuRow(STAR_ON + " Favourites", sets.isFavourite(modelId),
                () -> sets.toggleFavourite(modelId)));
        for (dev.custommodels.cmv.collection.SkinSets.Set set : sets.allSets()) {
            final dev.custommodels.cmv.collection.SkinSets.Set target = set;
            rows.add(new MenuRow(set.name.isEmpty() ? "(unnamed)" : set.name,
                    sets.contains(set, modelId), () -> sets.toggleMember(target, modelId)));
        }
        // Making a set out of the model you were looking at is how most of them
        // get made, so it is offered here and not only on the sets tab.
        openMenu("Add to", rows, "New set", typed -> {
            if (typed.isBlank()) {
                beginNaming("Name for the new set", name -> addToNewSet(name, modelId));
            } else {
                addToNewSet(typed, modelId);
            }
        }, x, y);
    }

    /** The headings this set could be filed under, including none of them. */
    private void openMoveToMenu(dev.custommodels.cmv.collection.SkinSets.Set set,
                                int x, int y) {
        dev.custommodels.cmv.collection.SkinSets.Group current = sets.groupOf(set);
        List<MenuRow> rows = new ArrayList<>();
        rows.add(new MenuRow("No heading", current != null && current.name.isEmpty(),
                () -> sets.moveSetToGroup(set, "")));
        for (dev.custommodels.cmv.collection.SkinSets.Group group : sets.groups()) {
            if (group.name.isEmpty()) {
                continue;
            }
            final String name = group.name;
            rows.add(new MenuRow(name, group == current,
                    () -> sets.moveSetToGroup(set, name)));
        }
        openMenu("Move to", rows, "New heading", typed -> {
            if (typed.isBlank()) {
                beginNaming("Name for the new heading", name -> {
                    if (!name.isBlank()) {
                        sets.moveSetToGroup(set, name);
                    }
                });
            } else {
                sets.moveSetToGroup(set, typed);
            }
        }, x, y);
    }

    private void drawButton(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                            int x, int y, String label, Runnable action) {
        int width = Math.max(50, this.font.width(label) + 16);
        boolean hover = inside(mouseX, mouseY, x, y, width, 16);
        GuiTheme.chip(graphics, x, y, width, 16, false, hover);
        GuiTheme.text(graphics, this.font, label, x + 8, y + 4,
                hover ? GuiTheme.TEXT_HEADING : GuiTheme.TEXT_DIM, false);
        hits.add(new Hit(x, y, width, 16, action));
    }

    /** Breaks a sentence into tooltip lines at word boundaries. */
    private List<Component> wrapTooltip(String text) {
        List<Component> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder(48);
        for (String word : text.split(" ")) {
            if (!line.isEmpty() && this.font.width(line + " " + word) > 260) {
                lines.add(Component.literal(line.toString()));
                line.setLength(0);
            }
            if (!line.isEmpty()) {
                line.append(' ');
            }
            line.append(word);
        }
        if (!line.isEmpty()) {
            lines.add(Component.literal(line.toString()));
        }
        return lines;
    }

    private void drawTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (hoveredModel != null && pendingConfirm == null) {
            drawModelTooltip(graphics, mouseX, mouseY);
            return;
        }
        if (hovered == null || pendingConfirm != null) {
            return;
        }
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(hovered.displayName()));
        // The set is inferred and, on a pack organised by event rather than by
        // collection, often wrong. It stays available under debug, where its
        // evidence is shown alongside it, rather than being presented here as
        // though it were a fact.
        lines.add(Component.literal("Type: " + hovered.type().displayName()));
        lines.add(Component.literal(hovered.compatibleItems().isEmpty()
                ? "Fits: any item (item_model)"
                : "Fits: " + String.join(", ", hovered.compatibleItems())));
        lines.add(Component.literal("Model: " + hovered.model().defaultState().modelId()));
        if (hovered.model().hasMultipleStates()) {
            lines.add(Component.literal("States: " + hovered.model().states().size()
                    + " (kept; the item still changes with use)"));
        }

        ResolvedName name = hovered.name();
        lines.add(Component.literal("Name from: " + name.source().label()
                + " (" + name.confidence().label() + ")"));

        if (CmvConfig.get().debugResolution) {
            lines.add(Component.literal("--- compatibility ---"));
            lines.add(Component.literal(hovered.compatibilityReason(
                    itemIdOf(selectedStack()))));
            lines.add(Component.literal("Writes: " + hovered.componentSummary(
                    itemIdOf(selectedStack()))));
            if (!name.evidence().isEmpty()) {
                lines.add(Component.literal("Name evidence: " + name.evidence()));
            }
        }
        lines.add(Component.literal("Click for details, textures and export"));
        GuiTheme.tooltip(graphics, this.font, lines, mouseX, mouseY);
    }

    private void drawModelTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(hoveredModel.displayName()));
        lines.add(Component.literal(hoveredModel.defaultState().modelId()));
        lines.add(Component.literal((hoveredModel.baseItemIsRegistered()
                ? "Base item: " : "Item definition: ") + hoveredModel.baseItemLabel()));
        if (!hoveredModel.alsoCarriedBy().isEmpty()) {
            List<String> others = hoveredModel.alsoCarriedBy();
            lines.add(Component.literal("Also on: " + (others.size() <= 3
                    ? String.join(", ", others)
                    : String.join(", ", others.subList(0, 3))
                            + " +" + (others.size() - 3) + " more")));
        }
        lines.add(Component.literal("Pack: " + shortPack(hoveredModel.sourcePackId())));
        if (!hoveredModel.customModelData().isEmpty()) {
            lines.add(Component.literal("Custom model data: "
                    + String.join(", ", hoveredModel.customModelData())));
        }
        lines.add(Component.literal("Click for details"));
        GuiTheme.tooltip(graphics, this.font, lines, mouseX, mouseY);
    }

    private void drawStatus(GuiGraphicsExtractor graphics) {
        if (status.isEmpty() || System.currentTimeMillis() > statusUntil) {
            return;
        }
        int width = this.font.width(status) + 16;
        int x = (this.width - width) / 2;
        int y = gridTop() - 22;
        GuiTheme.rect(graphics, x, y, x + width, y + 18, GuiTheme.DARK_PANEL);
        GuiTheme.border(graphics, x, y, width, 18, GuiTheme.ACCENT_BLUE);
        GuiTheme.text(graphics, this.font, status, x + 8, y + 5, GuiTheme.TEXT_HEADING, false);
    }

    // ----------------------------------------------------------- operations

    @Override
    public void onClose() {
        if (looseParent != null) {
            McCompat.setScreen(looseParent);
            return;
        }
        super.onClose();
    }

    // ------------------------------------------------------ cosmetic skins

    private static String customNameOf(ItemStack stack) {
        var name = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME);
        return name == null ? "" : name.getString();
    }

    /**
     * What to call a pack on screen.
     *
     * <p>Its own name from {@code pack.mcmeta} rather than the zip's filename,
     * which is whatever the person who downloaded it happened to save it as.
     * Only ever for display — filtering still compares the real id, so two
     * packs that call themselves the same thing stay separate.</p>
     */
    private static String shortPack(String packId) {
        return McCompat.packDisplayName(packId);
    }

    /**
     * Steps a chip through its options and then back to "any", so every value
     * is reachable by clicking and none of them is a dead end.
     */
    private static String cycle(List<String> values, String current) {
        if (values.isEmpty()) {
            return "";
        }
        int at = values.indexOf(current);
        return at + 1 >= values.size() ? "" : values.get(at + 1);
    }

    private static String shortName(String itemId) {
        int colon = itemId.indexOf(':');
        return (colon >= 0 ? itemId.substring(colon + 1) : itemId).replace('_', ' ');
    }


    private void showStatus(String message) {
        this.status = message;
        this.statusUntil = System.currentTimeMillis() + 4000;
    }

    // ---------------------------------------------------------- interaction

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event,
                                double dragX, double dragY) {
        // Dragging is what makes the wheel usable: a colour is chosen by sliding
        // around it, not by a series of separate clicks.
        if (picker.isOpen() && picker.drag((int) event.x(), (int) event.y())) {
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        picker.release();
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int mouseX = (int) event.x();
        int mouseY = (int) event.y();

        // The picker is modal: it is drawn over everything and takes the click
        // before anything underneath can claim it.
        if (picker.isOpen()) {
            picker.click(mouseX, mouseY);
            McCompat.playClick();
            return true;
        }

        // The menu owns every click while it is up: one on a row picks it, one
        // anywhere else puts the menu away.
        if (menuOpen()) {
            // Backwards, so the topmost thing drawn wins. The menu is drawn
            // over the grid, and a card's star underneath it would otherwise
            // take a click meant for a row.
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit hit = hits.get(i);
                if (inside(mouseX, mouseY, hit.x(), hit.y(), hit.width(), hit.height())) {
                    hit.action().run();
                    McCompat.playClick();
                    return true;
                }
            }
            closeMenu();
            return true;
        }

        if (event.button() == 1) {
            String modelId = hovered != null
                    ? hovered.model().defaultState().modelId()
                    : hoveredModel != null ? hoveredModel.defaultState().modelId() : "";
            if (!modelId.isEmpty()) {
                openAddToMenu(modelId, mouseX + 4, mouseY + 4);
                McCompat.playClick();
                return true;
            }
        }

        if (pendingConfirm == null) {
            switch (pager.clicked(mouseX, mouseY)) {
                case FIRST -> {
                    goToPage(0);
                    return true;
                }
                case PREVIOUS -> {
                    goToPage(page - 1);
                    return true;
                }
                case NEXT -> {
                    goToPage(page + 1);
                    return true;
                }
                case LAST -> {
                    goToPage(totalPages() - 1);
                    return true;
                }
                case NONE -> {
                    // Falls through to the rest of the screen.
                }
            }
        }

        // The confirmation owns every click while it is up.
        if (pendingConfirm != null) {
            for (Hit hit : hits) {
                if (inside(mouseX, mouseY, hit.x(), hit.y(), hit.width(), hit.height())) {
                    hit.action().run();
                    McCompat.playClick();
                    return true;
                }
            }
            return true;
        }

        for (Hit hit : navHits) {
            if (inside(mouseX, mouseY, hit.x(), hit.y(), hit.width(), hit.height())) {
                hit.action().run();
                McCompat.playClick();
                return true;
            }
        }
        for (Hit hit : hits) {
            if (inside(mouseX, mouseY, hit.x(), hit.y(), hit.width(), hit.height())) {
                hit.action().run();
                McCompat.playClick();
                return true;
            }
        }
        for (SlotHit slot : slotHits) {
            if (inside(mouseX, mouseY, slot.x(), slot.y(), SLOT, SLOT)) {
                selectedSlot = slot.slot();
                page = 0;
                rebuild();
                McCompat.playClick();
                return true;
            }
        }
        // While a set is being filled, a card's click is about the set. Both
        // tabs behave the same way here on purpose: what the card does is
        // decided by what the screen is for at that moment, not by which tab
        // it happens to be on.
        if (collecting && (hovered != null || hoveredModel != null)) {
            String modelId = hovered != null
                    ? hovered.model().defaultState().modelId()
                    : hoveredModel.defaultState().modelId();
            if (!modelId.isEmpty()) {
                toggleOpenList(modelId);
                McCompat.playClick();
                return true;
            }
        }
        if (hoveredModel != null) {
            McCompat.playClick();
            McCompat.setScreen(new DetailScreen(hoveredModel, this));
            return true;
        }
        if (hovered != null) {
            McCompat.playClick();
            // A catalogue card opens what it is a card for.
            McCompat.setScreen(new DetailScreen(hovered.model(), this));
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (deltaY != 0 && menuOpen()) {
            // The menu is on top, so it takes the wheel.
            menuScroll = Math.max(0, Math.min(Math.max(0, menuRows.size() - MENU_ROWS),
                    menuScroll - (int) Math.signum(deltaY)));
            return true;
        }
        if (deltaY != 0 && tab == Tab.HOME) {
            int visibleHeight = this.height - FOOTER_H - 4 - gridTop();
            int most = Math.max(0, homeContentHeight - visibleHeight);
            homeScroll = Math.max(0, Math.min(most,
                    homeScroll - (int) Math.signum(deltaY) * 33));
            return true;
        }
        if (deltaY != 0 && tab == Tab.SETS && !inSet()) {
            // A list scrolls; only a grid of pages turns.
            int visibleHeight = this.height - FOOTER_H - 4
                    - (setsListTop > 0 ? setsListTop : gridTop());
            int most = Math.max(0, setsContentHeight - visibleHeight);
            setsScroll = Math.max(0, Math.min(most,
                    setsScroll - (int) Math.signum(deltaY) * SET_ROW_H * 3));
            return true;
        }
        if (deltaY != 0) {
            goToPage(page - (int) Math.signum(deltaY));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        if (menuOpen()) {
            String typed = event.codepointAsString();
            if (!typed.isEmpty()
                    && menuFilter.length()
                            < dev.custommodels.cmv.collection.SkinSets.MAX_NAME_LENGTH) {
                menuFilter += typed;
                menuScroll = 0;
            }
            return true;
        }
        return super.charTyped(event);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (picker.isOpen() && event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            picker.close();
            return true;
        }
        if (menuOpen()) {
            // The menu has the keyboard while it is up, so that searching it
            // does not also type into the search box behind it.
            if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                closeMenu();
                return true;
            }
            if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE) {
                if (!menuFilter.isEmpty()) {
                    menuFilter = menuFilter.substring(0, menuFilter.length() - 1);
                    menuScroll = 0;
                }
                return true;
            }
            if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                    || event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
                // Enter takes the only thing left, which after typing most of a
                // name is what was meant.
                List<MenuRow> rows = filteredRows();
                if (rows.size() == 1) {
                    rows.get(0).action().run();
                    closeMenu();
                    rebuild();
                }
                return true;
            }
            return true;
        }
        if (naming != null) {
            // The name box owns the keyboard while it is up, so that typing a
            // set called "e" does not also fire whatever "e" is bound to.
            if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                finishNaming(false);
                return true;
            }
            if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                    || event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
                finishNaming(true);
                return true;
            }
            // Everything else goes to the box, which has the focus.
            return super.keyPressed(event);
        }
        if (pendingConfirm != null) {
            if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                pendingConfirm = null;
                return true;
            }
            return super.keyPressed(event);
        }
        // Typing belongs to the search box while it has focus.
        if (searchBox != null && searchBox.isFocused()
                && event.key() != org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            return super.keyPressed(event);
        }
        switch (event.key()) {
            case org.lwjgl.glfw.GLFW.GLFW_KEY_R -> {
                index.refresh();
                rebuild();
                return true;
            }
            case org.lwjgl.glfw.GLFW.GLFW_KEY_I -> {
                showStats = !showStats;
                return true;
            }
            case org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT -> {
                goToPage(page + 1);
                return true;
            }
            case org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT -> {
                goToPage(page - 1);
                return true;
            }
            case org.lwjgl.glfw.GLFW.GLFW_KEY_HOME -> {
                goToPage(0);
                return true;
            }
            case org.lwjgl.glfw.GLFW.GLFW_KEY_END -> {
                goToPage(totalPages() - 1);
                return true;
            }
            default -> {
                return super.keyPressed(event);
            }
        }
    }

    @Override
    public void tick() {
        if (tab == Tab.HOME && index.report().isRunning()) {
            // A scan in progress changes what this page says every tick.
            rebuildHome();
        }
        if (searchPendingSince > 0
                && System.currentTimeMillis() - searchPendingSince >= SEARCH_SETTLE_MILLIS) {
            searchPendingSince = 0;
            rebuild();
        }
        // The selected item can change under the interface — durability ticks,
        // a hopper takes it, the server sends an update. Rebuilding on a change
        // of item id keeps the compatible list honest without polling anything
        // expensive.
        String itemId = itemIdOf(selectedStack());
        if (!itemId.equals(lastItemId)) {
            lastItemId = itemId;
            searchPendingSince = 0;
            rebuild();
        }
        super.tick();
    }

    private String lastItemId = "";
    /** When the search box last changed, or 0 when the list is up to date. */
    private long searchPendingSince;

    /** How long typing must pause before the list is rebuilt. */
    private static final long SEARCH_SETTLE_MILLIS = 120;

    // -------------------------------------------------------------- helpers

    private static boolean inside(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private static String shortId(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    private String trim(String text, int maxWidth) {
        if (this.font.width(text) <= maxWidth) {
            return text;
        }
        StringBuilder builder = new StringBuilder();
        int budget = maxWidth - this.font.width("...");
        for (char c : text.toCharArray()) {
            if (this.font.width(builder.toString() + c) > budget) {
                break;
            }
            builder.append(c);
        }
        return builder + "...";
    }
}
