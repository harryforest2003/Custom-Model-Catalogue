import dev.custommodels.cmv.explorer.render.FigurePreview;
import dev.custommodels.cmv.explorer.render.FigurePreview.Act;
import dev.custommodels.cmv.explorer.render.FigurePreview.Beat;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemUseAnimation;

/**
 * The figure view: where its loops have got to, and what it does with an item.
 *
 * <p>Nothing here renders anything. Everything the figure view does that can
 * be wrong without looking wrong is in two pure methods, and this walks both
 * of them. How large the model render is asked for, and when, is the other
 * view's business and is checked in {@code StillRenderTest}.</p>
 *
 * <h2>Why the loop is worth a harness</h2>
 *
 * <p>The pull of a bow and the wind of a crossbow are not drawn by this mod.
 * They are dispatched by the game off two properties — {@code use_duration}
 * scaled by 0.05, and {@code crossbow/pull} divided by the charge duration —
 * and both of those turn a tick count into a number that is meant to land on
 * one at the end and never go past it. A loop that runs a tick long pushes the
 * value over, and the bow or crossbow silently stops at its last model while
 * the arms carry on moving. On screen that is a figure whose hands do not
 * match what it is holding for a few frames of every loop, which is exactly
 * the kind of thing nobody can see and everybody can feel.</p>
 *
 * <h2>And why the classification is</h2>
 *
 * <p>Because the point of asking the item rather than listing items is that
 * the answers come from the game, and an answer that comes from the game can
 * change underneath you. These check the four that matter against the real
 * registry: a helmet is worn, a shield goes to the offhand, a bow draws and a
 * crossbow does something else.</p>
 */
public final class FigureTest {

    private static int passed;
    private static int failed;

    private FigureTest() {
    }

    private static void says(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (ok) {
            passed++;
        } else {
            failed++;
        }
    }

    /** One tick of the loop, in milliseconds. */
    private static long at(int tick) {
        return tick * 50L;
    }

    // ------------------------------------------------------------------ bow

    private static void theBow() {
        System.out.println("the bow");
        int full = FigurePreview.BOW_DRAW_TICKS;

        int drawing = 0;
        int pinned = 0;
        int resting = 0;
        int over = 0;
        boolean reachedFull = false;
        boolean everCharged = false;
        // Four hundred ticks is several loops at any phase length, so every
        // part of the cycle is walked more than once.
        for (int tick = 0; tick < 400; tick++) {
            Beat beat = FigurePreview.beat(Act.BOW, at(tick), 25, 72000);
            if (beat.elapsed() > full) {
                over++;
            }
            if (beat.charged()) {
                everCharged = true;
            }
            if (!beat.using()) {
                resting++;
                if (beat.elapsed() != 0) {
                    over++;
                }
                continue;
            }
            if (beat.elapsed() == full) {
                pinned++;
                reachedFull = true;
            } else {
                drawing++;
            }
        }
        says("the draw never runs past a full pull", over == 0);
        says("and it does reach one", reachedFull);
        says("it is held there for a while", pinned > 0);
        says("it spends time drawing", drawing > 0);
        says("and it relaxes between loops", resting > 0);
        says("a bow is never charged, which is a crossbow's word", !everCharged);

        // The draw climbs. Taken from the first loop, which starts at tick
        // zero because the clock is a multiple of the phase length there.
        boolean climbs = true;
        for (int tick = 1; tick < full; tick++) {
            int before = FigurePreview.beat(Act.BOW, at(tick - 1), 25, 72000).elapsed();
            int now = FigurePreview.beat(Act.BOW, at(tick), 25, 72000).elapsed();
            if (now != before + 1) {
                climbs = false;
            }
        }
        says("and it climbs a tick at a time from the start", climbs);
        says("the first tick of a loop is the start of the draw",
                FigurePreview.beat(Act.BOW, 0L, 25, 72000).elapsed() == 0
                        && FigurePreview.beat(Act.BOW, 0L, 25, 72000).using());
    }

    // ------------------------------------------------------------- crossbow

