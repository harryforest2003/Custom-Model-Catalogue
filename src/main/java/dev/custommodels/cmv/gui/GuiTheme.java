package dev.custommodels.cmv.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.resources.Identifier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * ===========================================================================
 * THE RENDERING LAYER. The second of two files to check if the build fails.
 * ===========================================================================
 *
 * <p>Every call into {@code GuiGraphicsExtractor} in this mod goes through the
 * handful of wrappers below. No screen calls the graphics object directly. So
 * if Mojang has shuffled the drawing API again, the fix is confined to this
 * file rather than scattered across six screens.</p>
 *
 * <h2>Renames applied for 26.1</h2>
 * <p>{@code GuiGraphics} became {@code GuiGraphicsExtractor}, and its methods
 * dropped their {@code draw*}, {@code render*} and {@code submit*} prefixes,
 * with {@code *String*} names becoming {@code *Text*}. Applied here as:</p>
 * <pre>
 *   drawString            -> text
 *   renderItem            -> item
 *   renderItemDecorations -> itemDecorations
 *   renderTooltip         -> tooltip
 *   fill                  -> fill        (no prefix, unchanged)
 * </pre>
 *
 * <p>These follow the documented rule but are <b>inferred, not verified</b>.
 * If one is wrong: in IntelliJ press Cmd/Ctrl+O, open
 * {@code GuiGraphicsExtractor}, press Cmd/Ctrl+F12 for the method list, and
 * correct the five wrappers below. Nothing else needs touching.</p>
 *
 * <p>Panels and slots are drawn as filled rectangles rather than blitted from
 * the vanilla atlas. Sprite names and blit signatures are among the most
 * volatile parts of the client, and a screen that looks slightly off beats one
 * that will not compile.</p>
 */
public final class GuiTheme {

    public static final int SLOT_SIZE = 18;
    public static final int COLUMNS = 9;

    public static final int LINE_HEIGHT = 10;

    // ---------------------------------------------------------------------
    // The palette.
    //
    // Every colour below is deliberately NOT final. Java inlines a
    // `static final` primitive into its call sites at compile time, so a
    // screen in another class would keep drawing the value that was current
    // when it was compiled and never see a theme change. Non-final means the
    // ~150 existing call sites read the live field and needed no edit.
    //
    // Surfaces climb in five steps from sunken to raised, so depth reads by
    // value alone and the accent is never asked to carry structure. Status
    // colours keep their hue in every theme and move only in lightness, so
    // green still means good on a near-white panel as well as a near-black one.
    // ---------------------------------------------------------------------
    public static int DARK_BG;
    public static int DARK_PANEL;
    public static int PANEL_ALT;
    public static int SIDEBAR;
    public static int DARK_SUNKEN;
    public static int BORDER;
    /** Named for the accent's role, not its hue, so call sites keep reading true. */
    public static int ACCENT_BLUE;
    public static int ACCENT_DIM;

    public static int TEXT_BRIGHT;
    public static int TEXT_DIM;
    public static int TEXT_MUTED;
    public static int TEXT_HEADING;

    // Green means good, amber means look, red means broken — in every theme.
    // The hue is fixed; only the lightness moves, because the same green that
    // reads on a near-black panel disappears on a near-white one.
    public static int GOOD;
    public static int TEXT_WARN;
    public static int TEXT_ERROR;

    public static int HOVER_TINT;
    public static int TRACK;
    /** Dims the screen behind a modal. Tinted to match rather than neutral black. */
    public static int SCRIM;

    public static int CARD_FACE;
    public static int CARD_HOVER;
    public static int CARD_SELECTED;
    public static int CARD_BORDER;
    public static int CARD_BORDER_HOVER;
    public static int CHIP_FACE;
    public static int CHIP_HOVER;
    public static int CHIP_BORDER;
    /** Kept under the old name so existing call sites read the same. */
    public static int ACCENT_LIGHT;

    // The vanilla-shaped raised panel: a light face with dark text and a
    // light/dark bevel. Nothing calls panel(), slot(), hover(), tab() or
    // separator() any more, so these are derived from the theme rather than
    // hand-picked — enough to keep those helpers coherent if one is used again.
    public static int PANEL_FACE;
    public static int PANEL_LIGHT;
    public static int PANEL_DARK;
    public static int SLOT_BG;
    public static int SLOT_LIGHT;
    public static int SLOT_DARK;
    public static int HIGHLIGHT;
    public static int TEXT;
    public static int TEXT_LIGHT;
    public static int ACCENT;
    public static int HEADER_LINE;
    public static int TOOLTIP_BG;
    public static int TOOLTIP_BORDER;

