package dev.custommodels.cmv.gui;

import com.mojang.blaze3d.platform.NativeImage;
import dev.custommodels.cmv.compat.McCompat;
import dev.custommodels.cmv.core.CmvMod;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.function.IntConsumer;

/**
 * A colour wheel, for choosing the two colours the rest of the theme is built
 * from.
 *
 * <p>The wheel is hue around and saturation outwards, with lightness on a
 * slider beside it, which is the arrangement most people have already used
 * somewhere else. It is drawn from a texture built once rather than from
 * rectangles: a 128 pixel disc is sixteen thousand pixels, and asking the
 * screen to fill sixteen thousand one-pixel rectangles every frame to show a
 * picture that never changes would cost more than everything else on screen put
 * together.
 *
 * <p>Only the wheel is a texture. The lightness slider is a strip of a hundred
 * or so rectangles because it has to be repainted whenever the hue moves, and
 * at that size the cost is nothing.
 */
public final class ColourPicker {

    /** Side of the wheel texture, in pixels. Also its size on screen. */
    private static final int WHEEL = 128;
    private static final int SLIDER_W = 14;
    private static final int GAP = 8;
    private static final int PAD = 10;
    private static final int SWATCH = 14;

    private static Identifier wheelTexture;

    private boolean open;
    private String title = "";
    private IntConsumer accept;
    private float hue;
    private float saturation;
    private float lightness = 0.5F;
    private boolean draggingWheel;
    private boolean draggingSlider;

    private int x;
    private int y;

    public boolean isOpen() {
        return open;
    }

    /**
     * Shows the picker.
     *
     * @param start  the colour to begin on, so reopening does not lose the
     *               choice already made
     * @param accept called with the new colour every time it changes, because a
     *               theme picker that only applies on OK is one you cannot see
     *               the effect of while choosing
     */
    public void open(String title, int start, IntConsumer accept) {
        this.title = title;
        this.accept = accept;
        float[] hsl = GuiTheme.toHsl(start & 0xFFFFFF);
        this.hue = hsl[0];
        this.saturation = hsl[1];
        this.lightness = Math.max(0.12F, Math.min(0.92F, hsl[2]));
        this.open = true;
    }

    public void close() {
        open = false;
        accept = null;
        draggingWheel = false;
        draggingSlider = false;
    }

    public int width() {
        return WHEEL + GAP + SLIDER_W + PAD * 2;
    }

    public int height() {
        return WHEEL + PAD * 2 + 34;
    }

    /** Centres the panel on the screen it is drawn over. */
    public void place(int screenWidth, int screenHeight) {
        x = (screenWidth - width()) / 2;
        y = (screenHeight - height()) / 2;
    }

    public int current() {
        return GuiTheme.fromHsl(hue, saturation, lightness);
    }

    // ------------------------------------------------------------- drawing

    public void draw(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
        if (!open) {
            return;
        }
        GuiTheme.rect(graphics, x, y, x + width(), y + height(), GuiTheme.DARK_PANEL);
        GuiTheme.border(graphics, x, y, width(), height(), GuiTheme.ACCENT_BLUE);
        GuiTheme.text(graphics, font, title, x + PAD, y + 7, GuiTheme.TEXT_HEADING, false);

        int wheelX = x + PAD;
        int wheelY = y + 22;
        Identifier wheel = wheel();
        if (wheel != null) {
            GuiTheme.texture(graphics, wheel, wheelX, wheelY, WHEEL, WHEEL, 0.0F, 1.0F);
        }

        // Where the current colour sits on the wheel.
        double angle = Math.toRadians(hue);
        int radius = WHEEL / 2;
        int markX = wheelX + radius + (int) Math.round(Math.cos(angle) * saturation * (radius - 2));
        int markY = wheelY + radius + (int) Math.round(Math.sin(angle) * saturation * (radius - 2));
        ring(graphics, markX, markY);

        // The lightness strip, in the hue and saturation now chosen.
        int sliderX = wheelX + WHEEL + GAP;
        for (int i = 0; i < WHEEL; i++) {
            float l = 1.0F - i / (float) (WHEEL - 1);
            GuiTheme.rect(graphics, sliderX, wheelY + i, sliderX + SLIDER_W, wheelY + i + 1,
                    0xFF000000 | GuiTheme.fromHsl(hue, saturation, l));
        }
        GuiTheme.border(graphics, sliderX, wheelY, SLIDER_W, WHEEL, GuiTheme.BORDER);
        int handleY = wheelY + Math.round((1.0F - lightness) * (WHEEL - 1));
        GuiTheme.rect(graphics, sliderX - 2, handleY - 1, sliderX + SLIDER_W + 2, handleY + 2,
                GuiTheme.TEXT_HEADING);

        // The result, and the hex so it can be written down or typed elsewhere.
        int swatchY = wheelY + WHEEL + 6;
        GuiTheme.rect(graphics, wheelX, swatchY, wheelX + 34, swatchY + SWATCH,
                0xFF000000 | current());
        GuiTheme.border(graphics, wheelX, swatchY, 34, SWATCH, GuiTheme.BORDER);
        GuiTheme.text(graphics, font, String.format("#%06X", current()),
                wheelX + 40, swatchY + 4, GuiTheme.TEXT_DIM, false);
    }

