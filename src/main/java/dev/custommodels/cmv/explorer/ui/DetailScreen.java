package dev.custommodels.cmv.explorer.ui;

import dev.custommodels.cmv.compat.McCompat;
import dev.custommodels.cmv.core.CmvConfig;
import dev.custommodels.cmv.core.CmvMod;
import dev.custommodels.cmv.explorer.def.Selector;
import dev.custommodels.cmv.explorer.export.ImageExport;
import dev.custommodels.cmv.wrap.WrapTarget;
import dev.custommodels.cmv.explorer.graph.LogicalModel;
import dev.custommodels.cmv.explorer.graph.ModelState;
import dev.custommodels.cmv.explorer.render.PreviewService;
import dev.custommodels.cmv.explorer.res.ResolvedModel;
import dev.custommodels.cmv.explorer.scan.ExplorerIndex;
import dev.custommodels.cmv.gui.GuiTheme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
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
        index.closeViewer();
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
        // dropped whichever control was last — "Give & apply", the one people
        // actually want — on any window narrow enough to matter.
        int controlRows = controlRowCount(right - left);
        int bottom = this.height - MARGIN / 2 - 24 - (controlRows - 1) * CONTROL_ROW_H;

        GuiTheme.rect(graphics, left, top, right, bottom, GuiTheme.DARK_SUNKEN);
        GuiTheme.border(graphics, left, top, right - left, bottom - top, GuiTheme.BORDER);

        int box = Math.min(right - left, bottom - top) - 16;
        int centreX = (left + right) / 2;
        int centreY = (top + bottom) / 2;

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
        int drawX = centreX - box / 2;
        int drawY = centreY - box / 2;
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

        String angle = String.format("yaw %.0f   pitch %.0f   zoom %.2fx", yaw, pitch, zoom)
                + "   " + shownPixels + "px" + (sharpening ? " \u2192 " + sharpTarget : "");
        GuiTheme.text(graphics, this.font, angle, left + 6, bottom - 12,
                GuiTheme.TEXT_MUTED, false);

        drawPreviewControls(graphics, mouseX, mouseY, left, bottom, right);
    }

    private void drawPreviewControls(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                     int left, int bottom, int right) {
        int y = bottom + 5;
        int x = left;
        List<Runnable> handlers = List.of(
                () -> autoRotate = !autoRotate, this::resetView, this::copyImage,
                this::saveImage, this::exportAnimation);
        List<String> labels = controlLabels();
        List<CopyAction> controls = new ArrayList<>(labels.size());
        for (int i = 0; i < labels.size(); i++) {
            controls.add(new CopyAction(labels.get(i), handlers.get(i)));
        }

        ExportProgress running = export;
        if (running != null) {
            String label = running.label() + "  frame " + running.frame().get()
                    + " / " + running.total();
            GuiTheme.text(graphics, this.font, label, x, y + 4, GuiTheme.ACCENT_BLUE, false);
            GuiTheme.progress(graphics, x, y + 15, right - left, 3,
                    running.frame().get() / (float) running.total());
            return;
        }

        for (CopyAction control : controls) {
            int width = this.font.width(control.label()) + 12;
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
        for (String label : controlLabels()) {
            int width = this.font.width(label) + 12;
            if (x > 0 && x + width > available) {
                rows++;
                x = 0;
            }
            x += width + 4;
        }
        return rows;
    }

    private List<String> controlLabels() {
        return List.of(autoRotate ? "Pause spin" : "Auto spin", "Reset view",
                "Copy image", "Save image", "Animated preview");
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
