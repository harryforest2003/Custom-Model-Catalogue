package dev.custommodels.cmv.explorer.ui;

import dev.custommodels.cmv.compat.McCompat;
import dev.custommodels.cmv.core.CmvConfig;
import dev.custommodels.cmv.explorer.graph.ExplorerQuery;
import dev.custommodels.cmv.explorer.graph.LogicalModel;
import dev.custommodels.cmv.explorer.res.ResolvedModel;
import dev.custommodels.cmv.explorer.scan.ExplorerIndex;
import dev.custommodels.cmv.gui.GuiTheme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Two models side by side, at one angle.
 *
 * <p>The partner to finding look-alikes. The point of being shown forty models
 * that resemble the one you were looking at is deciding whether any of them is
 * actually different, and until now that meant opening one, remembering it,
 * going back and opening another — comparing two pictures across a gap of
 * several seconds and a screen transition, which is the one thing human memory
 * is worst at.</p>
 *
 * <p>So both are drawn at the same angle, the same zoom and the same moment of
 * their animations, and all three are shared: turning one turns the other. A
 * difference you can only see from behind is still a difference, and a pair
 * that could be turned independently would hide it behind the work of lining
 * them up again.</p>
 *
 * <p>It also says <em>how</em> they are related, because that is the question
 * underneath the question. Two models sharing a template differ by whatever
 * their author changed; two sharing a texture sheet differ in shape; and two
 * that merely happen to have the same number of cuboids are probably not
 * related at all, which is worth knowing before squinting at them.</p>
 */
public final class CompareScreen extends Screen {

    private static final int HEADER_H = 34;
    private static final int FOOTER_H = 18;
    private static final int PAD = 10;

    private final LogicalModel left;
    private final LogicalModel right;
    private final Screen parent;
    private final ExplorerIndex index = ExplorerIndex.get();

    private float yaw = 30.0F;
    private float pitch = -8.0F;
    private float zoom = 1.0F;
    private boolean autoRotate = CmvConfig.get().autoRotateDetail;
    private boolean dragging;
    private double lastMouseX;
    private double lastMouseY;
    private long lastFrameNanos = System.nanoTime();
    private int textureTick;

    /**
     * Every word on the screen, worked out once.
     *
     * <p>Two models and how they relate cannot change while this is open, and
     * the only thing that can \u2014 whether the figures are turning \u2014 changes
     * when a key is pressed rather than when a frame is drawn. Building any of
     * it per frame is the per-frame work this codebase gates against, and the
     * gate caught it.</p>
     */
    private final String relation;
    private final String leftFacts;
    private final String rightFacts;
    private String hint;

    public CompareScreen(LogicalModel left, LogicalModel right, Screen parent) {
        super(Component.literal("Side by side"));
        this.left = left;
        this.right = right;
        this.parent = parent;
        this.relation = relation(left, right);
        this.leftFacts = facts(left);
        this.rightFacts = facts(right);
        this.hint = hint();
    }

    /** The two numbers worth comparing, as a line. */
    private static String facts(LogicalModel model) {
        if (model == null) {
            return "";
        }
        ResolvedModel resolved = model.defaultState().resolved();
        return resolved.elementCount() + " cuboid(s)      "
                + resolved.textureSlots().size() + " texture slot(s)";
    }

    private String hint() {
        return "Drag to turn both   |   Scroll to zoom both   |   Space "
                + (autoRotate ? "pauses" : "resumes") + "   |   Home resets";
    }

    @Override
    protected void init() {
        // Reached directly from a grid, so it has to hold the preview service
        // open on its own account.
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

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                   float partialTick) {
        GuiTheme.setScreenSize(this.width, this.height);
        GuiTheme.rect(graphics, 0, 0, this.width, this.height, GuiTheme.DARK_BG);
        index.previews().beginFrame();
        advance();

        GuiTheme.rect(graphics, 0, HEADER_H - 1, this.width, HEADER_H, GuiTheme.BORDER);
        GuiTheme.text(graphics, this.font, "SIDE BY SIDE", PAD + 2, 8,
                GuiTheme.TEXT_HEADING, false);
        GuiTheme.text(graphics, this.font, GuiTheme.ellipsise(this.font, relation,
                        this.width - PAD * 2 - 4), PAD + 2, 20,
                GuiTheme.TEXT_DIM, false);

        int top = HEADER_H + PAD;
        int bottom = this.height - FOOTER_H - PAD;
        int half = (this.width - PAD * 3) / 2;
        drawSide(graphics, left, leftFacts, PAD, top, half, bottom);
        drawSide(graphics, right, rightFacts, PAD * 2 + half, top, half, bottom);

        int footer = this.height - FOOTER_H;
        GuiTheme.rect(graphics, 0, footer, this.width, footer + 1, GuiTheme.BORDER);
        GuiTheme.text(graphics, this.font, hint, 12, footer + 5,
                GuiTheme.TEXT_MUTED, false);
        GuiTheme.textRight(graphics, this.font, "Esc back", this.width - 12, footer + 5,
                GuiTheme.TEXT_MUTED);
    }

