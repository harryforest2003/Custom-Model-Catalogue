/**
 * The detail view's render size, and when it asks for the sharp one.
 *
 * <p>Nothing here renders anything. Both of the things the sharpening can get
 * wrong are pure functions, and this walks both.</p>
 *
 * <h2>Why the size is worth a harness</h2>
 *
 * <p>A moving preview has to stay small, because turning asks for a fresh
 * render every three degrees. A still one is rendered at the size it occupies,
 * which at a GUI scale of four is four times what the old cap allowed \u2014 that
 * gap is the whole feature. A model whose textures animate caches a whole
 * cycle at once, so its size has to come down until the cycle fits the budget,
 * and getting that wrong does not look wrong: it thrashes the cache and
 * re-renders frames it already had.</p>
 *
 * <h2>And why the settle is</h2>
 *
 * <p>Because the sequence is the point. Paused, grabbed, dragged, let go,
 * waited: the sharp render must be asked for exactly once, at the angle the
 * view was left at, and never on the pauses between drags.</p>
 */
public final class StillRenderTest {

    private static int passed;
    private static int failed;

    private StillRenderTest() {
    }

    private static void says(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (ok) {
            passed++;
        } else {
            failed++;
        }
    }

    // --------------------------------------------------- how big it is drawn
    private static final int MOVING = 288;
    private static final int STILL = 1280;
    private static final long BUDGET = 16L * 1024 * 1024;

    private static int pixels(int box, int scale, boolean moving, int frames) {
        return dev.custommodels.cmv.explorer.render.PreviewService.detailPixels(
                box, scale, moving, frames, MOVING, STILL, BUDGET);
    }

    private static void howBig() {
        System.out.println();
        System.out.println("how big the model render is asked for");

        // The fault this exists for: at a GUI scale of four a 284-pixel box
        // wants 1136 real pixels and was being given 288, then blown up.
        says("a still view is rendered at the size it is displayed",
                pixels(284, 4, false, 1) == 1136);
        says("and a moving one stays small",
                pixels(284, 4, true, 1) == MOVING);
        says("so stopping always sharpens, never blurs",
                pixels(284, 4, false, 1) > pixels(284, 4, true, 1));

        // A small window must not be rendered larger than it is shown: that is
        // work thrown away, not quality.
        says("a small box is never over-rendered", pixels(40, 2, false, 1) == 80);

        // An animated model caches a whole cycle at once, so its size comes
        // down until the cycle fits - and never below the moving size.
        int one = pixels(284, 4, false, 1);
        int few = pixels(284, 4, false, 4);
        int many = pixels(284, 4, false, 64);
        says("more frames means a smaller still render", few < one && many < few);
        says("and never smaller than it was while moving", many >= MOVING);

        boolean fits = true;
        for (int frames = 2; frames <= 120; frames++) {
            int size = pixels(284, 4, false, frames);
            long bytes = (long) size * size * 4 * frames;
            // Allowed past the budget only where the floor of the moving size
            // forces it, which is the deliberate choice above.
            if (bytes > BUDGET && size > MOVING) {
                fits = false;
            }
        }
        says("a cached cycle fits the budget at every frame count", fits);

        // The animation cycle has to land on ticks the clock actually reaches,
        // or a view waiting for a complete set of frames waits forever.
        boolean landable = true;
        boolean multiples = true;
        for (int period = 1; period <= 600; period++) {
            int even = dev.custommodels.cmv.explorer.render.PreviewService
                    .evenPeriod(period);
            if (even > 1 && even % 2 != 0) {
                landable = false;
            }
            if (even % period != 0) {
                multiples = false;
            }
        }
        says("every cycle length is one the two-tick clock lands on", landable);
        says("and is still a multiple of the texture's own period", multiples);
        says("an even period is left alone",
                dev.custommodels.cmv.explorer.render.PreviewService.evenPeriod(20) == 20);
        says("and an odd one is doubled rather than nudged",
                dev.custommodels.cmv.explorer.render.PreviewService.evenPeriod(15) == 30);

        // Nonsense in, something drawable out.
        boolean sane = true;
        for (int box : new int[]{-5, 0, 1, 10000}) {
            for (int scale : new int[]{0, 1, 4, 9}) {
                for (boolean moving : new boolean[]{true, false}) {
                    int size = pixels(box, scale, moving, 1);
                    if (size < 16 || size > STILL) {
                        sane = false;
                    }
                }
            }
        }
        says("every size is drawable and within the cap", sane);
    }

    // ------------------------------------------------- when it asks for sharp

    private static void whenItSharpens() {
        System.out.println();
        System.out.println("when the sharp render is asked for");
        long settle = dev.custommodels.cmv.explorer.ui.DetailScreen.SETTLE_MILLIS;

        says("a spinning view never asks for it",
                moving(false, true, 10_000));
        says("nor does one being dragged",
                moving(true, false, 10_000));
        says("a paused view that has sat still does",
                !moving(false, false, settle + 1));

        // The sequence the angle is changed by: pause, drag, let go, wait.
        says("1. paused and settled - sharp", !moving(false, false, settle * 5));
        says("2. grab it - straight back to cheap", moving(true, false, 0));
        says("3. still dragging - still cheap", moving(true, false, 0));
        says("4. let go - cheap through the settle", moving(false, false, 0));
        says("5. part way through the settle - still cheap",
                moving(false, false, settle / 2));
        says("6. settled at the new angle - sharp again",
                !moving(false, false, settle + 1));

        // Nothing is asked for large on the way past: a drag that pauses for a
        // moment between movements must not trigger a render each time.
        boolean quietThroughout = true;
        for (long gap = 0; gap < settle; gap += 10) {
            if (!moving(false, false, gap)) {
                quietThroughout = false;
            }
        }
        says("a nudge-and-pause drag asks for nothing large", quietThroughout);
    }

    private static boolean moving(boolean dragging, boolean spinning, long since) {
        return dev.custommodels.cmv.explorer.ui.DetailScreen
                .moving(dragging, spinning, since);
    }

    public static void main(String[] args) {

        howBig();
        whenItSharpens();

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