    /**
     * The themes offered in the screen's appearance menu.
     *
     * <p>Each is one hue family for the surfaces plus one accent for anything
     * the player is meant to act on: selection, focus, the active chip, the
     * progress bar. {@code MONO} carries no hue at all and leans on value
     * alone, which is the one that stays legible if a player has trouble
     * separating colours.</p>
     */
    public enum Palette {
        PINK("Pink & Purple",
                0xF0120A1C, 0xFF0C0714, 0xFF150C24, 0xFF1C1030, 0xFF281842,
                0xFF3B2560, 0xFF32204F, 0xFF5C3A8C, 0xFF2A1A42, 0xA0140A22,
                0xFFFF2D95, 0xFF8E2060, 0x28FF6FC0,
                0xFFF3E9FF, 0xFFBCA6D6, 0xFF80699C,
                0xF0140A20, 0xFF7B34A8),
        BLUE("Blue",
                0xF00E1116, 0xFF0B0E13, 0xFF11161C, 0xFF161B22, 0xFF1C232C,
                0xFF2A3441, 0xFF232B36, 0xFF3D4C5E, 0xFF30363D, 0xA0000000,
                0xFF4A9EFF, 0xFF2B5C93, 0x30FFFFFF,
                0xFFE6EDF3, 0xFF9BA7B4, 0xFF6B7785,
                0xF0100010, 0xFF3C2C5C),
        RED("Red",
                0xF0150A0C, 0xFF100608, 0xFF1A0C0F, 0xFF221012, 0xFF301719,
                0xFF4A2226, 0xFF3A1C1F, 0xFF6E3238, 0xFF3A1A1D, 0xA0140A0C,
                0xFFFF3B4E, 0xFF8E1F2C, 0x28FF6F7A,
                0xFFFFE9EA, 0xFFD6A8AC, 0xFF9C6E73,
                0xF01A0A0C, 0xFFA83440),
        GREEN("Green",
                0xF00A140E, 0xFF06100A, 0xFF0C1A11, 0xFF101F16, 0xFF172C20,
                0xFF22452F, 0xFF1C3526, 0xFF326E48, 0xFF1A3A26, 0xA00A1410,
                0xFF3BFF87, 0xFF1F8E4E, 0x286FFFA0,
                0xFFE9FFF0, 0xFFA8D6B8, 0xFF6E9C80,
                0xF00A1A0F, 0xFF34A85E),
        MONO("Black & White",
                0xF00D0D0D, 0xFF070707, 0xFF121212, 0xFF1A1A1A, 0xFF262626,
                0xFF3D3D3D, 0xFF2E2E2E, 0xFF5C5C5C, 0xFF333333, 0xA00A0A0A,
                0xFFFFFFFF, 0xFF5A5A5A, 0x28FFFFFF,
                0xFFF5F5F5, 0xFFB5B5B5, 0xFF7D7D7D,
                0xF0101010, 0xFF8A8A8A),
        AMBER("Amber",
                0xF01A1008, 0xFF130B04, 0xFF1F140A, 0xFF26190C, 0xFF342313,
                0xFF4F3418, 0xFF3D2A16, 0xFF7A5226, 0xFF3A2712, 0xA0180F08,
                0xFFFFA033, 0xFF8A5410, 0x28FFB86B,
                0xFFFFF2E0, 0xFFD9BC94, 0xFFA08863,
                0xF01C1208, 0xFFA86A22),
        TEAL("Teal",
                0xF0071618, 0xFF041012, 0xFF0A1C1F, 0xFF0D2427, 0xFF143336,
                0xFF1C4C50, 0xFF183E42, 0xFF2E7A80, 0xFF16383B, 0xA0061416,
                0xFF2EE6D6, 0xFF12766E, 0x286BFFF0,
                0xFFE4FFFC, 0xFFA0D8D3, 0xFF6E9C99,
                0xF0081A1C, 0xFF27A89E),
        VIOLET("Violet",
                0xF00F0B1F, 0xFF0A0717, 0xFF130E26, 0xFF191230, 0xFF241A44,
                0xFF372A63, 0xFF2C2152, 0xFF55429A, 0xFF261C48, 0xA00E0A1E,
                0xFF8B5CFF, 0xFF4A2E9E, 0x28A98BFF,
                0xFFEFE9FF, 0xFFB4A8D9, 0xFF7D719E,
                0xF0110C22, 0xFF6A4AC0),
        SLATE("Slate",
                0xF0101418, 0xFF0B0E11, 0xFF141920, 0xFF1A2028, 0xFF262E38,
                0xFF3A4552, 0xFF2E3742, 0xFF5A6877, 0xFF313A45, 0xA00E1216,
                0xFF9FC0DB, 0xFF4A6076, 0x28C6DCF0,
                0xFFEDF2F7, 0xFFADBAC7, 0xFF79879A,
                0xF0111519, 0xFF5C6E80),
        MIDNIGHT("Midnight",
                0xF0060A16, 0xFF03060F, 0xFF090E1C, 0xFF0C1224, 0xFF141C33,
                0xFF222E4D, 0xFF1B2440, 0xFF3A4C7A, 0xFF1E2842, 0xA0050912,
                0xFF7EC8FF, 0xFF25538F, 0x289ED8FF,
                0xFFE8F2FF, 0xFFA6BCD9, 0xFF71869E,
                0xF0070C18, 0xFF2F639E),
        /**
         * The one light theme.
         *
         * <p>Everything else here is a dark surface with light text; this is
         * the reverse, which is why the heading and status colours had to stop
         * being fixed. Its tooltip stays dark, because tooltip text is drawn
         * white by the primitive that draws the box.</p>
         */
        PAPER("Paper (Light)",
                0xF0F7F7FA, 0xFFE4E4E9, 0xFFEDEDF2, 0xFFFAFAFC, 0xFFFFFFFF,
                0xFFC8C8D2, 0xFFEFEFF5, 0xFF9A9AAB, 0xFFDCDCE4, 0x50101018,
                0xFF1A5FB4, 0xFF2A6FD0, 0x18000000,
                0xFF14181F, 0xFF4A5160, 0xFF767E8E,
                0xF0202430, 0xFF4A5160, true);