    /**
     * One side: the model, its name, and the two numbers worth comparing.
     *
     * <p>Cuboids and texture slots rather than a fuller account, because these
     * are the two that differ between near-copies and the two the loosest
     * look-alike tier is built on. Everything else about a model is a tab away
     * in its own detail view.</p>
     */
    private void drawSide(GuiGraphicsExtractor graphics, LogicalModel model, String facts,
                          int x, int top, int width, int bottom) {
        GuiTheme.rect(graphics, x, top, x + width, bottom, GuiTheme.DARK_PANEL);
        GuiTheme.border(graphics, x, top, width, bottom - top, GuiTheme.BORDER);
        if (model == null) {
            return;
        }
        int box = Math.max(32, Math.min(width - 16, bottom - top - 56));
        int drawX = x + (width - box) / 2;
        int drawY = top + 8;
        GuiTheme.rect(graphics, drawX, drawY, drawX + box, drawY + box, GuiTheme.DARK_SUNKEN);

        int size = McCompat.previewPixels(box, CmvConfig.get().maxDetailPixels());
        Identifier texture = index.previews().detail(model, model.defaultStateIndex(),
                size, yaw, pitch, zoom, textureTick);
        if (texture != null) {
            GuiTheme.texture(graphics, texture, drawX, drawY, box, box, 0.0F, 1.0F);
        } else {
            GuiTheme.text(graphics, this.font, "Rendering...",
                    drawX + 6, drawY + box / 2 - 4, GuiTheme.TEXT_MUTED, false);
        }

        int y = drawY + box + 8;
        GuiTheme.text(graphics, this.font,
                GuiTheme.ellipsise(this.font, model.displayName(), width - 16),
                x + 8, y, GuiTheme.TEXT_BRIGHT, false);
        GuiTheme.text(graphics, this.font,
                GuiTheme.ellipsise(this.font, model.defaultState().modelId(), width - 16),
                x + 8, y + 11, GuiTheme.TEXT_MUTED, false);
        GuiTheme.text(graphics, this.font, facts, x + 8, y + 23,
                GuiTheme.TEXT_DIM, false);
    }

    /**
     * How the two are related, in the words the look-alike tiers use.
     *
     * <p>Read off the same facts the tiers are, in the same order, so that what
     * this says and what put the pair in front of you cannot disagree.</p>
     */
    private static String relation(LogicalModel left, LogicalModel right) {
        if (left == null || right == null) {
            return "";
        }
        if (same(left.geometryHash(), right.geometryHash())) {
            return "The same shape, wearing different textures.";
        }
        if (same(left.textureHash(), right.textureHash())) {
            return "The same textures, on a different shape.";
        }
        String template = ExplorerQuery.templateOf(left);
        if (!template.isEmpty() && template.equals(ExplorerQuery.templateOf(right))) {
            return "Both built from " + template;
        }
        for (String word : ExplorerQuery.tokensOf(left)) {
            if (ExplorerQuery.tokensOf(right).contains(word)) {
                // Said plainly rather than claimed as a family: in a pair there
                // is no pack to measure the word against, so all this knows is
                // that both names carry it. "After", because the word is a
                // stem - a plushie and a plushies are both named after plush
                // and neither is called it.
                return "Both named after “" + word + "”.";
            }
        }
        String folder = ExplorerQuery.folderOf(left);
        if (!folder.isEmpty() && folder.equals(ExplorerQuery.folderOf(right))) {
            return "Both filed in " + folder;
        }
        if (ExplorerQuery.sharesASheet(
                new java.util.HashSet<>(left.defaultState().resolved().textureSlots().values()),
                right)) {
            return "Drawn with a texture in common.";
        }
        return "Nothing in common beyond being models — which is an answer too.";
    }

    private static boolean same(String a, String b) {
        return a != null && !a.isEmpty() && a.equals(b);
    }

    /** Turns both figures together, and moves their animations on. */
    private void advance() {
        long now = System.nanoTime();
        float seconds = (now - lastFrameNanos) / 1_000_000_000.0F;
        lastFrameNanos = now;
        if (autoRotate && !dragging) {
            yaw = (yaw + seconds * 24.0F) % 360.0F;
        }
        // One clock for both sides. Two models wearing the same animated sheet
        // must be at the same frame of it, or the difference on show is the
        // animation rather than the models.
        textureTick = (int) (System.currentTimeMillis() / 50L);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        dragging = true;
        lastMouseX = event.x();
        lastMouseY = event.y();
        return true;
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
        if (deltaY != 0) {
            zoom = Math.max(0.35F, Math.min(4.0F, zoom * (deltaY > 0 ? 1.1F : 1 / 1.1F)));
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        switch (event.key()) {
            case org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE -> {
                autoRotate = !autoRotate;
                hint = hint();
                return true;
            }
            case org.lwjgl.glfw.GLFW.GLFW_KEY_HOME -> {
                yaw = 30.0F;
                pitch = -8.0F;
                zoom = 1.0F;
                return true;
            }
            default -> {
                return super.keyPressed(event);
            }
        }
    }
}
