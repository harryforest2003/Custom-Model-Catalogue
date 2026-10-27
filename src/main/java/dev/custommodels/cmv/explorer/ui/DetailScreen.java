package dev.custommodels.cmv.explorer.ui;

import dev.custommodels.cmv.compat.McCompat;
import dev.custommodels.cmv.core.CmvConfig;
import dev.custommodels.cmv.core.CmvMod;
import dev.custommodels.cmv.core.FigurePlacements;
import dev.custommodels.cmv.explorer.def.Selector;
import dev.custommodels.cmv.explorer.export.ImageExport;
import dev.custommodels.cmv.wrap.WrapPreview;
import dev.custommodels.cmv.wrap.WrapTarget;
import dev.custommodels.cmv.explorer.graph.LogicalModel;
import dev.custommodels.cmv.explorer.graph.ModelState;
import dev.custommodels.cmv.explorer.render.FigurePreview;
import dev.custommodels.cmv.explorer.render.PreviewService;
import dev.custommodels.cmv.explorer.res.ResolvedModel;
import dev.custommodels.cmv.explorer.scan.ExplorerIndex;
import dev.custommodels.cmv.gui.GuiTheme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The inspection view: everything known about one model on the left, a large
 * interactive preview of it on the right.
 *
 * <p>Every value on the left comes from the scan. Where something is an
 * inference rather than a fact — a type guessed from a folder name, a grouping
 * that only file names support — it is labelled as one. Nothing here is
 * invented to fill a gap; a missing value is shown as missing.</p>
 */
public final class DetailScreen extends Screen {

    private static final int MARGIN = 12;
    private static final int HEADER_HEIGHT = 30;
    private static final int LINE = 11;

    private final LogicalModel model;
    private final Screen parent;
    private final ExplorerIndex index = ExplorerIndex.get();

    private int stateIndex;
    private float yaw;
    private float pitch = -8.0F;
    private float zoom = 1.0F;
    private boolean autoRotate = CmvConfig.get().autoRotateDetail;
    /**
     * Which of the two renders the panel is showing.
     *
     * <p>The model render is the default, and the one the card has always
     * opened on.</p>
     */
    private boolean onFigure = CmvConfig.get().figureInDetail;
    /**
     * The other view. Costs nothing until it is asked for: no mannequin is
     * made, and no stack is built, until the panel first draws it.
     */
    private final FigurePreview figure = new FigurePreview();
    private boolean figureDressed;
    /** When the view last stopped turning, for the settle before it sharpens. */
    private long stillSince;
    /** The size the render being shown was drawn at, reported below it. */
    private int shownPixels;
    /** The size being worked towards, and whether that work is still going. */
    private int sharpTarget;
    private boolean sharpening;
    /** Plays the model's frames, until the player picks a state themselves. */
    private boolean cycleStates = CmvConfig.get().cycleDetailStates;
    private float sinceStateChange;
    /**
     * Where the model's animated textures have got to, in game ticks.
     *
     * <p>Counted here rather than taken from the world clock so that the
     * preview keeps animating in a menu, and so that it starts from the
     * beginning each time a model is opened.</p>
     */
    private int textureTick;
    private float sinceTick;
    /** The frame being faded out, or -1 when nothing is fading. */
    private int fadeFrom = -1;
    private float fadeProgress;
    private boolean dragging;
    private double lastMouseX;
    private double lastMouseY;
    private long lastFrameNanos;

    private int infoScroll;
    private int infoContentHeight;
    private String status = "";
    private long statusUntil;

    private final List<Button> buttons = new ArrayList<>();
    private final List<Button> stateButtons = new ArrayList<>();

    /** Progress of a running animation export, or null when idle. */
    private volatile ExportProgress export;

    private record ExportProgress(AtomicInteger frame, int total, String label) {
    }

    public DetailScreen(LogicalModel model, Screen parent) {
        super(Component.literal(model.displayName()));
        this.model = model;
        this.parent = parent;
        this.stateIndex = model.defaultStateIndex();
        // Read here rather than when the figure is first dressed, so the card
        // opens where it was left even on the frame the figure view appears.
        this.placeChosen = FigurePreview.placementByName(
                FigurePlacements.get(placementKey()));
    }

    @Override
    protected void init() {
        // Opening the detail view counts as a viewer being open, so the preview
        // service stays alive if this screen was reached directly.
        index.openViewer();
        lastFrameNanos = System.nanoTime();
    }

    @Override
    public void removed() {
        // The placement is not written here. closeViewer does it, and only when
        // the last viewer screen has really gone: see FigurePlacements.
        index.closeViewer();
        figure.close();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        McCompat.setScreen(parent);
    }

    private ModelState state() {
        return model.states().get(Math.min(stateIndex, model.states().size() - 1));
    }