    /** A two-tone ring, so the marker shows on light and dark alike. */
    private void ring(GuiGraphicsExtractor graphics, int cx, int cy) {
        GuiTheme.border(graphics, cx - 4, cy - 4, 8, 8, 0xFF000000);
        GuiTheme.border(graphics, cx - 3, cy - 3, 6, 6, 0xFFFFFFFF);
    }

    // ------------------------------------------------------------- input

    /** @return true when the click belonged to the picker */
    public boolean click(int mouseX, int mouseY) {
        if (!open) {
            return false;
        }
        int wheelX = x + PAD;
        int wheelY = y + 22;
        int sliderX = wheelX + WHEEL + GAP;

        if (inside(mouseX, mouseY, wheelX, wheelY, WHEEL, WHEEL)) {
            draggingWheel = true;
            pickFromWheel(mouseX, mouseY, wheelX, wheelY);
            return true;
        }
        if (inside(mouseX, mouseY, sliderX - 2, wheelY, SLIDER_W + 4, WHEEL)) {
            draggingSlider = true;
            pickFromSlider(mouseY, wheelY);
            return true;
        }
        // Anywhere else inside the panel is swallowed; outside it closes.
        if (inside(mouseX, mouseY, x, y, width(), height())) {
            return true;
        }
        close();
        return true;
    }

    public boolean drag(int mouseX, int mouseY) {
        if (!open) {
            return false;
        }
        int wheelX = x + PAD;
        int wheelY = y + 22;
        if (draggingWheel) {
            pickFromWheel(mouseX, mouseY, wheelX, wheelY);
            return true;
        }
        if (draggingSlider) {
            pickFromSlider(mouseY, wheelY);
            return true;
        }
        return false;
    }

    public void release() {
        draggingWheel = false;
        draggingSlider = false;
    }

    private void pickFromWheel(int mouseX, int mouseY, int wheelX, int wheelY) {
        int radius = WHEEL / 2;
        double dx = mouseX - (wheelX + radius);
        double dy = mouseY - (wheelY + radius);
        double distance = Math.sqrt(dx * dx + dy * dy);
        hue = hueAt(dx, dy);
        // Past the rim still counts as fully saturated, so a drag off the edge
        // does not snap the colour back towards grey.
        saturation = (float) Math.min(1.0, distance / (radius - 2));
        fire();
    }

    private void pickFromSlider(int mouseY, int wheelY) {
        float t = (mouseY - wheelY) / (float) (WHEEL - 1);
        lightness = Math.max(0.04F, Math.min(0.96F, 1.0F - t));
        fire();
    }

    private void fire() {
        if (accept != null) {
            accept.accept(current());
        }
    }

    private static boolean inside(int mx, int my, int bx, int by, int bw, int bh) {
        return mx >= bx && mx < bx + bw && my >= by && my < by + bh;
    }

    // ------------------------------------------------------------- texture

    /**
     * One pixel of the wheel, as ARGB, or transparent outside the disc.
     *
     * <p>Separated from the texture so the thing that actually went wrong can
     * be checked without a graphics device. {@code setPixel} takes ARGB — the
     * same order as everywhere else here, with a separate {@code setPixelABGR}
     * for the other one — and packing the channels the other way round made the
     * wheel display red where it said blue. The hue came from the click angle
     * and was right the whole time, so nothing looked wrong until you picked a
     * colour and got its opposite.</p>
     */
    public static int wheelPixel(int px, int py, int side) {
        int radius = side / 2;
        double dx = px - radius + 0.5;
        double dy = py - radius + 0.5;
        double distance = Math.sqrt(dx * dx + dy * dy);
        if (distance > radius - 1) {
            return 0;
        }
        float h = hueAt(dx, dy);
        float s = (float) Math.min(1.0, distance / (radius - 2));
        return 0xFF000000 | GuiTheme.fromHsl(h, s, 0.5F);
    }

    /** The hue an offset from the wheel's centre stands for. */
    public static float hueAt(double dx, double dy) {
        return (float) ((Math.toDegrees(Math.atan2(dy, dx)) + 360.0) % 360.0);
    }

    /**
     * The wheel, built once and kept.
     *
     * <p>Drawn at full saturation and mid lightness: it is a map of hue and
     * saturation, and showing it at the lightness currently chosen would make
     * the whole thing go black whenever somebody dragged the slider down.</p>
     */
    private static Identifier wheel() {
        if (wheelTexture != null) {
            return wheelTexture;
        }
        try {
            NativeImage image = new NativeImage(WHEEL, WHEEL, false);
            for (int py = 0; py < WHEEL; py++) {
                for (int px = 0; px < WHEEL; px++) {
                    image.setPixel(px, py, wheelPixel(px, py, WHEEL));
                }
            }
            Identifier id = McCompat.modId("colour_wheel");
            McCompat.mc().getTextureManager()
                    .register(id, new DynamicTexture(() -> "cmv-colour-wheel", image));
            wheelTexture = id;
            return id;
        } catch (RuntimeException e) {
            CmvMod.LOGGER.warn("Could not build the colour wheel", e);
            return null;
        }
    }
}