    private static void theCrossbow() {
        System.out.println();
        System.out.println("the crossbow");
        int charge = 25;

        int winding = 0;
        int ready = 0;
        int resting = 0;
        int over = 0;
        int bothAtOnce = 0;
        for (int tick = 0; tick < 400; tick++) {
            Beat beat = FigurePreview.beat(Act.CROSSBOW, at(tick), charge, 72000);
            if (beat.using() && beat.charged()) {
                bothAtOnce++;
            }
            if (beat.using()) {
                winding++;
                // The wind is divided by the charge duration, so reaching it
                // is already past the last pulling model.
                if (beat.elapsed() >= charge) {
                    over++;
                }
            } else if (beat.charged()) {
                ready++;
            } else {
                resting++;
            }
        }
        says("the wind never reaches past its last stage", over == 0);
        says("it is never winding and loaded at once", bothAtOnce == 0);
        says("it spends time winding", winding > 0);
        says("it is held loaded", ready > 0);
        says("and it empties again between loops", resting > 0);
        says("winding starts a loop",
                FigurePreview.beat(Act.CROSSBOW, 0L, charge, 72000).using());

        // A quick-charge crossbow winds faster, and the loop has to follow the
        // item rather than a fixed number - otherwise the arms stop moving
        // while the model carries on, or the other way about.
        int quickWinding = 0;
        for (int tick = 0; tick < 400; tick++) {
            if (FigurePreview.beat(Act.CROSSBOW, at(tick), 10, 72000).using()) {
                quickWinding++;
            }
        }
        says("a crossbow that winds faster spends less time winding",
                quickWinding < winding);

        // The two are not the same animation, which is the thing that was
        // asked for.
        boolean differ = false;
        for (int tick = 0; tick < 400; tick++) {
            Beat bow = FigurePreview.beat(Act.BOW, at(tick), charge, 72000);
            Beat crossbow = FigurePreview.beat(Act.CROSSBOW, at(tick), charge, 72000);
            if (bow.using() != crossbow.using() || bow.charged() != crossbow.charged()) {
                differ = true;
            }
        }
        says("a bow and a crossbow do not do the same thing", differ);
    }

    // ------------------------------------------------------- the other acts

    private static void theRest() {
        System.out.println();
        System.out.println("holding, and using anything else");

        boolean everUses = false;
        for (int tick = -40; tick < 400; tick++) {
            Beat beat = FigurePreview.beat(Act.HOLD, at(tick), 25, 72000);
            if (beat.using() || beat.charged() || beat.elapsed() != 0) {
                everUses = true;
            }
        }
        says("holding something never uses it", !everUses);

        // A shield asks for seventy-two thousand ticks of blocking. The loop
        // is capped, or the figure would raise it once and never lower it.
        int using = 0;
        for (int tick = 0; tick < 400; tick++) {
            if (FigurePreview.beat(Act.USE, at(tick), 25, 72000).using()) {
                using++;
            }
        }
        says("an endless use is capped so the loop comes round", using < 400);

        // Food takes about thirty ticks and should not be capped at all.
        int eating = 0;
        int idle = 0;
        for (int tick = 0; tick < 400; tick++) {
            if (FigurePreview.beat(Act.USE, at(tick), 25, 32).using()) {
                eating++;
            } else {
                idle++;
            }
        }
        says("a short use runs and then pauses", eating > 0 && idle > 0);

        // A clock before the epoch must not index backwards. floorMod, not %.
        boolean sane = true;
        for (int tick = -200; tick < 0; tick++) {
            for (Act act : Act.values()) {
                Beat beat = FigurePreview.beat(act, at(tick), 25, 72000);
                if (beat.elapsed() < 0) {
                    sane = false;
                }
            }
        }
        says("a clock before the epoch still reads forwards", sane);

        // Nonsense inputs rather than a crash: a zero charge duration is one
        // division by zero away from an unrenderable panel.
        boolean survives = true;
        try {
            for (Act act : Act.values()) {
                FigurePreview.beat(act, 1234L, 0, 0);
                FigurePreview.beat(act, 1234L, -5, -5);
            }
        } catch (RuntimeException e) {
            survives = false;
        }
        says("a zero or negative duration is survived", survives);
    }

    // ------------------------------------------------- what goes where, why