        public final String label;
        final int bg, sunken, sidebar, panel, panelAlt;
        final int border, cardHover, cardBorderHover, track, scrim;
        final int accent, accentDim, hoverTint;
        final int textBright, textDim, textMuted;
        final int tooltipBg, tooltipBorder;
        /** Light surfaces with dark text, which flips the heading and status colours. */
        final boolean light;

        Palette(String label,
                int bg, int sunken, int sidebar, int panel, int panelAlt,
                int border, int cardHover, int cardBorderHover, int track, int scrim,
                int accent, int accentDim, int hoverTint,
                int textBright, int textDim, int textMuted,
                int tooltipBg, int tooltipBorder) {
            this(label, bg, sunken, sidebar, panel, panelAlt, border, cardHover,
                    cardBorderHover, track, scrim, accent, accentDim, hoverTint,
                    textBright, textDim, textMuted, tooltipBg, tooltipBorder, false);
        }

        Palette(String label,
                int bg, int sunken, int sidebar, int panel, int panelAlt,
                int border, int cardHover, int cardBorderHover, int track, int scrim,
                int accent, int accentDim, int hoverTint,
                int textBright, int textDim, int textMuted,
                int tooltipBg, int tooltipBorder, boolean light) {
            this.light = light;
            this.label = label;
            this.bg = bg;
            this.sunken = sunken;
            this.sidebar = sidebar;
            this.panel = panel;
            this.panelAlt = panelAlt;
            this.border = border;
            this.cardHover = cardHover;
            this.cardBorderHover = cardBorderHover;
            this.track = track;
            this.scrim = scrim;
            this.accent = accent;
            this.accentDim = accentDim;
            this.hoverTint = hoverTint;
            this.textBright = textBright;
            this.textDim = textDim;
            this.textMuted = textMuted;
            this.tooltipBg = tooltipBg;
            this.tooltipBorder = tooltipBorder;
        }

        /** Falls back to the default rather than throwing on an unknown name. */
        public static Palette of(String name) {
            if (name != null) {
                for (Palette p : values()) {
                    if (p.name().equalsIgnoreCase(name)) {
                        return p;
                    }
                }
            }
            return PINK;
        }
    }

    // ---------------------------------------------------------------------
    // Deriving a whole scheme from two colours.
    //
    // The ladder below was measured off the Pink & Purple preset rather than
    // invented, because that is the arrangement already known to read: every
    // surface in it sits within a few degrees of one hue, at saturation about
    // a half, on a lightness ramp from 0.05 to 0.39. Rebuilding that ramp
    // around any hue reproduces the same design in a different colour instead
    // of producing eleven unrelated colour schemes.
    //
    // Secondary chooses the hue and how saturated the surfaces are; it does not
    // choose their lightness, because "the panels are a darker version of the
    // secondary" is the whole point. Picking a grey secondary therefore gives
    // the neutral scheme for free, with no special case.
    // ---------------------------------------------------------------------

    /** Lightness of each surface, in the order they stack. */
    private static final float L_SUNKEN = 0.053F;
    private static final float L_BG = 0.075F;
    private static final float L_SIDEBAR = 0.094F;
    private static final float L_PANEL = 0.125F;
    private static final float L_PANEL_ALT = 0.176F;
    private static final float L_TRACK = 0.180F;
    private static final float L_CARD_HOVER = 0.218F;
    private static final float L_BORDER = 0.261F;
    private static final float L_BORDER_HOVER = 0.388F;

    /** Saturation the surfaces use when the secondary is fully saturated. */
    private static final float S_SURFACE = 0.47F;

    private static int customPrimary;
    private static int customSecondary;
    private static boolean custom;

    public static boolean isCustom() {
        return custom;
    }

    public static int primary() {
        return custom ? customPrimary : (current.accent & 0xFFFFFF);
    }

    public static int secondary() {
        return custom ? customSecondary : (current.border & 0xFFFFFF);
    }

