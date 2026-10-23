import dev.custommodels.cmv.gui.GuiTheme;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks every colour theme is actually wearable.
 *
 * <p>The failure this exists for is silent: a palette that forgets a field
 * leaves it at zero, which is transparent black, and the surface it paints
 * simply is not there. Nothing throws, no test of behaviour notices, and it
 * only shows up when somebody switches to that theme and finds a hole in the
 * screen. The same goes for a text colour that lands too close to the panel it
 * sits on — legible in the author's head, unreadable in the game.
 *
 * <p>So this walks the real fields by reflection rather than listing them:
 * a colour added later is covered without anybody remembering to add it here.
 *
 * <p>Run with the game jar on the classpath; see the README.
 */
public final class ThemeTest {

    private static int passed;
    private static int failed;

    /** Not colours, so not part of a theme. */
    private static final List<String> NOT_THEMED = List.of(
            "SLOT_SIZE", "COLUMNS", "LINE_HEIGHT");

    public static void main(String[] args) {
        List<Field> colours = themedFields();
        check("there are themed colours to check", !colours.isEmpty());

        for (GuiTheme.Palette palette : GuiTheme.Palette.values()) {
            GuiTheme.apply(palette);
            String tag = palette.name();

            // Every field set. Zero means transparent black: an invisible panel.
            List<String> unset = new ArrayList<>();
            for (Field f : colours) {
                if (value(f) == 0) {
                    unset.add(f.getName());
                }
            }
            check(tag + ": every themed colour is set", unset.isEmpty(), unset.toString());

            // Surfaces must be opaque, or the world shows through the panel.
            List<String> seeThrough = new ArrayList<>();
            for (String name : List.of("DARK_PANEL", "PANEL_ALT", "SIDEBAR",
                    "DARK_SUNKEN", "BORDER", "CARD_HOVER", "TRACK")) {
                if (alpha(read(name)) != 0xFF) {
                    seeThrough.add(name);
                }
            }
            check(tag + ": surfaces are opaque", seeThrough.isEmpty(), seeThrough.toString());

            // The accent has to be visible against the panel it marks, and
            // against the fill used for a selected card.
            check(tag + ": the accent reads against the panel",
                    contrast(read("ACCENT_BLUE"), read("DARK_PANEL")) > 0.25,
                    String.valueOf(contrast(read("ACCENT_BLUE"), read("DARK_PANEL"))));

            // Text against the surface it is drawn on. TEXT_MUTED is the
            // quietest by design, so it gets the loosest bound.
            checkText(tag, "TEXT_BRIGHT", "DARK_PANEL", 0.55);
            checkText(tag, "TEXT_DIM", "DARK_PANEL", 0.40);
            checkText(tag, "TEXT_MUTED", "DARK_PANEL", 0.25);
            checkText(tag, "TEXT_BRIGHT", "PANEL_ALT", 0.50);
            checkText(tag, "TEXT_HEADING", "DARK_PANEL", 0.55);

            // Status colours have to survive the theme they sit on, which is
            // the whole reason they stopped being fixed when a light theme
            // arrived: the green that reads on near-black vanishes on white.
            checkText(tag, "GOOD", "DARK_PANEL", 0.20);
            checkText(tag, "TEXT_WARN", "DARK_PANEL", 0.20);
            checkText(tag, "TEXT_ERROR", "DARK_PANEL", 0.20);

            // White label on a selected card, which is filled with ACCENT_DIM.
            check(tag + ": a selected card's label stays readable",
                    contrast(0xFFFFFFFF, read("CARD_SELECTED")) > 0.35,
                    String.valueOf(contrast(0xFFFFFFFF, read("CARD_SELECTED"))));

            // Depth has to read without relying on hue, so the raised surface
            // must actually be lighter than the sunken one.
            check(tag + ": surfaces climb from sunken to raised",
                    luma(read("DARK_SUNKEN")) < luma(read("DARK_PANEL"))
                            && luma(read("DARK_PANEL")) < luma(read("PANEL_ALT")));
        }

        // Unknown names fall back rather than throwing, because this value comes
        // out of a config file a human can edit.
        check("an unknown theme name falls back to the default",
                GuiTheme.Palette.of("chartreuse") == GuiTheme.Palette.PINK);
        check("a null theme name falls back too",
                GuiTheme.Palette.of(null) == GuiTheme.Palette.PINK);
        check("names round-trip", GuiTheme.Palette.of("GREEN") == GuiTheme.Palette.GREEN);
        check("names are not case sensitive",
                GuiTheme.Palette.of("green") == GuiTheme.Palette.GREEN);

        // The reason the fields are not final: a final primitive is inlined
        // into its call sites, so switching theme would change nothing.
        List<String> stillFinal = new ArrayList<>();
        for (Field f : colours) {
            if (Modifier.isFinal(f.getModifiers())) {
                stillFinal.add(f.getName());
            }
        }
        check("themed colours are not final, or the change never reaches a screen",
                stillFinal.isEmpty(), stillFinal.toString());

        // Applying really does move the values.
        GuiTheme.apply(GuiTheme.Palette.PINK);
        int pink = read("ACCENT_BLUE");
        GuiTheme.apply(GuiTheme.Palette.GREEN);
        check("applying a theme changes the accent", read("ACCENT_BLUE") != pink);
        check("and reports which one is current",
                GuiTheme.palette() == GuiTheme.Palette.GREEN);

        // Every theme is distinct, or one of them is a typo of another.
        List<Integer> accents = new ArrayList<>();
        for (GuiTheme.Palette p : GuiTheme.Palette.values()) {
            GuiTheme.apply(p);
            accents.add(read("ACCENT_BLUE"));
        }
        check("every theme has its own accent",
                accents.size() == accents.stream().distinct().count());

        // ---- schemes derived from two chosen colours ----
        // The presets are eleven hand-checked points; a custom pair is any
        // point at all, so the same guarantees are swept round the hue circle
        // rather than sampled. This is where clamping the accent by HSL
        // lightness was caught: a blue and a yellow at the same lightness are
        // nowhere near equally visible, and the blue failed here.
        double worstAccent = 9, worstMuted = 9, worstCard = 9;
        int worstAccentHue = -1;
        for (int h = 0; h < 360; h += 6) {
            int secondary = GuiTheme.fromHsl(h, 0.9F, 0.66F);
            int primary = GuiTheme.fromHsl((h + 140) % 360, 1.0F, 0.60F);
            GuiTheme.applyCustom(primary, secondary);

            double accent = contrast(read("ACCENT_BLUE"), read("DARK_PANEL"));
            if (accent < worstAccent) {
                worstAccent = accent;
                worstAccentHue = h;
            }
            worstMuted = Math.min(worstMuted, contrast(read("TEXT_MUTED"), read("DARK_PANEL")));
            worstCard = Math.min(worstCard, contrast(0xFFFFFFFF, read("CARD_SELECTED")));

            if (luma(read("DARK_SUNKEN")) >= luma(read("DARK_PANEL"))
                    || luma(read("DARK_PANEL")) >= luma(read("PANEL_ALT"))) {
                check("custom hue " + h + ": surfaces climb", false);
            }
            for (String name : List.of("DARK_PANEL", "PANEL_ALT", "SIDEBAR",
                    "DARK_SUNKEN", "BORDER", "CARD_HOVER", "TRACK")) {
                if (alpha(read(name)) != 0xFF) {
                    check("custom hue " + h + ": " + name + " is opaque", false);
                }
            }
        }
        check("every custom hue keeps its surfaces stacked and opaque", failed == 0);
        check("the accent reads at every hue", worstAccent > 0.25,
                String.format("worst %.3f at hue %d", worstAccent, worstAccentHue));
        check("muted text reads at every hue", worstMuted > 0.25,
                String.format("worst %.3f", worstMuted));
        check("white on a selected card reads at every hue", worstCard > 0.35,
                String.format("worst %.3f", worstCard));

        // A colour with no saturation should give the neutral scheme, with no
        // special case anywhere for it.
        GuiTheme.applyCustom(0xFFFFFF, 0x808080);
        int panel = read("DARK_PANEL");
        check("a grey secondary gives grey surfaces",
                ((panel >> 16) & 0xFF) == ((panel >> 8) & 0xFF)
                        && ((panel >> 8) & 0xFF) == (panel & 0xFF),
                String.format("#%06X", panel & 0xFFFFFF));

        // Round trips, because the picker converts both ways every frame it is
        // dragged and an error there would drift the colour as you move.
        int drift = 0;
        for (int rgb : new int[] {0xFF2D95, 0x8B5CF6, 0x000000, 0xFFFFFF, 0x3BFF87,
                                  0x808080, 0x010203, 0xFEDCBA}) {
            float[] hsl = GuiTheme.toHsl(rgb);
            int back = GuiTheme.fromHsl(hsl[0], hsl[1], hsl[2]);
            for (int shift : new int[] {16, 8, 0}) {
                drift = Math.max(drift,
                        Math.abs(((rgb >> shift) & 0xFF) - ((back >> shift) & 0xFF)));
            }
        }
        check("a colour survives the round trip through HSL", drift <= 1,
                "worst channel drift " + drift);

        check("a derived scheme reports itself as custom", GuiTheme.isCustom());
        GuiTheme.apply(GuiTheme.Palette.PINK);
        check("and a preset reports itself as not custom", !GuiTheme.isCustom());

        GuiTheme.apply(GuiTheme.Palette.PINK);
        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void checkText(String tag, String text, String surface, double least) {
        double got = contrast(read(text), read(surface));
        check(tag + ": " + text + " reads on " + surface, got > least,
                String.format("%.3f, wanted > %.2f", got, least));
    }

    private static List<Field> themedFields() {
        List<Field> out = new ArrayList<>();
        for (Field f : GuiTheme.class.getDeclaredFields()) {
            if (f.getType() != int.class || !Modifier.isPublic(f.getModifiers())
                    || !Modifier.isStatic(f.getModifiers())
                    || NOT_THEMED.contains(f.getName())) {
                continue;
            }
            out.add(f);
        }
        return out;
    }

    private static int value(Field f) {
        try {
            return f.getInt(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int read(String name) {
        try {
            return GuiTheme.class.getField(name).getInt(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("no such colour: " + name, e);
        }
    }

    private static int alpha(int argb) {
        return (argb >>> 24) & 0xFF;
    }

    /** Relative luminance, 0 to 1, ignoring alpha. */
    private static double luma(int argb) {
        double r = ((argb >> 16) & 0xFF) / 255.0;
        double g = ((argb >> 8) & 0xFF) / 255.0;
        double b = (argb & 0xFF) / 255.0;
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double contrast(int a, int b) {
        return Math.abs(luma(a) - luma(b));
    }

    private static void check(String what, boolean ok) {
        check(what, ok, "");
    }

    private static void check(String what, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  ok   " + what);
        } else {
            failed++;
            System.out.println("  FAIL " + what + (detail.isEmpty() ? "" : "   " + detail));
        }
    }
}