    private static void whatGoesWhere() {
        System.out.println();
        System.out.println("what the figure does with an item");

        says("a helmet is worn on the head",
                FigurePreview.slotFor(EquipmentSlot.HEAD) == EquipmentSlot.HEAD);
        says("and boots on the feet",
                FigurePreview.slotFor(EquipmentSlot.FEET) == EquipmentSlot.FEET);
        says("a shield goes to the offhand",
                FigurePreview.slotFor(EquipmentSlot.OFFHAND) == EquipmentSlot.OFFHAND);
        says("something equippable into the main hand stays there",
                FigurePreview.slotFor(EquipmentSlot.MAINHAND) == EquipmentSlot.MAINHAND);
        says("a sword, which is equippable into nothing, is held",
                FigurePreview.slotFor((EquipmentSlot) null) == EquipmentSlot.MAINHAND);
        says("a saddle names a slot a player has not got, so it is held",
                FigurePreview.slotFor(EquipmentSlot.SADDLE) == EquipmentSlot.MAINHAND);
        says("and so does body armour for an animal",
                FigurePreview.slotFor(EquipmentSlot.BODY) == EquipmentSlot.MAINHAND);

        // Every slot the game has lands somewhere a player can actually carry
        // it. A new one added in a future version falls through to the hand
        // rather than to an exception or an invisible figure.
        boolean allCarryable = true;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            EquipmentSlot went = FigurePreview.slotFor(slot);
            if (went.getType() != EquipmentSlot.Type.HUMANOID_ARMOR
                    && went.getType() != EquipmentSlot.Type.HAND) {
                allCarryable = false;
            }
        }
        says("every slot the game has ends up somewhere a player can carry it",
                allCarryable);

        says("a bow draws",
                FigurePreview.actFor(ItemUseAnimation.BOW, EquipmentSlot.MAINHAND)
                        == Act.BOW);
        says("a crossbow winds",
                FigurePreview.actFor(ItemUseAnimation.CROSSBOW, EquipmentSlot.MAINHAND)
                        == Act.CROSSBOW);
        says("and the two are told apart",
                FigurePreview.actFor(ItemUseAnimation.BOW, EquipmentSlot.MAINHAND)
                        != FigurePreview.actFor(ItemUseAnimation.CROSSBOW,
                                EquipmentSlot.MAINHAND));
        says("something edible is eaten",
                FigurePreview.actFor(ItemUseAnimation.EAT, EquipmentSlot.MAINHAND)
                        == Act.USE);
        says("a spyglass is looked through",
                FigurePreview.actFor(ItemUseAnimation.SPYGLASS, EquipmentSlot.MAINHAND)
                        == Act.USE);
        says("a shield is raised, in the hand it is in",
                FigurePreview.actFor(ItemUseAnimation.BLOCK, EquipmentSlot.OFFHAND)
                        == Act.USE);
        says("a block, which is used for nothing, is simply held",
                FigurePreview.actFor(ItemUseAnimation.NONE, EquipmentSlot.MAINHAND)
                        == Act.HOLD);
        says("nothing at all is held, not used",
                FigurePreview.actFor((ItemUseAnimation) null, EquipmentSlot.MAINHAND)
                        == Act.HOLD);