    /**
     * Builds the whole scheme from an accent and a surface colour.
     *
     * @param primaryRgb   the accent: selection, focus, the active chip. Used
     *                     at the hue and saturation given, with its lightness
     *                     pulled into a band that stays legible on a dark panel
     * @param secondaryRgb the surface colour. Its hue and saturation are kept
     *                     and its lightness discarded, because every surface
     *                     derives its own from the ladder above
     */
    public static void applyCustom(int primaryRgb, int secondaryRgb) {
        customPrimary = primaryRgb & 0xFFFFFF;
        customSecondary = secondaryRgb & 0xFFFFFF;
        custom = true;

        float[] sec = toHsl(customSecondary);
        float hue = sec[0];
        // A washed-out pick should give washed-out surfaces, so the template's
        // saturation is scaled by how saturated the choice actually was.
        float sat = S_SURFACE * Math.min(1.0F, sec[1] / 0.9F);

        DARK_SUNKEN = opaque(fromHsl(hue, sat, L_SUNKEN));
        DARK_BG = 0xF0000000 | fromHsl(hue, sat * 1.05F, L_BG);
        SIDEBAR = opaque(fromHsl(hue, sat * 1.06F, L_SIDEBAR));
        DARK_PANEL = opaque(fromHsl(hue, sat * 1.06F, L_PANEL));
        PANEL_ALT = opaque(fromHsl(hue, sat, L_PANEL_ALT));
        TRACK = opaque(fromHsl(hue, sat * 0.92F, L_TRACK));
        CARD_HOVER = opaque(fromHsl(hue, sat * 0.90F, L_CARD_HOVER));
        BORDER = opaque(fromHsl(hue, sat * 0.94F, L_BORDER));
        CARD_BORDER_HOVER = opaque(fromHsl(hue, sat * 0.87F, L_BORDER_HOVER));
        SCRIM = 0xA0000000 | fromHsl(hue, sat * 1.15F, 0.086F);
        TOOLTIP_BG = 0xF0000000 | fromHsl(hue, sat * 1.10F, 0.082F);

        // Text loses saturation as it brightens, or a pale tint reads as a
        // colour rather than as text.
        TEXT_BRIGHT = opaque(fromHsl(hue, sat * 0.55F, 0.957F));
        TEXT_DIM = opaque(fromHsl(hue, sat * 0.78F, 0.745F));
        TEXT_MUTED = opaque(fromHsl(hue, sat * 0.43F, 0.512F));
        TEXT_HEADING = 0xFFFFFFFF;

        float[] pri = toHsl(customPrimary);
        // Lightness is not brightness. A pure blue and a pure yellow at the
        // same HSL lightness are nowhere near as visible as each other, because
        // the eye takes almost none of its sense of brightness from the blue
        // channel. Clamping lightness therefore guarantees nothing; the accent
        // is lifted until it actually reads against the panel it marks.
        ACCENT_BLUE = opaque(readableAgainst(pri[0], pri[1], pri[2], DARK_PANEL));
        ACCENT_LIGHT = ACCENT_BLUE;
        ACCENT = ACCENT_BLUE;
        // The dim accent fills a selected card and has white text on it, so it
        // is the same hue taken down to where white still reads.
        ACCENT_DIM = opaque(fromHsl(pri[0], Math.min(0.75F, pri[1] * 0.72F), 0.34F));
        TOOLTIP_BORDER = opaque(fromHsl(hue, sat * 1.10F, 0.431F));
        HOVER_TINT = 0x28000000 | fromHsl(pri[0], pri[1] * 0.8F, 0.72F);
        HIGHLIGHT = 0x80FFFFFF;

        GOOD = 0xFF3FB950;
        TEXT_WARN = 0xFFD29922;
        TEXT_ERROR = 0xFFF85149;

        CARD_FACE = PANEL_ALT;
        CARD_SELECTED = ACCENT_DIM;
        CARD_BORDER = BORDER;
        CHIP_FACE = DARK_PANEL;
        CHIP_HOVER = PANEL_ALT;
        CHIP_BORDER = BORDER;

        deriveLightPanels(PANEL_ALT, ACCENT_BLUE);
    }

    /** How bright a colour looks, 0 to 1. */
    private static float luma(int argb) {
        return 0.2126F * (((argb >> 16) & 0xFF) / 255.0F)
                + 0.7152F * (((argb >> 8) & 0xFF) / 255.0F)
                + 0.0722F * ((argb & 0xFF) / 255.0F);
    }

    /**
     * The same hue, lightened until it stands out from a surface.
     *
     * <p>Starts from the lightness asked for and raises it in small steps until
     * the difference in apparent brightness is comfortable, so a deep blue
     * accent ends up pale enough to see while a yellow one is left alone.</p>
     */
    private static int readableAgainst(float hue, float saturation,
                                       float wanted, int surface) {
        float target = luma(surface) + 0.30F;
        float l = Math.max(0.42F, Math.min(0.80F, wanted));
        int colour = fromHsl(hue, saturation, l);
        while (luma(0xFF000000 | colour) < target && l < 0.92F) {
            l += 0.02F;
            colour = fromHsl(hue, saturation, l);
        }
        return colour;
    }

    private static int opaque(int rgb) {
        return 0xFF000000 | rgb;
    }

