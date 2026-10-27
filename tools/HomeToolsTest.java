import dev.custommodels.cmv.explorer.scan.AtlasBudget;
import dev.custommodels.cmv.gui.ColourPicker;
import dev.custommodels.cmv.gui.GuiTheme;

/**
 * The two pure pieces behind the home page's new controls.
 *
 * <p>Both turn messy real-world input into something that has to be exactly
 * right: a server name that will become a file name, and a pixel count that
 * decides whether the game is about to switch off every resource pack. Neither
 * can be checked by looking at the screen, because the interesting cases are
 * the ones nobody has in front of them.
 *
 * <p>Run with the game jar on the classpath; see the README.
 */
public final class HomeToolsTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        // ---- atlas sizing ----
        // The stitcher doubles until it fits, so the answer must step rather
        // than grow smoothly: one pixel over 4096x4096 costs a whole 8192.
        eq("nothing needs the smallest atlas", AtlasBudget.sideFor(0), 64);
        eq("4096 squared exactly fits 4096", AtlasBudget.sideFor(4096L * 4096), 4096);
        eq("one pixel more needs 8192", AtlasBudget.sideFor(4096L * 4096 + 1), 8192);
        eq("8192 squared exactly fits 8192", AtlasBudget.sideFor(8192L * 8192), 8192);
        eq("one pixel more needs 16384", AtlasBudget.sideFor(8192L * 8192 + 1), 16384);
        check("beyond the limit is reported as beyond, not clamped",
                AtlasBudget.sideFor(16384L * 16384 + 1) > AtlasBudget.MAX_SIDE);

        // The real numbers from the packs that emptied the pack list.
        eq("tonight's combined packs needed the largest atlas",
                AtlasBudget.sideFor(197_477_584L), 16384);
        check("and the pre-merge total was past even that",
                AtlasBudget.sideFor(528_288_105L) > AtlasBudget.MAX_SIDE);

        // The frame-versus-strip bug, in the numbers it actually produced.
        // An animated texture is a column of frames and only one frame is ever
        // in the atlas; measuring the whole column counted a wing's eleven
        // frames eleven times. On one real pack that added 243 million pixels
        // that were never there, and the panel declared an atlas impossible
        // while the game was running happily with it.
        check("counting whole strips made a working pack look unstitchable",
                AtlasBudget.sideFor(458_911_367L) > AtlasBudget.MAX_SIDE);
        eq("counting one frame each, the same pack fits the largest atlas",
                AtlasBudget.sideFor(215_639_513L), 16384);
        check("and sits comfortably inside it",
                215_639_513.0 / (16384.0 * 16384.0) < 0.85);

        // An atlas the game has actually built reports against its real size,
        // not against the size its sprites would have needed.
        AtlasBudget.Atlas live = new AtlasBudget.Atlas(
                "items", 55_094_570L, 25780, AtlasBudget.sideFor(55_094_570L),
                8192, java.util.List.of(), java.util.List.of());
        eq("a built atlas is judged on the size it has", live.effectiveSide(), 8192);
        check("its fill is measured against that", live.fill() > 0.8 && live.fill() < 0.85);
        eq("and its cost follows from it", Math.round(live.vram() / 1e6), 268L);
        check("room left is capacity minus what is in it",
                live.room() == 8192L * 8192 - 55_094_570L);
        check("a comfortable atlas is not flagged", !live.tight() && !live.overflows());

        // Not yet stitched: fall back to what the sprites need.
        AtlasBudget.Atlas pending = new AtlasBudget.Atlas(
                "items", 55_094_570L, 25780, AtlasBudget.sideFor(55_094_570L),
                0, java.util.List.of(), java.util.List.of());
        eq("an unbuilt atlas falls back to the needed size",
                pending.effectiveSide(), 8192);

        // A full 4096 atlas has three quarters of its pixels still to give,
        // because the next step quadruples the area. Not a warning.
        AtlasBudget.Atlas small = new AtlasBudget.Atlas(
                "items", 16_000_000L, 900, AtlasBudget.sideFor(16_000_000L),
                4096, java.util.List.of(), java.util.List.of());
        check("a full small atlas is not a warning", !small.tight());

        AtlasBudget.Atlas tight = new AtlasBudget.Atlas(
                "items", 200_000_000L, 28000, AtlasBudget.sideFor(200_000_000L),
                16384, java.util.List.of(), java.util.List.of());
        check("a mostly-full largest atlas is a warning", tight.tight());
        check("and still has room reported", tight.room() > 0);

        AtlasBudget.Atlas over = new AtlasBudget.Atlas(
                "items", 528_288_105L, 28634, AtlasBudget.sideFor(528_288_105L),
                0, java.util.List.of(), java.util.List.of());
        check("an unstitchable atlas is reported as such", over.overflows());
        check("and its fill is over 100%, not a comfortable-sounding fraction",
                over.fill() > 1.0, String.format("%.0f%%", over.fill() * 100));
        check("and is not merely called tight", !over.tight());
        eq("nothing is left to give", over.room(), 0L);

        // ---- the colour wheel shows the colour it hands back ----
        // The regression: the wheel's pixels were packed with red and blue
        // swapped, while the hue was taken from the click angle. Every colour
        // came out as its opposite and nothing in the maths was wrong, so only
        // comparing the two ends against each other catches it.
        int wheelSide = 128;
        int radius = wheelSide / 2;
        double worstHueError = 0;
        int checked = 0;
        for (int degrees = 0; degrees < 360; degrees += 5) {
            double angle = Math.toRadians(degrees);
            // Two thirds out: saturated enough to have an unambiguous hue.
            double dx = Math.cos(angle) * (radius * 0.66);
            double dy = Math.sin(angle) * (radius * 0.66);
            int px = (int) Math.round(radius + dx - 0.5);
            int py = (int) Math.round(radius + dy - 0.5);
            int argb = ColourPicker.wheelPixel(px, py, wheelSide);
            if ((argb >>> 24) == 0) {
                continue;
            }
            // What the picker would report for that spot...
            float claimed = ColourPicker.hueAt(px - radius + 0.5, py - radius + 0.5);
            // ...against the hue actually painted there.
            float shown = GuiTheme.toHsl(argb & 0xFFFFFF)[0];
            double error = Math.abs(((claimed - shown + 540) % 360) - 180);
            worstHueError = Math.max(worstHueError, error);
            checked++;
        }
        check("the wheel was sampled all the way round", checked >= 60, checked + " points");
        check("every point on the wheel shows the hue it hands back",
                worstHueError < 3.0,
                String.format("worst error %.1f degrees", worstHueError));

        // Blue on screen must be blue in the value, which is the exact
        // complaint: it was coming back red.
        int bluePx = (int) Math.round(radius + Math.cos(Math.toRadians(240)) * radius * 0.66 - 0.5);
        int bluePy = (int) Math.round(radius + Math.sin(Math.toRadians(240)) * radius * 0.66 - 0.5);
        int blue = ColourPicker.wheelPixel(bluePx, bluePy, wheelSide) & 0xFFFFFF;
        check("the blue quadrant is actually blue",
                (blue & 0xFF) > ((blue >> 16) & 0xFF),
                String.format("#%06X has more red than blue", blue));

        check("outside the disc is transparent",
                (ColourPicker.wheelPixel(0, 0, wheelSide) >>> 24) == 0);
        check("the centre is drawn", (ColourPicker.wheelPixel(radius, radius, wheelSide) >>> 24) != 0);

        // ---- remembering the pack selection ----
        // The failure this undoes writes ["vanilla"] over the list. A history
        // that recorded that would have overwritten the answer with the
        // problem, so a selection of one entry is never worth keeping.

        check("a wiped selection is not recorded",
                !dev.custommodels.cmv.core.PackHistory.worthRecording(
                        java.util.List.of("vanilla"), java.util.List.of()));
        check("an empty selection is not recorded",
                !dev.custommodels.cmv.core.PackHistory.worthRecording(
                        java.util.List.of(), java.util.List.of()));
        check("a real selection is recorded",
                dev.custommodels.cmv.core.PackHistory.worthRecording(
                        java.util.List.of("vanilla", "file/a.zip"), java.util.List.of()));
        check("the same selection twice is only recorded once",
                !dev.custommodels.cmv.core.PackHistory.worthRecording(
                        java.util.List.of("vanilla", "file/a.zip"),
                        java.util.List.of(java.util.List.of("vanilla", "file/a.zip"))));

        java.util.List<java.util.List<String>> history = java.util.List.of(
                java.util.List.of("vanilla", "file/a.zip"),
                java.util.List.of("vanilla", "file/a.zip", "file/b.zip", "file/c.zip"));
        check("after a wipe, the newest fuller selection is offered",
                dev.custommodels.cmv.core.PackHistory.candidateFrom(history, 1)
                        .size() == 2);
        check("with two on, it reaches past to the four-pack one",
                dev.custommodels.cmv.core.PackHistory.candidateFrom(history, 2)
                        .size() == 4);
        check("nothing is offered when nothing is fuller",
                dev.custommodels.cmv.core.PackHistory.candidateFrom(history, 4) == null);
        check("an empty history offers nothing",
                dev.custommodels.cmv.core.PackHistory.candidateFrom(
                        java.util.List.of(), 0) == null);
        check("a null history offers nothing",
                dev.custommodels.cmv.core.PackHistory.candidateFrom(null, 0) == null);

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void eq(String what, Object got, Object want) {
        check(what, got.equals(want), "got " + got + ", wanted " + want);
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