        // Worn is worn: a helmet has nothing to do with its hands, whatever
        // use animation the item happens to declare.
        boolean wornIdle = true;
        for (ItemUseAnimation animation : ItemUseAnimation.values()) {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                if (slot.getType() != EquipmentSlot.Type.HUMANOID_ARMOR) {
                    continue;
                }
                if (FigurePreview.actFor(animation, slot) != Act.HOLD) {
                    wornIdle = false;
                }
            }
        }
        says("anything worn does nothing with its hands", wornIdle);

        // And every animation the game has gets an answer, with the bow and
        // the crossbow the only two that get their own.
        int own = 0;
        boolean allAnswered = true;
        for (ItemUseAnimation animation : ItemUseAnimation.values()) {
            Act act = FigurePreview.actFor(animation, EquipmentSlot.MAINHAND);
            if (act == null) {
                allAnswered = false;
            }
            if (act == Act.BOW || act == Act.CROSSBOW) {
                own++;
            }
        }
        says("every use animation the game has gets an answer", allAnswered);
        says("and exactly two of them get an animation of their own", own == 2);
    }

    // ------------------------------------------------------------- which way

    private static void whichWay() {
        System.out.println();
        System.out.println("which way the figure faces");

        // The bug this exists to stop coming back: yRot is the head's yaw
        // relative to the body, so anything put in it turns the head further
        // than the body went. Half a turn of that and the body faces away
        // while the face still looks at you.
        boolean headInLine = true;
        for (int yaw = -720; yaw <= 720; yaw += 3) {
            FigurePreview.Facing facing = FigurePreview.facing(yaw);
            if (facing.headYaw() != 0.0F || facing.headPitch() != 0.0F) {
                headInLine = false;
            }
        }
        says("the head is never turned away from the body, at any angle",
                headInLine);

        // And the body carries all of the turn, one degree for one.
        boolean oneForOne = true;
        for (int yaw = -720; yaw <= 720; yaw += 3) {
            float moved = FigurePreview.facing(yaw).bodyRot()
                    - FigurePreview.facing(0).bodyRot();
            if (Math.abs(moved - yaw) > 0.001F) {
                oneForOne = false;
            }
        }
        says("and it carries the whole turn, a degree for a degree", oneForOne);

        says("at rest the figure faces you",
                FigurePreview.facing(0).bodyRot() == 180.0F);
    }

    // ------------------------------------------------- wings and other backs

    private static dev.custommodels.cmv.explorer.res.ModelJson.Transform at(
            float x, float y, float z) {
        return new dev.custommodels.cmv.explorer.res.ModelJson.Transform(
                new float[]{0, 0, 0}, new float[]{x, y, z}, new float[]{1, 1, 1});
    }

    private static boolean offhand(
            dev.custommodels.cmv.explorer.res.ModelJson.Transform left, boolean leftSaid,
            dev.custommodels.cmv.explorer.res.ModelJson.Transform right, boolean rightSaid) {
        return FigurePreview.wantsOffhand(left, leftSaid, right, rightSaid);
    }

    private static void wingsAndBacks() {
        System.out.println();
        System.out.println("what the model says about the offhand");
        var none = dev.custommodels.cmv.explorer.res.ModelJson.Transform.IDENTITY;

        // Wings: the left hand is sent a torso's width away, the right says
        // nothing. This is the shape the whole feature exists for.
        says("a model that sends the left hand to your back wants the offhand",
                offhand(at(0, 8, -6), true, none, false));

        // An ordinary item nudged into a fist is not wearing anything.
        says("a small left-hand nudge is still just held",
                !offhand(at(0, 3, 1), true, none, false));

        // Nothing declared about the left hand says nothing at all: Minecraft
        // mirrors the right-hand transform, so there is no statement to read.
        says("an undeclared left hand is not a request",
                !offhand(at(0, 9, -7), false, none, false));
        says("not even when the right hand is displaced",
                !offhand(none, false, at(0, 9, -7), true));

        // Both hands told to do the same thing is a model that does not care.
        says("the same placement in both hands is not an offhand model",
                !offhand(at(0, 8, -6), true, at(0, 8, -6), true));
        says("but a different one in each is",
                offhand(at(0, 8, -6), true, at(0, 2, 1), true));

        // A model that displaces the right hand further was drawn for it.
        says("a further right hand wins",
                !offhand(at(0, 7, 0), true, at(0, 14, 0), true));
        says("and a further left hand does not lose",
                offhand(at(0, 14, 0), true, at(0, 7, 0), true));

        // Any axis will do: a cape goes backwards, a halo goes up.
        says("reach on any one axis counts",
                offhand(at(-9, 0, 0), true, none, false)
                        && offhand(at(0, 0, 9), true, none, false));

        // The real vanilla values, read out of the 26.2 assets. These are the
        // cases that must never move: every sword, axe and pickaxe in the game
        // inherits item/handheld, and sending those to the offhand would be
        // worse than never having tried.
        var handheldLeft = at(0, 4.0F, 0.5F);
        var handheldRight = at(0, 4.0F, 0.5F);
        says("a sword stays in the main hand (item/handheld)",
                !offhand(handheldLeft, true, handheldRight, true));
        says("so does a mace (item/handheld_mace)",
                !offhand(at(0, 4.0F, 1), true, at(0, 4.0F, 1), true));
        says("and a fishing rod (item/handheld_rod)",
                !offhand(at(0, 4.0F, 2.5F), true, at(0, 4.0F, 2.5F), true));
        says("and a bow (item/bow)",
                !offhand(at(-1, -2, 2.5F), true, at(-1, -2, 2.5F), true));
        says("a feather declares no left hand at all (item/generated)",
                !offhand(none, false, at(0, 3, 1), true));

        // item/shield does reach twelve and would read as an offhand model.
        // It is never asked, because being equippable to the offhand settles
        // it first - but if that order were ever reversed this is the case
        // that would start lying about why.
        says("a shield would read as an offhand model on its transform alone",
                offhand(at(10, 6, 12), true, at(10, 6, -4), true));

        // Nothing in, no crash and no claim.
        says("nothing declared at all is not a request",
                !offhand(null, false, null, false));
        says("and a declared nothing is not either",
                !offhand(none, true, none, false));
    }

    public static void main(String[] args) {

        theBow();
        theCrossbow();
        theRest();
        whatGoesWhere();
        whichWay();
        wingsAndBacks();

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