    /** Hue in degrees, saturation and lightness 0 to 1. */
    public static float[] toHsl(int rgb) {
        float r = ((rgb >> 16) & 0xFF) / 255.0F;
        float g = ((rgb >> 8) & 0xFF) / 255.0F;
        float b = (rgb & 0xFF) / 255.0F;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float l = (max + min) / 2.0F;
        if (max == min) {
            return new float[] {0.0F, 0.0F, l};
        }
        float d = max - min;
        float s = l > 0.5F ? d / (2.0F - max - min) : d / (max + min);
        float h;
        if (max == r) {
            h = (g - b) / d + (g < b ? 6.0F : 0.0F);
        } else if (max == g) {
            h = (b - r) / d + 2.0F;
        } else {
            h = (r - g) / d + 4.0F;
        }
        return new float[] {h * 60.0F, s, l};
    }

    /** The inverse of {@link #toHsl}, returning a colour with no alpha. */
    public static int fromHsl(float hue, float saturation, float lightness) {
        float h = ((hue % 360.0F) + 360.0F) % 360.0F / 360.0F;
        float s = Math.max(0.0F, Math.min(1.0F, saturation));
        float l = Math.max(0.0F, Math.min(1.0F, lightness));
        if (s == 0.0F) {
            int v = Math.round(l * 255.0F);
            return (v << 16) | (v << 8) | v;
        }
        float q = l < 0.5F ? l * (1.0F + s) : l + s - l * s;
        float p = 2.0F * l - q;
        int r = Math.round(hueToChannel(p, q, h + 1.0F / 3.0F) * 255.0F);
        int g = Math.round(hueToChannel(p, q, h) * 255.0F);
        int b = Math.round(hueToChannel(p, q, h - 1.0F / 3.0F) * 255.0F);
        return (r << 16) | (g << 8) | b;
    }

    private static float hueToChannel(float p, float q, float t) {
        float x = t;
        if (x < 0.0F) {
            x += 1.0F;
        }
        if (x > 1.0F) {
            x -= 1.0F;
        }
        if (x < 1.0F / 6.0F) {
            return p + (q - p) * 6.0F * x;
        }
        if (x < 0.5F) {
            return q;
        }
        if (x < 2.0F / 3.0F) {
            return p + (q - p) * (2.0F / 3.0F - x) * 6.0F;
        }
        return p;
    }

    private static Palette current = Palette.PINK;

    static {
        apply(Palette.PINK);
    }

    public static Palette palette() {
        return current;
    }

    /** Blends two opaque colours, keeping the first one's alpha. */
    private static int mix(int a, int b, float t) {
        int alpha = (a >>> 24) & 0xFF;
        int r = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return (alpha << 24) | (r << 16) | (g << 8) | bl;
    }

    /**
     * Repoints every themed colour at the given palette.
     *
     * <p>Screens read these fields as they draw, so a change shows on the next
     * frame with nothing to invalidate — except text whose colour was cached
     * when a list was built, which is why the wrap screen rebuilds its rows
     * after calling this.</p>
     */
    public static void apply(Palette p) {
        current = p;

        DARK_BG = p.bg;
        DARK_SUNKEN = p.sunken;
        SIDEBAR = p.sidebar;
        DARK_PANEL = p.panel;
        PANEL_ALT = p.panelAlt;
        BORDER = p.border;
        ACCENT_BLUE = p.accent;
        ACCENT_DIM = p.accentDim;
        ACCENT_LIGHT = p.accent;

        TEXT_BRIGHT = p.textBright;
        TEXT_DIM = p.textDim;
        TEXT_MUTED = p.textMuted;

        // A heading is the brightest thing on a dark panel and the darkest on
        // a light one, so it cannot be a fixed white. Status colours keep their
        // hue and swap lightness for the same reason.
        TEXT_HEADING = p.light ? 0xFF0A0C10 : 0xFFFFFFFF;
        GOOD = p.light ? 0xFF1A7F37 : 0xFF3FB950;
        TEXT_WARN = p.light ? 0xFF8A6100 : 0xFFD29922;
        TEXT_ERROR = p.light ? 0xFFB3261E : 0xFFF85149;
        HIGHLIGHT = p.light ? 0x30000000 : 0x80FFFFFF;

        HOVER_TINT = p.hoverTint;
        TRACK = p.track;
        SCRIM = p.scrim;

        CARD_FACE = p.panelAlt;
        CARD_HOVER = p.cardHover;
        CARD_SELECTED = p.accentDim;
        CARD_BORDER = p.border;
        CARD_BORDER_HOVER = p.cardBorderHover;
        CHIP_FACE = p.panel;
        CHIP_HOVER = p.panelAlt;
        CHIP_BORDER = p.border;

        TOOLTIP_BG = p.tooltipBg;
        TOOLTIP_BORDER = p.tooltipBorder;

        deriveLightPanels(0xFF000000 | p.panelAlt, p.accent);
        TEXT_LIGHT = p.textBright;
        custom = false;
    }