    // ------------------------------------------------------------- rendering

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                   float partialTick) {
        GuiTheme.setScreenSize(this.width, this.height);
        GuiTheme.rect(graphics, 0, 0, this.width, this.height, GuiTheme.DARK_BG);
        index.previews().beginFrame();
        advanceRotation();

        int splitX = Math.max(260, Math.min(this.width / 2, 420));

        drawHeader(graphics, mouseX, mouseY);
        drawInformation(graphics, mouseX, mouseY, splitX);
        drawPreview(graphics, mouseX, mouseY, splitX);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        drawStatus(graphics);
    }

    /**
     * How long the view must sit still before it is rendered at full size.
     *
     * <p>Long enough that nudging it round by hand does not ask for a large
     * render on every pause between drags, short enough that stopping to look
     * at something feels like it sharpens straight away.</p>
     */
    public static final long SETTLE_MILLIS = 220L;

    /**
     * Whether the view still counts as moving, and so keeps the cheap render.
     *
     * <p>Static and taking its three inputs, so the sequence can be walked:
     * turning is cheap, dragging is cheap, and letting go stays cheap for the
     * settle before the sharp render is asked for. That last part is what
     * makes changing the angle of a paused view free — nudging it round asks
     * for nothing large on every pause between drags, and the sharp render is
     * only ever asked for once, at the angle you actually left it at.</p>
     *
     * @param sinceMotion milliseconds since the view last turned or was dragged
     */
    public static boolean moving(boolean dragging, boolean spinning, long sinceMotion) {
        return dragging || spinning || sinceMotion < SETTLE_MILLIS;
    }

    /** Whether the view is turning, being dragged, or has only just stopped. */
    private boolean moving() {
        return moving(dragging, autoRotate, System.currentTimeMillis() - stillSince);
    }

    /** How long each frame is held when the model's states are played. */
    private static final float SECONDS_PER_STATE = 2.0F;

    /**
     * How long one frame takes to dissolve into the next.
     *
     * <p>A pull stage is a different model, not a moved one — the string, the
     * arrow and often the whole bow are drawn afresh — so cutting between them
     * lands as a jolt however well the frames are ordered. A dissolve costs
     * nothing to render, because both frames are already drawn, and it is what
     * turns four separate pictures into one movement.</p>
     */
    private static final float FADE_SECONDS = 0.45F;

    /**
     * Advances the automatic spin using real elapsed time rather than a frame
     * count, so the rotation runs at the same speed whatever the frame rate.
     */
    private void advanceRotation() {
        long now = System.nanoTime();
        float seconds = (now - lastFrameNanos) / 1_000_000_000.0F;
        lastFrameNanos = now;
        if (autoRotate && !dragging) {
            // A full turn every twenty seconds: slow enough to study, fast
            // enough not to feel stuck.
            yaw = (yaw + seconds * 18.0F) % 360.0F;
        }
        if (dragging || autoRotate) {
            stillSince = System.currentTimeMillis();
        }
        if (onFigure) {
            // The model view's three clocks are the model view's work: a state
            // to step, a dissolve to run, a texture tick to advance, each of
            // which asks the preview service to render. None of it is on
            // screen, so none of it runs — and because they are clocks rather
            // than threads, stopping them is this, and starting them again is
            // nothing. The card comes back to the frame it left.
            return;
        }
        advanceState(seconds);
        advanceFade(seconds);
        advanceTextures(seconds);
    }

    /**
     * Advances the clock the model's animated textures are drawn against.
     *
     * <p>Held to ten steps a second rather than the game's twenty. A step is a
     * render, and while the view is also rotating each one is at a new angle
     * too, so the pair of them decide how much work a single open model asks
     * for. Ten is enough for the fire and shimmer textures these packs use —
     * most of which hold each frame for two ticks or more anyway — and it
     * halves the renders.</p>
     *
     * <p>Nothing here is per-card: the grid draws stills, and a model with no
     * animated texture never reaches the clock at all, because the preview
     * service leaves the tick out of its cache key unless the model has
     * one.</p>
     */
    private void advanceTextures(float seconds) {
        if (!CmvConfig.get().animateDetailTextures) {
            return;
        }
        sinceTick += seconds;
        while (sinceTick >= 0.1F) {
            sinceTick -= 0.1F;
            textureTick += 2;
        }
    }

    /**
     * Steps to the next frame of the model, if it has frames.
     *
     * <p>Nothing here renders anything: it moves the same state index the
     * buttons move, and the preview for a state is drawn and cached exactly as
     * it is when the player clicks through them by hand. A model that has been
     * round its cycle once therefore replays it from the cache.</p>
     *
     * <p>Only models whose states are alternatives are played — see
     * {@link LogicalModel#animationFrames()} — because most models with several
     * states have them as parts drawn together, and stepping through those
     * would take the model apart rather than animate it.</p>
     */
    private void advanceState(float seconds) {
        if (!cycleStates || dragging) {
            return;
        }
        List<Integer> frames = model.animationFrames();
        if (frames.size() < 2) {
            return;
        }
        sinceStateChange += seconds;
        if (sinceStateChange < SECONDS_PER_STATE) {
            return;
        }
        sinceStateChange = 0.0F;
        int at = frames.indexOf(stateIndex);
        fadeFrom = stateIndex;
        fadeProgress = 0.0F;
        stateIndex = frames.get(at < 0 ? 0 : (at + 1) % frames.size());
    }

    /** Advances a dissolve between two frames, if one is running. */
    private void advanceFade(float seconds) {
        if (fadeFrom < 0) {
            return;
        }
        fadeProgress += seconds / FADE_SECONDS;
        if (fadeProgress >= 1.0F) {
            fadeFrom = -1;
            fadeProgress = 0.0F;
        }
    }

    /**
     * Eased, so the dissolve starts and ends gently.
     *
     * <p>A straight ramp reads as a crossfade; this reads as one picture
     * becoming another, which is the difference between the two frames looking
     * like separate images and looking like one movement.</p>
     */
    private static float smooth(float t) {
        float clamped = Math.max(0.0F, Math.min(1.0F, t));
        return clamped * clamped * (3.0F - 2.0F * clamped);
    }

    private void drawHeader(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        GuiTheme.rect(graphics, 0, 0, this.width, HEADER_HEIGHT, GuiTheme.SIDEBAR);
        GuiTheme.rect(graphics, 0, HEADER_HEIGHT - 1, this.width, HEADER_HEIGHT,
                GuiTheme.BORDER);

        buttons.clear();
        Button back = new Button("< Back", MARGIN, 7, this::onClose);
        back.draw(graphics, mouseX, mouseY);
        buttons.add(back);

        String title = model.displayName().toUpperCase(java.util.Locale.ROOT);
        GuiTheme.text(graphics, this.font, title,
                (this.width - this.font.width(title)) / 2, 11,
                GuiTheme.TEXT_HEADING, false);

        GuiTheme.textRight(graphics, this.font, model.type().displayName(),
                this.width - MARGIN, 11, GuiTheme.ACCENT_BLUE);
    }

    // --------------------------------------------------------- left: details

    private void drawInformation(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                 int splitX) {
        int panelTop = HEADER_HEIGHT + MARGIN / 2;
        int panelBottom = this.height - MARGIN / 2;
        GuiTheme.rect(graphics, 0, HEADER_HEIGHT, this.width, this.height, GuiTheme.DARK_BG);
        GuiTheme.rect(graphics, MARGIN / 2, panelTop, splitX - MARGIN / 2, panelBottom,
                GuiTheme.DARK_PANEL);
        GuiTheme.border(graphics, MARGIN / 2, panelTop,
                splitX - MARGIN, panelBottom - panelTop, GuiTheme.BORDER);

        graphics.enableScissor(MARGIN / 2, panelTop, splitX - MARGIN / 2, panelBottom);
        int x = MARGIN;
        int y = panelTop + 6 - infoScroll;
        int width = splitX - MARGIN * 2;
        int startY = y;

        y = section(graphics, "IDENTITY", x, y);
        y = field(graphics, "Name", model.displayName(), x, y, width);
        y = field(graphics, model.baseItemIsRegistered() ? "Base item" : "Item definition id",
                model.baseItemLabel(), x, y, width);
        if (!model.alsoCarriedBy().isEmpty()) {
            // The browse list shows one card per skin; this is what the other
            // routes to it were.
            List<String> others = model.alsoCarriedBy();
            String shown = others.size() <= 4
                    ? String.join(", ", others)
                    : String.join(", ", others.subList(0, 4))
                            + " and " + (others.size() - 4) + " more";
            y = field(graphics, "Also on", shown, x, y, width);
        }
        y = field(graphics, "Item type", typeLine(), x, y, width);
        y = field(graphics, "Namespace", model.namespace(), x, y, width);
        y = field(graphics, "Resource pack",
                McCompat.packDisplayName(model.sourcePackId()), x, y, width);
        if (!model.customModelData().isEmpty()) {
            y = field(graphics, "Custom model data",
                    String.join(", ", model.customModelData()), x, y, width);
        }
        y += 4;

        ModelState current = state();
        ResolvedModel resolved = current.resolved();

        y = section(graphics, "PATHS", x, y);
        y = field(graphics, "Model ID", current.modelId(), x, y, width);
        y = field(graphics, "Model path",
                resolved.exists() ? resolved.archivePath()
                        : resolved.archivePath() + "  (not found)", x, y, width);
        y = field(graphics, "Item definition", model.definitionArchivePath(), x, y, width);

        List<String> textures = resolved.distinctTextures();
        if (textures.isEmpty()) {
            y = field(graphics, "Textures", "none referenced", x, y, width);
        } else {
            for (int i = 0; i < textures.size(); i++) {
                y = field(graphics, i == 0 ? "Textures" : "", textures.get(i), x, y, width);
            }
        }
        if (!resolved.parentChain().isEmpty()) {
            y = field(graphics, "Parent", String.join("  >  ", resolved.parentChain()),
                    x, y, width);
        }
        y += 4;

        if (model.hasMultipleStates()) {
            y = section(graphics, "STATES  (" + model.states().size() + ")", x, y);
            y = drawStateButtons(graphics, mouseX, mouseY, x, y, width);
            y = field(graphics, "Condition", current.conditionText(), x, y, width);
            y = field(graphics, "Grouped by", model.groupReason().description()
                    + "  (" + model.groupReason().confidence().label() + " confidence)",
                    x, y, width);
            y += 4;
        }

        if (!model.mergedReferences().isEmpty()) {
            y = section(graphics, "MERGED REFERENCES", x, y);
            for (String reference : model.mergedReferences()) {
                y = wrapped(graphics, reference, x, y, width, GuiTheme.TEXT_MUTED);
            }
            y += 4;
        }

        List<String> warnings = model.warnings();
        if (!warnings.isEmpty()) {
            y = section(graphics, "WARNINGS", x, y);
            for (String warning : warnings) {
                y = wrapped(graphics, warning, x, y, width, GuiTheme.TEXT_WARN);
            }
            y += 4;
        }

        if (CmvConfig.get().debugResolution) {
            y = section(graphics, "MODEL RESOLUTION", x, y);
            for (String line : resolutionTrace()) {
                y = wrapped(graphics, line, x, y, width, GuiTheme.TEXT_MUTED);
            }
            y += 4;
        }

        y = section(graphics, "COPY", x, y);
        y = drawCopyButtons(graphics, mouseX, mouseY, x, y, width);

        infoContentHeight = y - startY + 8;
        graphics.disableScissor();

        drawInfoScrollbar(graphics, splitX, panelTop, panelBottom);
    }

    private String typeLine() {
        var classification = model.classification();
        String suffix = switch (classification.source()) {
            case ITEM_TAG -> "  (from " + classification.evidence() + ")";
            case ITEM_COMPONENT -> "  (from item components)";
            case ITEM_ID -> "  (from base item)";
            case MODEL_PATH -> "  (inferred from model path)";
            case NONE -> "  (unclassified)";
        };
        return model.type().displayName() + suffix;
    }

    private int drawStateButtons(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                 int x, int y, int width) {
        stateButtons.clear();
        int cursorX = x;
        int cursorY = y;
        for (int i = 0; i < model.states().size(); i++) {
            ModelState candidate = model.states().get(i);
            String label = candidate.label();
            int buttonWidth = this.font.width(label) + 10;
            if (cursorX + buttonWidth > x + width) {
                cursorX = x;
                cursorY += 18;
            }
            boolean active = i == stateIndex;
            boolean hover = inside(mouseX, mouseY, cursorX, cursorY, buttonWidth, 15);
            GuiTheme.chip(graphics, cursorX, cursorY, buttonWidth, 15, active, hover);
            GuiTheme.text(graphics, this.font, label, cursorX + 5, cursorY + 4,
                    active ? GuiTheme.TEXT_HEADING : GuiTheme.TEXT_DIM, false);

            final int target = i;
            stateButtons.add(new Button(label, cursorX, cursorY, buttonWidth, 15,
                    () -> selectState(target)));
            cursorX += buttonWidth + 4;
        }
        return cursorY + 20;
    }

    /**
     * Switching state reuses everything it can. The mesh and textures for the
     * new state may already be cached from a previous visit, and the camera is
     * deliberately left alone so the two states can be compared at the same
     * angle.
     */
    private void selectState(int target) {
        stateIndex = target;
        // The player has taken over. Moving it on again two seconds later
        // would be the interface arguing with them.
        cycleStates = false;
        fadeFrom = -1;
        fadeProgress = 0.0F;
        McCompat.playClick();
    }

    private int drawCopyButtons(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                int x, int y, int width) {
        int cursorX = x;
        int cursorY = y;
        for (CopyAction action : copyActions()) {
            int buttonWidth = this.font.width(action.label()) + 12;
            if (cursorX + buttonWidth > x + width) {
                cursorX = x;
                cursorY += 18;
            }
            Button button = new Button(action.label(), cursorX, cursorY, buttonWidth, 15,
                    action.action());
            button.draw(graphics, mouseX, mouseY);
            buttons.add(button);
            cursorX += buttonWidth + 4;
        }
        return cursorY + 20;
    }

    private record CopyAction(String label, Runnable action) {
    }

    private List<CopyAction> copyActions() {
        ModelState current = state();
        List<CopyAction> actions = new ArrayList<>();
        actions.add(new CopyAction("Model ID", () -> copy(current.modelId(), "Model ID")));
        actions.add(new CopyAction("Model path",
                () -> copy(current.resolved().archivePath(), "Model path")));
        actions.add(new CopyAction("Definition path",
                () -> copy(model.definitionArchivePath(), "Item definition path")));
        List<String> textures = current.resolved().distinctTextures();
        if (!textures.isEmpty()) {
            actions.add(new CopyAction("Texture path",
                    () -> copy(String.join("\n", textures), "Texture path")));
        }
        actions.add(new CopyAction("All information",
                () -> copy(fullReport(), "Full information")));
        return actions;
    }

    // -------------------------------------------------------- right: preview

    private void drawPreview(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                             int splitX) {
        int left = splitX + MARGIN / 2;
        int top = HEADER_HEIGHT + MARGIN / 2;
        int right = this.width - MARGIN / 2;
        // The controls wrap when they will not fit on one line, and the panel
        // gives up the height for the extra row. They used to be a single row
        // that simply stopped drawing when it ran out of width, which quietly
        // dropped whichever control was last on any window narrow enough to
        // matter.
        int controlRows = controlRowCount(right - left);
        int bottom = this.height - MARGIN / 2 - 24 - (controlRows - 1) * CONTROL_ROW_H;

        GuiTheme.rect(graphics, left, top, right, bottom, GuiTheme.DARK_SUNKEN);
        GuiTheme.border(graphics, left, top, right - left, bottom - top, GuiTheme.BORDER);

        int box = Math.min(right - left, bottom - top) - 16;
        int centreX = (left + right) / 2;
        int centreY = (top + bottom) / 2;

        // The same square for both views, worked out once. The figure is
        // given the rectangle the model render uses rather than the whole
        // panel, because the two are meant to occupy one area: flipping
        // between them should change what is drawn and nothing else.
        int drawX = centreX - box / 2;
        int drawY = centreY - box / 2;
        if (onFigure) {
            drawFigure(graphics, drawX, drawY, box, centreX, centreY);
        } else {
            // Small while it turns, the size it is actually displayed at once it
            // stops. A spin asks for a fresh render every three degrees; standing
            // still asks for one and then nothing.
            CmvConfig config = CmvConfig.get();
            int frames = index.previews().animationSteps(model, stateIndex);
            int guiScale = McCompat.guiScale();
            int smallSize = PreviewService.detailPixels(box, guiScale, true, frames,
                    config.maxDetailPixels(), config.maxStillPixels(),
                    PreviewService.cycleBudget());
            int sharpSize = PreviewService.detailPixels(box, guiScale, false, frames,
                    config.maxDetailPixels(), config.maxStillPixels(),
                    PreviewService.cycleBudget());
            // The small render is what is shown until the sharp one is completely
            // ready for this exact angle — every frame of it, for a model whose
            // textures animate. Swapping the moment the first frame landed would
            // drop the view to "Rendering..." on the pause (nothing has been drawn
            // at the new size yet, and the fallback is kept per size) and would
            // then flicker between sharp and soft for a whole cycle. So the sharp
            // one is asked for and quietly accumulates, and the picture changes
            // once, when it is all there.
            int renderSize = smallSize;
            sharpening = false;
            if (!moving() && sharpSize > smallSize) {
                index.previews().detail(model, stateIndex, sharpSize,
                        yaw, pitch, zoom, textureTick);
                if (index.previews().cycleReady(model, stateIndex, sharpSize,
                        yaw, pitch, zoom)) {
                    renderSize = sharpSize;
                } else {
                    sharpening = true;
                }
            }
            shownPixels = renderSize;
            sharpTarget = sharpSize;
            Identifier texture = index.previews()
                    .detail(model, stateIndex, renderSize, yaw, pitch, zoom, textureTick);
            // The frame being left behind, at whatever angle it was last drawn at.
            // Nothing is rendered for it: over half a second of dissolve the view
            // turns about eight degrees, which no one can see through a fade, and
            // asking for the exact angle would double the render rate to correct
            // it.
            Identifier previous = fadeFrom < 0 || fadeFrom == stateIndex ? null
                    : index.previews().lastRendered(model, fadeFrom, renderSize);
            if (previous != null && texture != null) {
                // Both fade: one out as the other comes in. Leaving the old frame
                // opaque underneath would hide the fade until the moment it ends
                // and then take the old bow away all at once, which is the jolt
                // this exists to remove.
                float into = smooth(fadeProgress);
                GuiTheme.fadedTexture(graphics, previous, drawX, drawY,
                        box, box, renderSize, 1.0F - into);
                GuiTheme.fadedTexture(graphics, texture, drawX, drawY,
                        box, box, renderSize, into);
            } else if (texture != null) {
                GuiTheme.texture(graphics, texture, drawX, drawY, box, box, 0.0F, 1.0F);
            } else if (previous != null) {
                // The frame being moved to has not been drawn at this angle yet.
                // Holding the previous one is better than dropping to a message
                // for a frame or two.
                GuiTheme.texture(graphics, previous, drawX, drawY, box, box, 0.0F, 1.0F);
            } else {
                String message = state().resolved().exists()
                        ? "Rendering..." : "Model file not found";
                GuiTheme.text(graphics, this.font, message,
                        centreX - this.font.width(message) / 2, centreY - 4,
                        state().resolved().exists() ? GuiTheme.TEXT_MUTED : GuiTheme.TEXT_ERROR,
                        false);
            }
        }

        String angle = String.format("yaw %.0f   pitch %.0f   zoom %.2fx", yaw, pitch, zoom)
                + (onFigure ? "   " + placement() : "   " + shownPixels + "px"
                        + (sharpening ? " \u2192 " + sharpTarget : ""));
        GuiTheme.text(graphics, this.font, angle, left + 6, bottom - 12,
                GuiTheme.TEXT_MUTED, false);

        drawPreviewControls(graphics, mouseX, mouseY, left, bottom, right);
    }

    private void drawPreviewControls(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                     int left, int bottom, int right) {
        int y = bottom + 5;
        int x = left;

        ExportProgress running = export;
        if (running != null) {
            String label = running.label() + "  frame " + running.frame().get()
                    + " / " + running.total();
            GuiTheme.text(graphics, this.font, label, x, y + 4, GuiTheme.ACCENT_BLUE, false);
            GuiTheme.progress(graphics, x, y + 15, right - left, 3,
                    running.frame().get() / (float) running.total());
            return;
        }

        for (Control control : controls()) {
            int width = this.font.width(control.measure()) + 12;
            if (x > left && x + width > right) {
                x = left;
                y += CONTROL_ROW_H;
            }
            Button button = new Button(control.label(), x, y, width, 15, control.action());
            button.draw(graphics, mouseX, mouseY);
            buttons.add(button);
            x += width + 4;
        }
    }

    /** Height of one row of preview controls, including the gap above the next. */
    private static final int CONTROL_ROW_H = 19;

    /**
     * How many rows the controls need at this width.
     *
     * <p>Measured before the preview is drawn, because the panel has to know
     * how much height to give up. Kept in step with the drawing loop by using
     * the same widths and the same wrap rule.</p>
     */
    private int controlRowCount(int available) {
        int rows = 1;
        int x = 0;
        for (Control control : controls(true)) {
            int width = this.font.width(control.measure()) + 12;
            if (x > 0 && x + width > available) {
                rows++;
                x = 0;
            }
            x += width + 4;
        }
        return rows;
    }

    /**
     * One preview control: what it says, the widest it ever says it, and what
     * it does.
     *
     * <p>The widest form is carried separately because two of these change
     * their own label when clicked, and the panel's height comes from how many
     * rows the labels need. Measured from the label on screen, flipping "Auto
     * spin" to "Pause spin" could add a row and shift the whole preview at the
     * moment of the click; measured from the widest form, the row the controls
     * need never changes and the view never moves.</p>
     */
    private record Control(String label, String measure, Runnable action) {

        Control(String label, Runnable action) {
            this(label, label, action);
        }
    }

    /** The controls under the preview, in the order they are laid out. */
    private List<Control> controls() {
        return controls(false);
    }

    /**
     * The controls under the preview.
     *
     * <p>{@code forMeasuring} asks for every control that can ever appear
     * rather than the ones showing now. The panel's height comes from how many
     * rows the controls need, and the hand switch only exists on the figure
     * view — measured from the live list, flipping views could add a row and
     * move the preview, which is the one thing the two views must not do to
     * each other.</p>
     */
    private List<Control> controls(boolean forMeasuring) {
        List<Control> made = new ArrayList<>(7);
        made.add(new Control(onFigure ? "Show model" : "Show on me",
                wider("Show model", "Show on me"), this::flipView));
        if (forMeasuring) {
            // Named at its widest and never asked where the model would go:
            // working that out needs a figure, and the view this is measuring
            // for is the one that has just put its figure away. Always counted,
            // so neither flipping views nor opening an item with fewer
            // placements can change how many rows the controls need.
            made.add(new Control(widestPlaceName(), this::flipPlacement));
        } else if (onFigure && placeChoosable()) {
            made.add(new Control(placeName(placeNext()), widestPlaceName(),
                    this::flipPlacement));
        }
        made.addAll(List.of(
                new Control(autoRotate ? "Pause spin" : "Auto spin",
                        wider("Pause spin", "Auto spin"), () -> autoRotate = !autoRotate),
                new Control("Reset view", this::resetView),
                new Control("Copy image", this::copyImage),
                new Control("Save image", this::saveImage),
                new Control("Animated preview", this::exportAnimation)));
        return made;
    }

    /** Whichever of two labels takes more room. */
    private String wider(String one, String other) {
        return this.font.width(one) >= this.font.width(other) ? one : other;
    }

    /**
     * Draws the model on your own figure.
     *
     * <p>The stack is built once, the first time the view is asked for, and
     * not per frame: see {@link FigurePreview#show}. A model that cannot be
     * put into anyone's hands says so rather than showing an empty figure,
     * which would look like the model had failed to render.</p>
     */
    private void drawFigure(GuiGraphicsExtractor graphics, int drawX, int drawY,
                            int box, int centreX, int centreY) {
        if (!figureDressed) {
            dressFigure();
        }
        boolean inWorld = McCompat.mc() != null && McCompat.mc().player != null;
        if (!figure.hasItem() || !inWorld) {
            // Two different answers, because they want different things of the
            // player: one is this model, which no item can carry, and the
            // other is this screen, which was opened without a world to take a
            // skin and a figure from.
            String message = !inWorld
                    ? "Join a world to see this on yourself"
                    : "There is no item to show this on";
            GuiTheme.text(graphics, this.font, message,
                    centreX - this.font.width(message) / 2, centreY - 4,
                    GuiTheme.TEXT_MUTED, false);
            return;
        }
        figure.draw(graphics, drawX, drawY, drawX + box, drawY + box, yaw, pitch, zoom);
    }

    /**
     * The item the figure holds or wears: this model, on something that can
     * carry it.
     *
     * <p>Conjured, looked at, and dropped when the screen closes. Nothing is
     * given, applied or written — this mod still applies nothing — but the
     * components are the ones a wrap would write, because a figure dressed by
     * some other means would show a model the player could not actually get
     * that way. See {@link WrapPreview}.</p>
     */
    private ItemStack figureStack() {
        WrapTarget target = index.catalogue().byModel(model.defaultState().modelId());
        String itemId = target != null ? itemToGive(target)
                : model.baseItemIsRegistered() ? model.baseItemId() : "";
        if (itemId.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack base = McCompat.buildStack(itemId, "");
        if (base.isEmpty() || target == null) {
            return base;
        }
        // Undressed rather than nothing when the appearance cannot be written:
        // the shape of the thing on your head is still worth seeing, and the
        // panel beside it already says what the model is.
        ItemStack dressed = WrapPreview.dressed(base, target);
        return dressed == null ? base : dressed;
    }

    /**
     * Which item to put this model on.
     *
     * <p>An item the pack actually gives this model a number on, when it works
     * that way. The compatible list can name an item whose definition mentions
     * the model without giving it a value of its own, and dressing that one
     * produces a stack the appearance then has to refuse.</p>
     */
    private String itemToGive(WrapTarget target) {
        if (target.kind() == WrapTarget.Kind.CUSTOM_MODEL_DATA) {
            for (String item : target.itemsWithValues()) {
                return item;
            }
        }
        if (!target.compatibleItems().isEmpty()) {
            return target.compatibleItems().get(0);
        }
        return model.baseItemIsRegistered() ? model.baseItemId() : "";
    }

    /**
     * Flips between the two views.
     *
     * <p>Letting go of the figure on the way out is what keeps the two from
     * both existing: no mannequin, no stand-in head, nothing to animate. The
     * stack is built again on the way back in, which costs one item lookup.</p>
     */
    private void flipView() {
        onFigure = !onFigure;
        CmvConfig config = CmvConfig.get();
        config.figureInDetail = onFigure;
        config.save();
        if (!onFigure) {
            figure.close();
            figureDressed = false;
        }
    }

    /**
     * Builds the figure's stack and settles where the model goes.
     *
     * <p>Both at once, because the placement is read off the stack: what the
     * item is worn as decides where the card opens, and which placements the
     * control walks.</p>
     */
    private void dressFigure() {
        ItemStack stack = figureStack();
        ResolvedModel resolved = model.defaultState().resolved();
        placeOrder = FigurePreview.placements(FigurePreview.slotFor(stack),
                resolved.transform(THIRD_PERSON_RIGHT),
                resolved.transform(THIRD_PERSON_LEFT),
                resolved.transform(HEAD_CONTEXT));
        EquipmentSlot opening = placeChosen != null ? placeChosen
                : FigurePreview.placeFor(stack, modelWantsOffhand());
        figure.show(stack, FigurePreview.openAt(placeOrder, opening));
        figureDressed = true;
    }

    /**
     * Whether the model is drawn to be worn from the offhand.
     *
     * <p>Read off the model's own display transforms — see {@link
     * FigurePreview#wantsOffhand}. It decides where the card opens and nothing
     * more; the control reaches every placement whatever this says.</p>
     */
    private boolean modelWantsOffhand() {
        ResolvedModel resolved = model.defaultState().resolved();
        return FigurePreview.wantsOffhand(
                resolved.transform(THIRD_PERSON_LEFT),
                resolved.transforms().containsKey(THIRD_PERSON_LEFT),
                resolved.transform(THIRD_PERSON_RIGHT),
                resolved.transforms().containsKey(THIRD_PERSON_RIGHT));
    }

    // Display transform names, spelled as the pack files spell them.
    private static final String THIRD_PERSON_LEFT = "thirdperson_lefthand";
    private static final String THIRD_PERSON_RIGHT = "thirdperson_righthand";
    private static final String HEAD_CONTEXT = "head";

    /**
     * The placement the control would move to, which is what it is labelled
     * with.
     *
     * <p>Dressing first if the figure has not been dressed yet: the cycle is
     * built from the item, so there is nothing to step through until there is
     * a stack. Only ever reached with the figure view open.</p>
     */
    private EquipmentSlot placeNext() {
        if (!figureDressed) {
            dressFigure();
        }
        return FigurePreview.nextPlacement(placeOrder, figure.slot());
    }

    /**
     * Whether the model has more than one placement to be seen in.
     *
     * <p>A model the pack has ruled out of everywhere but one place gets no
     * control, rather than a button that says where it already is.</p>
     */
    private boolean placeChoosable() {
        if (!figureDressed) {
            dressFigure();
        }
        return placeOrder.size() > 1;
    }

    /**
     * Moves the model to the next placement, remembers it, and dresses the
     * figure again.
     *
     * <p>Saved against the model rather than kept for this card, so a model
     * whose default is wrong for it is wrong once. Written on the click, the
     * way the view switch writes its own setting.</p>
     */
    private void flipPlacement() {
        placeChosen = placeNext();
        // Remembered at once, so another card opened on this model agrees with
        // this one, and written when the viewer closes rather than now.
        FigurePlacements.set(placementKey(), placeChosen.getSerializedName());
        figureDressed = false;
    }

    /**
     * What a saved placement is filed under: the model, not the item.
     *
     * <p>One model can be reached through several items and several pieces of
     * custom model data, and where its geometry wants to sit is a property of
     * the geometry. Filing it under the item would ask the same question again
     * for every item that happens to carry it.</p>
     */
    private String placementKey() {
        return model.defaultState().modelId();
    }

    /**
     * The player's own answer about the placement, or null while the card's
     * stands.
     *
     * <p>Loaded from the saved placements when the card opens and written back
     * when the control is clicked, so it is an answer given once per model
     * rather than once per visit.</p>
     */
    private EquipmentSlot placeChosen;

    /**
     * The placements the open model can be seen in, settled when the figure is
     * dressed.
     *
     * <p>Held rather than recomputed so that the button's label, the step it
     * takes and whether it appears at all are all reading one list.</p>
     */
    private List<EquipmentSlot> placeOrder = List.of(EquipmentSlot.MAINHAND);

    private static final String IN_HAND = "Hold in hand";
    private static final String IN_OFFHAND = "Hold in offhand";
    private static final String ON_HEAD = "Wear on head";
    private static final String ON_CHEST = "Wear on chest";
    private static final String ON_LEGS = "Wear on legs";
    private static final String ON_FEET = "Wear on feet";

    /**
     * Every label the placement control can carry.
     *
     * <p>Held as a list so the control can be measured at its widest without
     * knowing which item is open. The panel's height comes from how many rows
     * the labels need, and an item whose cycle happened to contain a longer
     * word would otherwise move the preview the moment it was opened.</p>
     */
    private static final String[] PLACE_NAMES = {
            IN_HAND, IN_OFFHAND, ON_HEAD, ON_CHEST, ON_LEGS, ON_FEET };

    /** What to call a placement on the button that moves to it. */
    private static String placeName(EquipmentSlot where) {
        return switch (where) {
            case OFFHAND -> IN_OFFHAND;
            case HEAD -> ON_HEAD;
            case CHEST -> ON_CHEST;
            case LEGS -> ON_LEGS;
            case FEET -> ON_FEET;
            default -> IN_HAND;
        };
    }

    private String widestPlaceName() {
        String widest = PLACE_NAMES[0];
        for (String name : PLACE_NAMES) {
            if (this.font.width(name) > this.font.width(widest)) {
                widest = name;
            }
        }
        return widest;
    }

    /**
     * Where the figure is carrying the model, and on whose authority.
     *
     * <p>Said out loud because where it opened is a guess twice over: the
     * offhand is inferred from a display transform, and the head is taken from
     * a component that packs routinely contradict. A player who sees wings on
     * their back ought to be told that the model asked for that, not left to
     * wonder why a feather went into the wrong hand — and a player holding
     * something that plainly wants to be a hat ought to see that nothing has
     * decided that for them.</p>
     */
    private String placement() {
        EquipmentSlot where = figure.slot();
        String said = switch (where) {
            case HEAD -> "on your head";
            case CHEST -> "on your chest";
            case LEGS -> "on your legs";
            case FEET -> "on your feet";
            case OFFHAND -> "in your offhand";
            default -> "in your hand";
        };
        if (placeChosen != null) {
            return said + "  (your choice)";
        }
        if (figure.slotFromItem()) {
            // The item is worn there and says so. Nothing to explain.
            return said;
        }
        return where == EquipmentSlot.OFFHAND
                ? said + "  (the model asks for it)" : said;
    }

    private void resetView() {
        yaw = 0;
        pitch = -8.0F;
        zoom = 1.0F;
    }

    private void drawInfoScrollbar(GuiGraphicsExtractor graphics, int splitX,
                                   int panelTop, int panelBottom) {
        int viewport = panelBottom - panelTop;
        if (infoContentHeight <= viewport) {
            return;
        }
        GuiTheme.scrollbar(graphics, splitX - MARGIN / 2 - 7, panelTop,
                panelBottom - panelTop, infoContentHeight, viewport, infoScroll);
    }

    private void drawStatus(GuiGraphicsExtractor graphics) {
        if (status.isEmpty() || System.currentTimeMillis() > statusUntil) {
            return;
        }
        int width = this.font.width(status) + 16;
        int x = (this.width - width) / 2;
        int y = this.height - 40;
        GuiTheme.rect(graphics, x, y, x + width, y + 18, GuiTheme.DARK_PANEL);
        GuiTheme.border(graphics, x, y, width, 18, GuiTheme.ACCENT_BLUE);
        GuiTheme.text(graphics, this.font, status, x + 8, y + 5,
                GuiTheme.TEXT_HEADING, false);
    }

    // ------------------------------------------------------------ text blocks

    private int section(GuiGraphicsExtractor graphics, String title, int x, int y) {
        GuiTheme.text(graphics, this.font, title, x, y, GuiTheme.ACCENT_BLUE, false);
        GuiTheme.rect(graphics, x, y + 10, x + 200, y + 11, GuiTheme.BORDER);
        return y + LINE + 4;
    }

    private int field(GuiGraphicsExtractor graphics, String label, String value,
                      int x, int y, int width) {
        if (!label.isEmpty()) {
            GuiTheme.text(graphics, this.font, label, x, y, GuiTheme.TEXT_MUTED, false);
            y += LINE;
        }
        return wrapped(graphics, value, x + 6, y, width - 6, GuiTheme.TEXT_BRIGHT) + 2;
    }

    /** Wraps a value across lines rather than truncating it: paths matter. */
    private int wrapped(GuiGraphicsExtractor graphics, String value,
                        int x, int y, int width, int colour) {
        if (this.font.width(value) <= width) {
            GuiTheme.text(graphics, this.font, value, x, y, colour, false);
            return y + LINE;
        }
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            line.append(value.charAt(i));
            if (this.font.width(line.toString()) >= width) {
                GuiTheme.text(graphics, this.font, line.toString(), x, y, colour, false);
                y += LINE;
                line.setLength(0);
            }
        }
        if (line.length() > 0) {
            GuiTheme.text(graphics, this.font, line.toString(), x, y, colour, false);
            y += LINE;
        }
        return y;
    }

    /**
     * The step-by-step account of how this model was found, for debug mode.
     * Every line is read back out of the discovery record; none of it is
     * reconstructed after the fact.
     */
    private List<String> resolutionTrace() {
        List<String> lines = new ArrayList<>();
        ModelState current = state();
        lines.add("Base item:  " + model.baseItemId());
        lines.add("Definition: " + model.definitionArchivePath());
        int step = 1;
        for (Selector selector : current.source().path()) {
            lines.add("  " + step++ + ". " + selector.kind().name().toLowerCase()
                    + "  " + selector.describe());
        }
        lines.add("Selected:   " + current.modelId());
        lines.add("Model file: " + current.resolved().archivePath());
        for (String parent : current.resolved().parentChain()) {
            lines.add("  parent:   " + parent);
        }
        for (var slot : current.resolved().textureSlots().entrySet()) {
            lines.add("  #" + slot.getKey() + " -> " + slot.getValue());
        }
        lines.add("Geometry:   " + (current.resolved().generated()
                ? "extruded from " + current.resolved().layers().size() + " sprite layer(s)"
                : current.resolved().elementCount() + " element(s)"));
        lines.add("Gui light:  " + (current.resolved().guiLightFront() ? "front" : "side"));
        lines.add("Group:      " + model.groupReason().name() + " ("
                + model.groupReason().confidence().label() + ")");
        lines.add("Canonical:  " + model.canonicalId());
        lines.add("Geometry hash: " + model.geometryHash());
        lines.add("Texture hash:  " + model.textureHash());
        return lines;
    }

    /** The block of text "Copy all information" puts on the clipboard. */
    private String fullReport() {
        StringBuilder out = new StringBuilder(512);
        ModelState current = state();
        out.append("Name: ").append(model.displayName()).append('\n');
        out.append(model.baseItemIsRegistered() ? "Base Item: " : "Item Definition Id: ")
                .append(model.baseItemLabel()).append('\n');
        out.append("Item Type: ").append(model.type().displayName()).append('\n');
        out.append("Namespace: ").append(model.namespace()).append('\n');
        out.append("Resource Pack: ")
                .append(McCompat.packDisplayName(model.sourcePackId()))
                .append("  (").append(model.sourcePackId()).append(")\n");
        if (!model.customModelData().isEmpty()) {
            out.append("Custom Model Data: ")
                    .append(String.join(", ", model.customModelData())).append('\n');
        }
        out.append('\n');
        out.append("Model ID:\n").append(current.modelId()).append('\n');
        out.append("\nModel Path:\n").append(current.resolved().archivePath()).append('\n');
        out.append("\nItem Definition:\n").append(model.definitionArchivePath()).append('\n');

        List<String> textures = current.resolved().distinctTextures();
        if (!textures.isEmpty()) {
            out.append("\nTextures:\n");
            for (String texture : textures) {
                out.append(texture).append('\n');
            }
        }
        if (!current.resolved().parentChain().isEmpty()) {
            out.append("\nParent Chain:\n")
                    .append(String.join(" > ", current.resolved().parentChain())).append('\n');
        }
        if (model.hasMultipleStates()) {
            out.append("\nStates (").append(model.states().size()).append("):\n");
            for (ModelState candidate : model.states()) {
                out.append("  ").append(candidate.label()).append(": ")
                        .append(candidate.modelId())
                        .append("   [").append(candidate.conditionText()).append("]\n");
            }
            out.append("Grouping reason: ").append(model.groupReason().description())
                    .append(" (").append(model.groupReason().confidence().label())
                    .append(" confidence)\n");
        }
        if (!model.warnings().isEmpty()) {
            out.append("\nWarnings:\n");
            for (String warning : model.warnings()) {
                out.append("  ").append(warning).append('\n');
            }
        }
        return out.toString();
    }


    // ---------------------------------------------------------------- actions




    private void copy(String text, String what) {
        McCompat.copyToClipboard(text);
        showStatus(what + " copied");
    }

    private void showStatus(String message) {
        this.status = message;
        this.statusUntil = System.currentTimeMillis() + 2500;
    }

    private void copyImage() {
        exportStill(true);
    }

    private void saveImage() {
        exportStill(false);
    }

    /**
     * Renders a fresh, larger image of exactly what the preview shows and
     * writes it out. Nothing is read back from the framebuffer, so the result
     * contains the model and no part of the interface.
     */
    private void exportStill(boolean toClipboard) {
        CmvConfig config = CmvConfig.get();
        int size = config.exportSize;
        float exportYaw = yaw;
        float exportPitch = pitch;
        float exportZoom = zoom;
        showStatus("Rendering image...");
        // Textures have to be decoded here, on the client thread: the export
        // renders on a worker, and a worker may not read a resource pack.
        index.previews().prepareTextures(model, stateIndex);

        runOffThread(() -> {
            int[] pixels = index.previews().renderExact(
                    model, stateIndex, size, exportYaw, exportPitch, exportZoom, 0);
            if (pixels == null) {
                post(() -> showStatus("Nothing to render for this model"));
                return;
            }
            ImageExport.Outcome outcome = toClipboard
                    ? ImageExport.exportImage(pixels, size, size, model.displayName())
                    : saveOnly(pixels, size);
            post(() -> showStatus(outcome.message()));
        });
    }

    private ImageExport.Outcome saveOnly(int[] pixels, int size) {
        java.nio.file.Path file = ImageExport.exportDirectory()
                .resolve(ImageExport.safeName(model.displayName(), ".png"));
        try {
            ImageExport.writePng(pixels, size, size, file);
            McCompat.copyToClipboard(file.toString());
            return new ImageExport.Outcome(true, false, file,
                    "Saved " + file.getFileName());
        } catch (Exception e) {
            CmvMod.LOGGER.warn("Could not save preview image", e);
            return new ImageExport.Outcome(false, false, null,
                    "Could not save: " + e.getMessage());
        }
    }

    /**
     * Renders a full turn and writes it as an animated GIF.
     *
     * <p>All of it runs on a worker: a hundred frames of rasterizing and an LZW
     * pass is far too much to do between two frames of the game, and this is
     * exactly the sort of work the whole feature is built to keep off the
     * client thread.</p>
     */
    private void exportAnimation() {
        if (export != null) {
            return;
        }
        CmvConfig config = CmvConfig.get();
        int frames = Math.max(8, Math.min(120, config.exportFrames));
        int size = config.exportSize;
        float exportPitch = pitch;
        float exportZoom = zoom;

        AtomicInteger counter = new AtomicInteger();
        export = new ExportProgress(counter, frames, "Generating animated preview");

        index.previews().prepareTextures(model, stateIndex);
        runOffThread(() -> {
            try {
                List<int[]> rendered = new ArrayList<>(frames);
                for (int i = 0; i < frames; i++) {
                    int[] pixels = index.previews().renderExact(
                            model, stateIndex, size, i * 360.0F / frames,
                            exportPitch, exportZoom, 0);
                    if (pixels == null) {
                        post(() -> showStatus("Nothing to render for this model"));
                        return;
                    }
                    rendered.add(pixels);
                    counter.set(i + 1);
                }
                ImageExport.Outcome outcome = ImageExport.exportAnimation(
                        rendered, size, size, 1000 / 24, GuiTheme.DARK_BG,
                        model.displayName());
                post(() -> showStatus(outcome.message()));
            } finally {
                export = null;
            }
        });
    }

    /** Runs export work on a short-lived daemon thread. */
    private static void runOffThread(Runnable task) {
        Thread thread = new Thread(task, "CMV-Export");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 2);
        thread.start();
    }

    private static void post(Runnable task) {
        McCompat.mc().execute(task);
    }

    // ------------------------------------------------------------ interaction

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int mouseX = (int) event.x();
        int mouseY = (int) event.y();
        for (Button button : stateButtons) {
            if (button.hit(mouseX, mouseY)) {
                button.run();
                return true;
            }
        }
        for (Button button : buttons) {
            if (button.hit(mouseX, mouseY)) {
                button.run();
                McCompat.playClick();
                return true;
            }
        }
        int splitX = Math.max(260, Math.min(this.width / 2, 420));
        if (mouseX > splitX) {
            dragging = true;
            lastMouseX = event.x();
            lastMouseY = event.y();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        dragging = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (dragging) {
            yaw = (yaw - (float) (event.x() - lastMouseX) * 0.6F) % 360.0F;
            pitch = Math.max(-89.0F, Math.min(89.0F,
                    pitch + (float) (event.y() - lastMouseY) * 0.6F));
            lastMouseX = event.x();
            lastMouseY = event.y();
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        int splitX = Math.max(260, Math.min(this.width / 2, 420));
        if (mouseX > splitX) {
            zoom = Math.max(0.35F, Math.min(4.0F, zoom * (deltaY > 0 ? 1.1F : 1 / 1.1F)));
            return true;
        }
        int viewport = this.height - HEADER_HEIGHT - MARGIN;
        int maxScroll = Math.max(0, infoContentHeight - viewport);
        infoScroll = Math.max(0, Math.min(maxScroll,
                infoScroll - (int) Math.signum(deltaY) * 18));
        return true;
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        switch (event.key()) {
            case org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE -> {
                autoRotate = !autoRotate;
                return true;
            }
            case org.lwjgl.glfw.GLFW.GLFW_KEY_HOME -> {
                resetView();
                return true;
            }
            case org.lwjgl.glfw.GLFW.GLFW_KEY_D -> {
                CmvConfig config = CmvConfig.get();
                config.debugResolution = !config.debugResolution;
                config.save();
                showStatus("Debug resolution " + (config.debugResolution ? "on" : "off"));
                return true;
            }
            case org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT -> {
                stateIndex = Math.floorMod(stateIndex - 1, model.states().size());
                return true;
            }
            case org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT -> {
                stateIndex = (stateIndex + 1) % model.states().size();
                return true;
            }
            default -> {
                return super.keyPressed(event);
            }
        }
    }

    // ---------------------------------------------------------------- widgets

    /** A flat button drawn and hit-tested without a vanilla widget. */
    private final class Button {

        private final String label;
        private final int x;
        private final int y;
        private final int width;
        private final int height;
        private final Runnable action;

        Button(String label, int x, int y, Runnable action) {
            this(label, x, y, DetailScreen.this.font.width(label) + 12, 15, action);
        }

        Button(String label, int x, int y, int width, int height, Runnable action) {
            this.label = label;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.action = action;
        }

        void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
            boolean hover = hit(mouseX, mouseY);
            GuiTheme.chip(graphics, x, y, width, height, false, hover);
            GuiTheme.text(graphics, DetailScreen.this.font, label, x + 6, y + 4,
                    hover ? GuiTheme.TEXT_HEADING : GuiTheme.TEXT_DIM, false);
        }

        boolean hit(int mouseX, int mouseY) {
            return inside(mouseX, mouseY, x, y, width, height);
        }

        void run() {
            action.run();
        }
    }

    private static boolean inside(int mouseX, int mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }
}