    /**
     * The light-panel set, lifted off whatever surface the scheme settled on so
     * the vanilla-shaped helpers stay in family without a second hand-pick.
     */
    private static void deriveLightPanels(int panelAlt, int accent) {
        int white = 0xFFFFFFFF;
        PANEL_FACE = mix(panelAlt, white, 0.74F);
        PANEL_LIGHT = mix(panelAlt, white, 0.94F);
        PANEL_DARK = mix(panelAlt, white, 0.24F);
        SLOT_BG = mix(panelAlt, white, 0.52F);
        SLOT_LIGHT = PANEL_LIGHT;
        SLOT_DARK = mix(panelAlt, white, 0.10F);
        HEADER_LINE = SLOT_BG;
        TEXT = mix(panelAlt, 0xFF000000, 0.35F);
        ACCENT = accent;
    }

    private GuiTheme() {
    }

    // ---------------------------------------------------------------------
    // The five primitives. Everything else in the mod is built from these.
    // ---------------------------------------------------------------------

    /** Was {@code drawString}. */
    public static void text(GuiGraphicsExtractor g, Font font, String value,
                            int x, int y, int colour) {
        g.text(font, value, x, y, colour, false);
    }

    /** Was {@code drawString} with a Component. */
    public static void text(GuiGraphicsExtractor g, Font font, Component value,
                            int x, int y, int colour) {
        g.text(font, value, x, y, colour, false);
    }

    public static void text(GuiGraphicsExtractor g, Font font, String value,
                            int x, int y, int colour, boolean shadow) {
        g.text(font, value, x, y, colour, shadow);
    }

    public static void text(GuiGraphicsExtractor g, Font font, Component value,
                            int x, int y, int colour, boolean shadow) {
        g.text(font, value, x, y, colour, shadow);
    }

    /** Was {@code renderItem}. */
    public static void item(GuiGraphicsExtractor g, ItemStack stack, int x, int y) {
        if (stack != null && !stack.isEmpty()) {
            g.item(stack, x, y);
        }
    }

    /** Was {@code renderItemDecorations} (stack count, durability bar). */
    public static void itemDecorations(GuiGraphicsExtractor g, Font font,
                                       ItemStack stack, int x, int y) {
        if (stack != null && !stack.isEmpty()) {
            g.itemDecorations(font, stack, x, y);
        }
    }

    /**
     * Draws a tooltip box.
     *
     * <p>Hand-rolled on purpose. The vanilla tooltip call now demands a
     * {@code List<ClientTooltipComponent>}, a {@code ClientTooltipPositioner}
     * and a style {@code Identifier} — three more moving parts that can be
     * renamed out from under us. Since a tooltip is only a dark box with text
     * in it, drawing it from {@link #rect} and {@link #text} costs a few lines
     * and removes the dependency completely.</p>
     */
    public static void tooltip(GuiGraphicsExtractor g, Font font,
                               List<Component> lines, int x, int y) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        int width = 0;
        for (Component line : lines) {
            width = Math.max(width, font.width(line));
        }
        int height = lines.size() * LINE_HEIGHT;

        // Offset from the cursor the way vanilla does, then nudge back on
        // screen if the box would run off the right or bottom edge.
        int boxX = x + 12;
        int boxY = y - 12;
        if (boxX + width + 4 > screenWidth) {
            boxX = Math.max(4, x - width - 16);
        }
        if (boxY + height + 4 > screenHeight) {
            boxY = Math.max(4, screenHeight - height - 6);
        }
        if (boxY < 4) {
            boxY = 4;
        }

        rect(g, boxX - 4, boxY - 4, boxX + width + 4, boxY + height + 2, TOOLTIP_BG);
        rect(g, boxX - 3, boxY - 3, boxX + width + 3, boxY - 2, TOOLTIP_BORDER);
        rect(g, boxX - 3, boxY + height, boxX + width + 3, boxY + height + 1, TOOLTIP_BORDER);
        rect(g, boxX - 3, boxY - 3, boxX - 2, boxY + height + 1, TOOLTIP_BORDER);
        rect(g, boxX + width + 2, boxY - 3, boxX + width + 3, boxY + height + 1, TOOLTIP_BORDER);

        int lineY = boxY;
        for (Component line : lines) {
            text(g, font, line, boxX, lineY, 0xFFFFFFFF, true);
            lineY += LINE_HEIGHT;
        }
    }

    /**
     * Screen size, needed so tooltips can be kept on screen. Set once per
     * frame by each screen before it draws.
     */
    private static int screenWidth = 854;
    private static int screenHeight = 480;

    public static void setScreenSize(int width, int height) {
        screenWidth = width;
        screenHeight = height;
    }

    /** Solid rectangle. No prefix to strip, so unchanged across the rename. */
    public static void rect(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int colour) {
        g.fill(x1, y1, x2, y2, colour);
    }

    // ---------------------------------------------------------------------
    // Composed widgets, built purely from the primitives above.
    // ---------------------------------------------------------------------

    /** Vanilla-style raised panel. */
    public static void panel(GuiGraphicsExtractor g, int x, int y, int width, int height) {
        rect(g, x, y, x + width, y + height, PANEL_FACE);
        rect(g, x, y, x + width, y + 1, PANEL_LIGHT);
        rect(g, x, y, x + 1, y + height, PANEL_LIGHT);
        rect(g, x, y + height - 1, x + width, y + height, PANEL_DARK);
        rect(g, x + width - 1, y, x + width, y + height, PANEL_DARK);
    }

    /** Vanilla-style sunken 18x18 slot. */
    public static void slot(GuiGraphicsExtractor g, int x, int y) {
        rect(g, x, y, x + SLOT_SIZE, y + SLOT_SIZE, SLOT_BG);
        rect(g, x, y, x + SLOT_SIZE, y + 1, SLOT_DARK);
        rect(g, x, y, x + 1, y + SLOT_SIZE, SLOT_DARK);
        rect(g, x, y + SLOT_SIZE - 1, x + SLOT_SIZE, y + SLOT_SIZE, SLOT_LIGHT);
        rect(g, x + SLOT_SIZE - 1, y, x + SLOT_SIZE, y + SLOT_SIZE, SLOT_LIGHT);
    }

    public static void hover(GuiGraphicsExtractor g, int x, int y) {
        rect(g, x + 1, y + 1, x + SLOT_SIZE - 1, y + SLOT_SIZE - 1, HIGHLIGHT);
    }

    /** Tab above the panel; the selected tab reads as joined to it. */
    public static void tab(GuiGraphicsExtractor g, int x, int y, int width, int height,
                           boolean selected) {
        rect(g, x, y, x + width, y + height, selected ? PANEL_FACE : 0xFF9A9A9A);
        rect(g, x, y, x + width, y + 1, PANEL_LIGHT);
        rect(g, x, y, x + 1, y + height, PANEL_LIGHT);
        rect(g, x + width - 1, y, x + width, y + height, PANEL_DARK);
        if (!selected) {
            rect(g, x, y + height - 1, x + width, y + height, PANEL_DARK);
        }
    }

    public static void separator(GuiGraphicsExtractor g, int x, int y, int width) {
        rect(g, x, y, x + width, y + 1, HEADER_LINE);
    }

    /**
     * Draws a region of a texture.
     *
     * <p>Added for the model explorer, whose previews are rendered to an image
     * and then blitted. The {@code v} range picks one frame out of a rotation
     * strip, so a spinning card is a single texture and a changing offset
     * rather than a texture per angle.</p>
     *
     * <p>PORTING NOTE: the underlying call is
     * {@code blit(Identifier, x0, y0, x1, y1, minU, maxU, minV, maxV)}. The
     * argument order was confirmed by reading the method's bytecode in the 26.2
     * jar, because it is not the order the parameter names suggest: it forwards
     * as {@code (x0, x1, y0, y1)}, not {@code (x, y, width, height)}.</p>
     */
    public static void texture(GuiGraphicsExtractor g, Identifier texture,
                               int x, int y, int width, int height,
                               float v0, float v1) {
        g.blit(texture, x, y, x + width, y + height, 0.0F, 1.0F, v0, v1);
    }

    /**
     * The same, drawn at a fraction of its opacity.
     *
     * <p>Uses the tinted form of the blit, whose colour multiplies the texture.
     * {@code GUI_TEXTURED} blends translucently — confirmed in the 26.2 jar,
     * where its snippet is built with {@code BlendFunction.TRANSLUCENT} — so an
     * alpha in that colour is a real fade rather than a no-op.</p>
     *
     * @param textureSize the texture's own width and height in pixels, which
     *                    this form of the blit needs because it takes its
     *                    region in pixels rather than as a fraction
     */
    public static void fadedTexture(GuiGraphicsExtractor g, Identifier texture,
                                    int x, int y, int width, int height,
                                    int textureSize, float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255.0F)));
        if (a == 0) {
            return;
        }
        g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, texture,
                x, y, 0.0F, 0.0F, width, height, textureSize, textureSize,
                textureSize, textureSize, (a << 24) | 0x00FFFFFF);
    }

    /**
     * A card: filled surface with a one-pixel border on every side.
     *
     * <p>Square rather than the previous inset-corner shape, and bordered on
     * all four sides, matching the panels in the Auto Tuner screen.</p>
     */
    public static void card(GuiGraphicsExtractor g, int x, int y, int width, int height,
                            boolean hovered, boolean selected) {
        rect(g, x, y, x + width, y + height,
                selected ? CARD_SELECTED : (hovered ? CARD_HOVER : CARD_FACE));
        int border = selected ? ACCENT_BLUE : (hovered ? CARD_BORDER_HOVER : CARD_BORDER);
        border(g, x, y, width, height, border);
    }

    /** One-pixel border around a rectangle. */
    public static void border(GuiGraphicsExtractor g, int x, int y, int width, int height,
                              int colour) {
        rect(g, x, y, x + width, y + 1, colour);
        rect(g, x, y + height - 1, x + width, y + height, colour);
        rect(g, x, y, x + 1, y + height, colour);
        rect(g, x + width - 1, y, x + width, y + height, colour);
    }

    /**
     * A panel with an optional heading and rule beneath it, as
     * {@code UiPanel} draws them.
     */
    public static void surface(GuiGraphicsExtractor g, Font font, int x, int y,
                               int width, int height, String heading) {
        rect(g, x, y, x + width, y + height, DARK_PANEL);
        border(g, x, y, width, height, BORDER);
        if (heading != null) {
            text(g, font, heading, x + 8, y + 6, TEXT_HEADING, false);
            rect(g, x + 8, y + 18, x + width - 8, y + 19, BORDER);
        }
    }

    /**
     * A filter pill. Accent-filled when active, with the accent line top and
     * bottom rather than a full border, exactly as {@code UiFilterChip} does.
     */
    public static void chip(GuiGraphicsExtractor g, int x, int y, int width, int height,
                            boolean active, boolean hovered) {
        rect(g, x, y, x + width, y + height,
                active ? ACCENT_DIM : (hovered ? PANEL_ALT : DARK_PANEL));
        int edge = active ? ACCENT_BLUE : BORDER;
        rect(g, x, y, x + width, y + 1, edge);
        rect(g, x, y + height - 1, x + width, y + height, edge);
    }

    /**
     * A sidebar navigation row: a hover tint, and a two-pixel accent bar down
     * the left edge when selected.
     */
    public static void navItem(GuiGraphicsExtractor g, Font font, int x, int y,
                               int width, int height, String label, String trailing,
                               boolean selected, boolean hovered, boolean enabled) {
        if (selected) {
            rect(g, x, y, x + width, y + height, PANEL_ALT);
            rect(g, x, y, x + 2, y + height, ACCENT_BLUE);
        } else if (hovered && enabled) {
            rect(g, x, y, x + width, y + height, HOVER_TINT);
        }
        int colour = !enabled ? TEXT_MUTED
                : selected ? TEXT_HEADING : (hovered ? TEXT_BRIGHT : TEXT_DIM);
        // The count is the fixed point: it is right-aligned and short, so the
        // label yields to it. "Hats & Helmets" beside a five-figure count is
        // the case that would otherwise overlap in a 132-pixel sidebar.
        boolean hasTrailing = trailing != null && !trailing.isEmpty();
        int room = width - 20 - (hasTrailing ? font.width(trailing) + 6 : 0);
        text(g, font, ellipsise(font, label, room), x + 12,
                y + (height - 8) / 2, colour, false);
        if (hasTrailing) {
            text(g, font, trailing, x + width - 8 - font.width(trailing),
                    y + (height - 8) / 2, TEXT_MUTED, false);
        }
    }

    /** A text field frame: accent edge when focused, brighter edge on hover. */
    public static void field(GuiGraphicsExtractor g, int x, int y, int width, int height,
                             boolean focused, boolean hovered) {
        rect(g, x, y, x + width, y + height, DARK_PANEL);
        border(g, x, y, width, height,
                focused ? ACCENT_BLUE : (hovered ? TEXT_MUTED : BORDER));
    }

    /** Vertical scrollbar track and thumb. */
    public static void scrollbar(GuiGraphicsExtractor g, int x, int y, int height,
                                 int contentHeight, int viewHeight, int offset) {
        if (contentHeight <= viewHeight) {
            return;
        }
        rect(g, x, y, x + 6, y + height, TRACK);
        int thumb = Math.max(20, (int) ((viewHeight / (double) contentHeight) * height));
        int max = Math.max(1, contentHeight - viewHeight);
        int thumbY = y + (int) ((height - thumb) * Math.min(1.0, offset / (double) max));
        rect(g, x, thumbY, x + 6, thumbY + thumb, ACCENT_DIM);
    }

    /** Horizontal progress bar. */
    public static void progress(GuiGraphicsExtractor g, int x, int y, int width, int height,
                                float fraction) {
        rect(g, x, y, x + width, y + height, TRACK);
        if (fraction < 0) {
            // Indeterminate: a block that sweeps, so the player can tell the
            // difference between "working" and "hung".
            int sweep = (int) ((System.currentTimeMillis() / 6 % (width + 40)) - 40);
            int from = Math.max(x, x + sweep);
            int to = Math.min(x + width, x + sweep + 40);
            if (to > from) {
                rect(g, from, y, to, y + height, ACCENT_BLUE);
            }
            return;
        }
        int filled = (int) (width * Math.max(0.0F, Math.min(1.0F, fraction)));
        if (filled > 0) {
            rect(g, x, y, x + filled, y + height, ACCENT_BLUE);
        }
    }

    /** Shortens text with an ellipsis so it fits the room given. */
    public static String ellipsise(Font font, String value, int room) {
        if (room <= 0 || font.width(value) <= room) {
            return value;
        }
        String out = value;
        while (!out.isEmpty() && font.width(out + "...") > room) {
            out = out.substring(0, out.length() - 1);
        }
        return out.isEmpty() ? value : out + "...";
    }

    /** Right-aligned text, used throughout the explorer's header and footer. */
    public static void textRight(GuiGraphicsExtractor g, Font font, String value,
                                 int rightX, int y, int colour) {
        text(g, font, value, rightX - font.width(value), y, colour, false);
    }

    /** Draws a stack plus its decorations in one call. */
    public static void stack(GuiGraphicsExtractor g, Font font, ItemStack s, int x, int y) {
        item(g, s, x, y);
        itemDecorations(g, font, s, x, y);
    }
}
