import dev.custommodels.cmv.explorer.render.FigurePreview;
import dev.custommodels.cmv.explorer.render.FigurePreview.Act;
import dev.custommodels.cmv.explorer.render.FigurePreview.Beat;
import dev.custommodels.cmv.core.FigurePlacements;
import dev.custommodels.cmv.explorer.res.ModelJson;
import dev.custommodels.cmv.explorer.graph.ItemType;
import dev.custommodels.cmv.explorer.graph.ResolvedName;
import dev.custommodels.cmv.wrap.WrapPreview;
import dev.custommodels.cmv.wrap.WrapTarget;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemUseAnimation;

import java.util.List;

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
    /**
     * The placement cycle: that the control reaches everywhere, and comes back.
     *
     * <p>Worth a harness because the whole reason the cycle exists is that
     * nothing can decide a placement from the item. A model drawn for a helmet
     * gets applied to a pickaxe and a model drawn for a tool gets applied to a
     * helmet, and from the item and the model alone those two are the same
     * situation. So the only correct behaviour is to reach everywhere and let
     * the person looking at it decide - which makes "everywhere is reachable"
     * the property, and a cycle that quietly dropped a placement would be a
     * thing you could only find by clicking a button six times.</p>
     */
    private static void everyPlacement() {
        System.out.println();
        System.out.println("the placements the control walks");

        // A sword: equippable into nothing, so held. Both hands and the head.
        List<EquipmentSlot> held = drawable(EquipmentSlot.MAINHAND);
        says("something held offers both hands and the head", held.size() == 3
                && held.contains(EquipmentSlot.MAINHAND)
                && held.contains(EquipmentSlot.OFFHAND)
                && held.contains(EquipmentSlot.HEAD));

        // A helmet. The head is already in the cycle, so it is not offered twice.
        List<EquipmentSlot> helmet = drawable(EquipmentSlot.HEAD);
        says("a helmet offers both hands and the head, and the head once",
                helmet.size() == 3 && helmet.contains(EquipmentSlot.HEAD));

        // A shield. Likewise for the offhand.
        List<EquipmentSlot> shield = drawable(EquipmentSlot.OFFHAND);
        says("a shield offers both hands and the head, and the offhand once",
                shield.size() == 3 && shield.contains(EquipmentSlot.OFFHAND));

        // A chestplate. A hands-and-head cycle would never reach the one
        // placement the thing was made for.
        List<EquipmentSlot> chest = drawable(EquipmentSlot.CHEST);
        says("chest armour is offered the chest as well", chest.size() == 4
                && chest.contains(EquipmentSlot.CHEST));
        says("and boots the feet",
                drawable(EquipmentSlot.FEET)
                        .contains(EquipmentSlot.FEET));

        // A saddle names a slot a player has not got. It must not become a
        // placement, or the control would step into a slot nothing draws.
        says("a saddle adds no placement of its own",
                drawable(EquipmentSlot.SADDLE).size() == 3);

        // Every slot the game has: a cycle of placements a player can carry,
        // no duplicates, and the hands and the head always in it.
        boolean sane = true;
        for (EquipmentSlot worn : EquipmentSlot.values()) {
            List<EquipmentSlot> order = drawable(worn);
            if (!order.contains(EquipmentSlot.MAINHAND)
                    || !order.contains(EquipmentSlot.OFFHAND)
                    || !order.contains(EquipmentSlot.HEAD)) {
                sane = false;
            }
            for (EquipmentSlot each : order) {
                if (each.getType() != EquipmentSlot.Type.HUMANOID_ARMOR
                        && each.getType() != EquipmentSlot.Type.HAND) {
                    sane = false;
                }
                if (order.indexOf(each) != order.lastIndexOf(each)) {
                    sane = false;
                }
            }
        }
        says("every item's cycle holds both hands, the head, and no repeats",
                sane);

        // Stepping. From anywhere in a cycle, as many steps as it is long comes
        // back to where it started, and visits the whole of it on the way.
        boolean walks = true;
        for (EquipmentSlot worn : EquipmentSlot.values()) {
            List<EquipmentSlot> order = drawable(worn);
            for (EquipmentSlot from : order) {
                EquipmentSlot at = from;
                boolean[] seen = new boolean[order.size()];
                for (int step = 0; step < order.size(); step++) {
                    at = FigurePreview.nextPlacement(order, at);
                    int where = order.indexOf(at);
                    if (where < 0 || seen[where]) {
                        walks = false;
                    } else {
                        seen[where] = true;
                    }
                }
                if (at != from) {
                    walks = false;
                }
            }
        }
        says("the control reaches every placement and comes back to the first",
                walks);

        says("a step always lands somewhere, never on nothing",
                FigurePreview.nextPlacement(drawable(EquipmentSlot.MAINHAND),
                        EquipmentSlot.MAINHAND) != null);

        // A placement from outside the cycle - a slot a future version adds,
        // arriving here in a saved choice - starts it over rather than throwing.
        says("a placement the cycle has never heard of starts it again",
                FigurePreview.nextPlacement(drawable(EquipmentSlot.MAINHAND),
                        EquipmentSlot.SADDLE) == EquipmentSlot.MAINHAND);

        // Where the card opens, which is the one thing the cycle does not
        // decide. What the item is worn as beats what the model asks for.
        says("a helmet opens on the head, whatever its transforms ask",
                FigurePreview.slotFor(EquipmentSlot.HEAD) == EquipmentSlot.HEAD);

        // And worn is still worn: something on the head has nothing to do with
        // its hands, however the placement got chosen.
        says("a bow worn on the head is held, not drawn",
                FigurePreview.actFor(ItemUseAnimation.BOW, EquipmentSlot.HEAD)
                        == Act.HOLD);
    }

    // ------------------------------------------------- what the pack rules out

    /** A transform that draws normally: everything the pack has not ruled out. */
    private static ModelJson.Transform drawn() {
        return ModelJson.Transform.IDENTITY;
    }

    /** A cycle for a model the pack draws everywhere. */
    private static java.util.List<EquipmentSlot> drawable(EquipmentSlot worn) {
        return FigurePreview.placements(worn, drawn(), drawn(), drawn());
    }

    /** The transform packs use to say "not here": a scale of nothing. */
    private static ModelJson.Transform collapsed() {
        return new ModelJson.Transform(new float[]{0, 0, 0},
                new float[]{0, 0, 0}, new float[]{0, 0, 0});
    }

    /**
     * Placements the pack has ruled out, and the one reading that is not a guess.
     *
     * <p>Read off the pack set this was developed against: 22 of the 87 models
     * that declare a head transform declare it zero-scaled, and no model in
     * either pack zero-scales anything else. So this is the pack saying "not
     * here" and it is the only part of a placement that is not undecidable.</p>
     */
    private static void whatThePackRulesOut() {
        System.out.println();
        System.out.println("placements the pack rules out");

        says("a transform that draws normally rules nothing out",
                !FigurePreview.drawsNothing(ModelJson.Transform.IDENTITY));
        says("a zero scale on every axis rules the placement out",
                FigurePreview.drawsNothing(collapsed()));

        // One axis is enough: the geometry is flat either way.
        says("a zero on one axis alone is enough",
                FigurePreview.drawsNothing(new ModelJson.Transform(
                        new float[]{0, 0, 0}, new float[]{0, 0, 0},
                        new float[]{1, 0, 1})));

        // The real shape of the thing in the packs: translation, no scale key.
        // Scaling by nothing and saying nothing about scale are opposites, and
        // a helmet that only nudges itself down must not be read as ruled out.
        says("a transform with a translation and no scale draws",
                !FigurePreview.drawsNothing(new ModelJson.Transform(
                        null, new float[]{0, -3.25F, 0}, null)));
        says("and nothing at all draws",
                !FigurePreview.drawsNothing(null));

        // A chestplate of one of those armour sets: zero-scaled on the head,
        // so the head is not offered, and the chest still is.
        java.util.List<EquipmentSlot> vest = FigurePreview.placements(
                EquipmentSlot.CHEST, drawn(), drawn(), collapsed());
        says("a model zero-scaled on the head is not offered the head",
                !vest.contains(EquipmentSlot.HEAD));
        says("and is still offered both hands and its own chest",
                vest.size() == 3 && vest.contains(EquipmentSlot.MAINHAND)
                        && vest.contains(EquipmentSlot.OFFHAND)
                        && vest.contains(EquipmentSlot.CHEST));

        // The helmet from the same set, which gives a real head transform.
        java.util.List<EquipmentSlot> helm = FigurePreview.placements(
                EquipmentSlot.HEAD, drawn(), drawn(), drawn());
        says("the helmet beside it keeps the head", helm.contains(EquipmentSlot.HEAD));

        // Where the item is worn wins over a transform that rules it out: the
        // armour layer draws it there whatever the model's own transform says.
        java.util.List<EquipmentSlot> odd = FigurePreview.placements(
                EquipmentSlot.HEAD, drawn(), drawn(), collapsed());
        says("a helmet that rules out its own head is still offered the head",
                odd.contains(EquipmentSlot.HEAD));

        // A pack that ruled out everything still leaves somewhere to look.
        java.util.List<EquipmentSlot> none = FigurePreview.placements(
                EquipmentSlot.MAINHAND, collapsed(), collapsed(), collapsed());
        says("a model ruled out of everywhere still has one placement",
                none.size() == 1 && none.contains(EquipmentSlot.MAINHAND));

        // And the cycle still steps, rather than dividing by a length of none.
        boolean steps = true;
        try {
            if (FigurePreview.nextPlacement(none, EquipmentSlot.MAINHAND)
                    != EquipmentSlot.MAINHAND) {
                steps = false;
            }
        } catch (RuntimeException e) {
            steps = false;
        }
        says("a cycle of one steps to itself", steps);

        // Opening. A placement the cycle cannot reach is snapped into it, so
        // the card never opens somewhere the control cannot return to.
        says("an opening placement the cycle has is kept",
                FigurePreview.openAt(vest, EquipmentSlot.OFFHAND)
                        == EquipmentSlot.OFFHAND);
        says("one it has not is snapped to the first it does",
                FigurePreview.openAt(vest, EquipmentSlot.HEAD)
                        == vest.get(0));

        // Hands are filtered by the same rule, not special-cased to the head.
        // No pack in the set does this, and the code should not care.
        java.util.List<EquipmentSlot> hatOnly = FigurePreview.placements(
                EquipmentSlot.MAINHAND, collapsed(), collapsed(), drawn());
        says("a model ruled out of both hands is offered the head",
                hatOnly.contains(EquipmentSlot.HEAD));
        says("and the hand it is held in, because that is where it belongs",
                hatOnly.contains(EquipmentSlot.MAINHAND));
    }

    // ------------------------------------------------- remembering the choice

    /**
     * Reading a saved placement back, which happens from a file people edit.
     *
     * <p>Worth a harness for one reason: {@code EquipmentSlot.byName} throws on
     * anything it does not recognise, so the obvious way to write this turns a
     * typo in a config file into a card that will not open. Every one of these
     * asks whether a bad value is survived.</p>
     */
    private static void rememberingTheChoice() {
        System.out.println();
        System.out.println("reading a saved placement back");

        // Every slot the game has round-trips through its own name. This is the
        // property that matters: what is written is what comes back.
        boolean roundTrips = true;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (FigurePreview.placementByName(slot.getSerializedName()) != slot) {
                roundTrips = false;
            }
        }
        says("every slot round-trips through the name it is saved as", roundTrips);

        says("mainhand reads back as the main hand",
                FigurePreview.placementByName("mainhand") == EquipmentSlot.MAINHAND);
        says("offhand as the offhand",
                FigurePreview.placementByName("offhand") == EquipmentSlot.OFFHAND);
        says("head as the head",
                FigurePreview.placementByName("head") == EquipmentSlot.HEAD);

        // Nothing saved, which is every model nobody has moved.
        says("nothing saved is no choice",
                FigurePreview.placementByName(null) == null);
        says("and an empty value is no choice either",
                FigurePreview.placementByName("") == null);

        // A file edited by hand. None of these may throw, and none may guess.
        String[] wrong = {"MAINHAND", "Head", " head", "head ", "main_hand",
                "hand", "1", "3", "0", "-1", "null", "offhandd", "\u00e9t\u00eate"};
        boolean survived = true;
        for (String each : wrong) {
            try {
                if (FigurePreview.placementByName(each) != null) {
                    survived = false;
                }
            } catch (RuntimeException e) {
                survived = false;
            }
        }
        says("a name the game does not use is no choice, and does not throw",
                survived);

        // The numbering that was considered and not used. If these ever read as
        // placements, something has started inventing its own scheme.
        says("a number is not a placement",
                FigurePreview.placementByName("2") == null);

        // A saved placement the model can no longer be seen in. The pack may
        // have ruled it out since it was chosen, and the card still has to open.
        java.util.List<EquipmentSlot> vest = FigurePreview.placements(
                EquipmentSlot.CHEST, drawn(), drawn(), collapsed());
        EquipmentSlot saved = FigurePreview.placementByName("head");
        says("a saved placement the pack has since ruled out is snapped into the cycle",
                FigurePreview.openAt(vest, saved) == vest.get(0));
        says("and one it still allows is kept",
                FigurePreview.openAt(vest, FigurePreview.placementByName("chest"))
                        == EquipmentSlot.CHEST);

        // A slot a player has not got, saved by hand. It parses - it is a real
        // slot - and the cycle is what refuses it.
        says("a slot a player has not got never survives the cycle",
                FigurePreview.openAt(vest, FigurePreview.placementByName("saddle"))
                        == vest.get(0));
    }

    // ------------------------------------------------- not writing for nothing

    /** The placements a file holds, as the store would spell them. */
    private static String onDisk(String... pairs) {
        java.util.Map<String, String> held = new java.util.TreeMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            held.put(pairs[i], pairs[i + 1]);
        }
        return FigurePlacements.pending(held, null);
    }

    /**
     * Whether leaving the viewer writes anything, and when it should not.
     *
     * <p>The case this is really about: open one card, cycle the placement all
     * the way round, and end on the one that was already saved. Every single
     * step of that is a change measured against the step before it, so a store
     * that asked "did this click change anything" would write a file identical
     * to the one already there. Measured against the file, nothing changed and
     * nothing should be written — which also means no write, no filesystem,
     * and none of the tail that comes with touching it.</p>
     */
    private static void notWritingForNothing() {
        System.out.println();
        System.out.println("leaving the viewer without writing for nothing");

        String saved = onDisk("pack:item/tools/pickaxe", "head");

        // The whole point. head -> mainhand -> offhand -> head, as the control
        // steps it, and the map ends up exactly as the file already has it.
        java.util.Map<String, String> live = new java.util.TreeMap<>();
        live.put("pack:item/tools/pickaxe", "head");
        live.put("pack:item/tools/pickaxe", "mainhand");
        live.put("pack:item/tools/pickaxe", "offhand");
        live.put("pack:item/tools/pickaxe", "head");
        says("cycling round to where it started writes nothing",
                FigurePlacements.pending(live, saved) == null);

        // Stopping one short of where it started does write.
        live.put("pack:item/tools/pickaxe", "mainhand");
        says("stopping anywhere else writes",
                FigurePlacements.pending(live, saved) != null);

        // Two placements of the same length. head, legs and feet are all four
        // letters, so this is the case that tells comparing the content apart
        // from comparing how much of it there is. Without it, a check on the
        // length alone passes everything here.
        java.util.Map<String, String> sameLength = new java.util.TreeMap<>();
        sameLength.put("pack:item/tools/pickaxe", "feet");
        says("a different placement of the very same length still writes",
                FigurePlacements.pending(sameLength, saved) != null);
        says("and the two names really are the same length",
                "head".length() == "feet".length());

        // A second model moved in the same session is a change even though the
        // first one went back to where it was.
        java.util.Map<String, String> two = new java.util.TreeMap<>();
        two.put("pack:item/tools/pickaxe", "head");
        two.put("pack:item/hats/crown", "mainhand");
        says("another model moved in the same session writes",
                FigurePlacements.pending(two, saved) != null);

        // Nothing touched at all.
        java.util.Map<String, String> same = new java.util.TreeMap<>();
        same.put("pack:item/tools/pickaxe", "head");
        says("a session that moved nothing writes nothing",
                FigurePlacements.pending(same, saved) == null);

        // The order things were put in must not count as a change, or every
        // session would write: a card visited in a different order is normal.
        // Inserted the other way round from how they sort - pickaxe before
        // crown, where crown sorts first - because an insertion order that is
        // already the sorted one would not notice the sorting going missing.
        java.util.Map<String, String> reordered = new java.util.LinkedHashMap<>();
        reordered.put("pack:item/tools/pickaxe", "head");
        reordered.put("pack:item/hats/crown", "mainhand");
        says("the order they were moved in is not a change",
                FigurePlacements.pending(reordered, onDisk(
                        "pack:item/tools/pickaxe", "head",
                        "pack:item/hats/crown", "mainhand")) == null);
        says("and that order is not the sorted one, so the check means something",
                "pack:item/tools/pickaxe".compareTo("pack:item/hats/crown") > 0);

        // An empty session against an empty file: no write, and so no file
        // created to say that nothing was chosen.
        says("an empty session against no file at all writes nothing",
                FigurePlacements.pending(new java.util.TreeMap<>(), onDisk()) == null);

        // A first placement, with no file yet.
        java.util.Map<String, String> first = new java.util.TreeMap<>();
        first.put("pack:item/hats/crown", "head");
        says("the first placement ever chosen writes",
                FigurePlacements.pending(first, onDisk()) != null);

        // Never read or written. Everything is a change against nothing.
        says("with nothing known about the file, a write is taken",
                FigurePlacements.pending(first, null) != null);

        // What gets written is readable, and reads back as what it says.
        String written = FigurePlacements.pending(first, null);
        says("what is written names the model and the slot",
                written != null && written.contains("pack:item/hats/crown")
                        && written.contains("head"));
        says("and the slot in it parses back to the slot",
                FigurePreview.placementByName("head") == EquipmentSlot.HEAD);
    }


    /**
     * What the figure is given to hold, when the pack names nothing holdable.
     *
     * <p>The case this exists for is the ordinary one in a pack that keeps its
     * definitions in its own namespace. A definition at {@code
     * lf:items/count_heart_of_the_sea.json} becomes an item_model wrap that
     * fits anything, which is recorded as an empty compatibility list — and
     * the item it is named after, {@code lf:count_heart_of_the_sea}, is not a
     * registered item. So the pack offers the appearance on nothing in
     * particular and names no real item, and the figure was handed an empty
     * stack and said there was nothing to show it on. In the pack this was
     * found against that is 4,483 definitions out of 4,840.</p>
     */
    private static void somethingToPutItOn() {
        System.out.println();
        System.out.println("what the figure is given to hold");

        says("a wrap that fits anything goes on a carrier when nothing else can",
                WrapPreview.carrierFor(universal(), "lf:count_heart_of_the_sea", false)
                        .equals(WrapPreview.CARRIER));
        says("and on the real item when the definition names one",
                WrapPreview.carrierFor(universal(), "minecraft:paper", true)
                        .equals("minecraft:paper"));
        says("an item the pack lists wins over the carrier",
                WrapPreview.carrierFor(fits("minecraft:diamond_sword"), "lf:x", false)
                        .equals("minecraft:diamond_sword"));
        says("and over the registered base item too, since the pack said so",
                WrapPreview.carrierFor(fits("minecraft:diamond_sword"), "minecraft:paper", true)
                        .equals("minecraft:diamond_sword"));
        says("no wrap and no real item leaves nothing to put it on",
                WrapPreview.carrierFor(null, "lf:x", false).isEmpty());
        says("no wrap but a real item uses that item",
                WrapPreview.carrierFor(null, "minecraft:paper", true)
                        .equals("minecraft:paper"));
        says("the carrier is a real item id",
                WrapPreview.CARRIER.startsWith("minecraft:"));

        says("a definition becomes the item_model that draws it",
                WrapPreview.definitionAsItemModel("lf:items/count_heart_of_the_sea.json")
                        .equals("lf:count_heart_of_the_sea"));
        says("a nested definition keeps its folders",
                WrapPreview.definitionAsItemModel("modelengine:items/nm_gnome_red/upperbody.json")
                        .equals("modelengine:nm_gnome_red/upperbody"));
        says("one already stripped is left alone",
                WrapPreview.definitionAsItemModel("minecraft:bow").equals("minecraft:bow"));
        says("nothing is not a definition",
                WrapPreview.definitionAsItemModel(null).isEmpty());
        says("and neither is a bare path with no namespace",
                WrapPreview.definitionAsItemModel("items/bow.json").isEmpty());
        says("a definition of only a namespace yields nothing",
                WrapPreview.definitionAsItemModel("lf:items/.json").isEmpty());
    }

    /** An item_model wrap that fits anything: the empty compatibility list. */
    private static WrapTarget universal() {
        return target(WrapTarget.Kind.ITEM_MODEL, List.of());
    }

    /** An item_model wrap the pack says fits one named item. */
    private static WrapTarget fits(String itemId) {
        return target(WrapTarget.Kind.ITEM_MODEL, List.of(itemId));
    }

    private static WrapTarget target(WrapTarget.Kind kind, List<String> compatible) {
        // The model is not read by anything under test, and building a real one
        // needs a scan, so it is left out rather than faked into existence.
        return new WrapTarget("test", new ResolvedName("Test",
                ResolvedName.Source.ITEM_NAME, "test"), ItemType.ALL, kind,
                "lf:count_heart_of_the_sea", "", List.of(), java.util.Map.of(),
                compatible, null);
    }

    // ----------------------------------------------- exported animation shims

    private static int spinTicks(float seconds, int period) {
        return dev.custommodels.cmv.explorer.ui.DetailScreen.spinTicks(seconds, period);
    }

    private static int delay(int ticks, int frames) {
        return dev.custommodels.cmv.explorer.ui.DetailScreen.frameDelay(ticks, frames);
    }

    private static int[] ticksFor(int frames, int ticks) {
        return dev.custommodels.cmv.explorer.ui.DetailScreen.exportTicks(frames, ticks);
    }

    private static int[] statesFor(int frames, java.util.List<Integer> stages, int fallback) {
        return dev.custommodels.cmv.explorer.ui.DetailScreen
                .exportStates(frames, stages, fallback);
    }

    private static boolean within(float value, float of, float by) {
        return Math.abs(value - of) <= by;
    }

    private static int countOf(int[] values, int wanted) {
        int found = 0;
        for (int value : values) {
            if (value == wanted) {
                found++;
            }
        }
        return found;
    }

    private static boolean allOf(int[] values, int wanted) {
        for (int value : values) {
            if (value != wanted) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------- which way a back cosmetic is worn

    /** A transform that also says what it scales to, as the packs do. */
    private static ModelJson.Transform at(float x, float y, float z, float scale) {
        return new ModelJson.Transform(
                new float[]{0, 0, 0}, new float[]{x, y, z},
                new float[]{scale, scale, scale});
    }

    private static boolean head(ModelJson.Transform head, boolean headSaid,
            ModelJson.Transform left, boolean leftSaid) {
        return FigurePreview.wantsHead(head, headSaid, left, leftSaid);
    }

    /**
     * The second of the two ways a pack puts something on your back.
     *
     * <p>Every transform below is copied out of the two packs here, not made
     * up. The offhand test claims all four of these models; what separates
     * them is what their {@code head} transform says, and between them they
     * cover both reasons it can win and both reasons it cannot.</p>
     */
    private static void whichWayItIsWorn() {
        System.out.println();
        System.out.println("whether a back cosmetic is worn on the head");

        // fantastic_wings_set_v1: the head sends it further AND scales it up.
        // The shape the whole correction exists for.
        says("wings the head sends further and larger are worn on the head",
                head(at(0, -15.5F, 7.25F, 1.8F), true, at(-5.5F, -7, 9, 1), true));

        // fairyset: the head does NOT send it as far - 9 against 12 - but
        // draws it at 2.2 against 1.3. Scale alone has to be enough or this
        // one stays in the offhand looking small, which is the complaint.
        says("and so are wings the head only draws larger",
                head(at(0, -9, 7.5F, 2.2F), true, at(-5.5F, -5.75F, 12, 1.3F), true));

        // elitecreatures pirate_weapon_set_v2/wing: a head transform that
        // reaches 20.25 against the hand's 16 - so the reach test says head -
        // and is scaled to nothing, which is the pack saying the head draws
        // nothing at all. Without the guard this pair sends a pair of wings to
        // a placement where there is nothing to see. Six models in these two
        // packs are this shape; 197 declare a zero-scaled head with a reach
        // over six, so it is not a hypothetical.
        says("a head scaled to nothing is the pack saying not there",
                !head(at(0, -20.25F, -0.75F, 0), true, at(-6, -3.75F, 16, 0.9F), true));
        // steampunk: the same instruction where the reach would not have won
        // either, so this one is about the guard and not about the comparison.
        says("even where the reach would not have chosen it",
                !head(at(0, 0, 0, 0), true, at(-6, -5.35F, 10.75F, 2.02F), true));

        // pixelmon_backpacks: no head transform in the file. Nothing to read,
        // so the offhand claim stands rather than being overruled by a guess.
        says("no head transform at all leaves the offhand claim alone",
                !head(null, false, at(-6, -3.5F, 10, 1), true));
        says("and neither does a head transform nobody declared",
                !head(at(0, -15.5F, 7.25F, 1.8F), false, at(-5.5F, -7, 9, 1), true));

        // A head that asks for less on both counts is not where this goes.
        says("a nearer, smaller head does not win",
                !head(at(0, -4, 0, 1), true, at(-5.5F, -7, 9, 1.3F), true));
        says("and matching it exactly is not winning either",
                !head(at(0, -9, 0, 1.5F), true, at(0, -9, 0, 1.5F), true));

        // An absent scale means the model's own size, which is what a scale of
        // one means. The two have to compare the same or a pack that mentions
        // no scale would read as smaller than one that says [1, 1, 1] - and
        // the wings that started all this mention none in the offhand. Asked
        // at the same translation in both, so scale is the only difference
        // left and the reach test cannot answer it instead.
        var unscaled = new dev.custommodels.cmv.explorer.res.ModelJson.Transform(
                new float[]{0, 0, 0}, new float[]{0, -5, 0}, null);
        says("an unmentioned scale counts as one, not as nothing",
                !head(at(0, -5, 0, 1), true, unscaled, true));
        says("and a head larger than it still wins",
                head(at(0, -5, 0, 1.5F), true, unscaled, true));

        // Nothing in, no crash and no claim.
        says("nothing declared at all is not a request",
                !head(null, false, null, false));
        says("and an offhand nobody declared is nothing to be larger than",
                !head(at(0, -15.5F, 7.25F, 1.8F), true, null, false));
    }

    /**
     * The speed of an exported animation, and what used to decide it.
     *
     * <p>The delay written into the file was fixed at a twenty-fourth of a
     * second, so the duration of a turn was the frame count divided by 24: the
     * default 48 frames turned right round in two seconds, which is 180 degrees
     * a second against the detail view's own 18. Ten times the speed of the
     * thing it is a recording of. Worse, asking for a smoother animation
     * bought a slower one and asking for a smaller file bought a blur, because
     * one number was doing two jobs.</p>
     *
     * <p>Duration is set on its own now and the frame count only decides
     * smoothness. The checks below are on the two numbers that decide it, so a
     * change that made the export fast again would fail here.</p>
     */
    private static void howFastAnExportTurns() {
        System.out.println();
        System.out.println("how fast an exported turn goes");

        // Nothing animating: the duration is exactly what was asked for.
        says("eight seconds is a hundred and sixty ticks", spinTicks(8.0F, 1) == 160);
        says("and a period of one changes nothing",
                spinTicks(8.0F, 1) == spinTicks(8.0F, 0));

        // The delay is in hundredths, and is what carries the duration now.
        says("the default turn lasts about eight seconds",
                within(delay(160, 72) * 72 / 100.0F, 8.0F, 0.2F));
        says("and so does it at a different smoothness",
                within(delay(160, 48) * 48 / 100.0F, 8.0F, 0.3F));
        says("so the frame count no longer decides the speed",
                Math.abs(delay(160, 72) * 72 - delay(160, 48) * 48) <= 30);

        // The old behaviour, computed the way the file actually came out, so it
        // cannot come back unnoticed. The old code passed a fixed 1000/24
        // milliseconds, which is 41, and the encoder rounds that to hundredths:
        // 4, so 40 milliseconds a frame and 1.92 seconds for the default 48.
        // Worth computing rather than quoting - reading 41 off the source and
        // calling it the answer is how 1.97 got written here first, and the
        // file never said 1.97.
        int wasHundredths = Math.max(2, Math.round((1000 / 24) / 10.0F));
        float wasSeconds = wasHundredths * 48 / 100.0F;
        says("the old fixed delay came out as four hundredths", wasHundredths == 4);
        says("so 48 frames turned right round in 1.92 seconds",
                within(wasSeconds, 1.92F, 0.005F));
        says("which is 187 degrees a second, over ten times the view's own 18",
                within(360.0F / wasSeconds, 187.5F, 0.5F)
                        && 360.0F / wasSeconds > 18.0F * 10);
        says("and the default is now four times slower than that",
                delay(160, 48) * 48 / 100.0F > wasSeconds * 4);

        // The delay this writes is in hundredths and the encoder is given
        // milliseconds, so it is multiplied by ten and divided by ten again.
        // An exact round trip, which is the point of choosing the coarser unit
        // here: a delay of 41 milliseconds is not a thing a GIF can hold, and
        // deciding the duration in a unit the file cannot express is how the
        // duration ends up being something nobody chose.
        says("the delay survives the trip through the encoder's units",
                Math.max(2, Math.round(delay(160, 72) * 10 / 10.0F)) == delay(160, 72));

        // A GIF delay of one or zero is read as a tenth of a second by most
        // players, so a file asking to be fast plays far slower than it asked.
        says("a delay is never written below two hundredths",
                delay(1, 120) >= 2 && delay(0, 120) >= 2);
        says("and never above what the format holds", delay(100000, 8) <= 255);

        // Fitting the texture cycle so the file loops in both its motions.
        says("a cycle that divides the duration is already whole",
                spinTicks(8.0F, 20) == 160);
        says("one that does not is rounded to the nearest whole cycle",
                spinTicks(8.0F, 30) == 150);
        says("rounded down where that is nearer, not always up",
                spinTicks(8.0F, 70) == 140);
        says("and never to nothing", spinTicks(0.5F, 7) > 0);

        // But not at any price. A cycle far longer than the duration would
        // make a thirty-second file to spare one jump, which is a worse answer
        // than the jump - the same call the detail view's cycle cap makes.
        says("a cycle too long to fit is not fitted",
                spinTicks(8.0F, 600) == 160);
        says("nor one that would more than double the duration",
                spinTicks(8.0F, 400) == 160);
        says("while one that merely stretches it is fitted",
                spinTicks(8.0F, 120) == 120 || spinTicks(8.0F, 120) == 240);

        // Somebody editing the config by hand cannot ask for nonsense.
        says("a zero duration is still a duration", spinTicks(0.0F, 1) >= 1);
        says("and an absurd one is capped", spinTicks(9000.0F, 1) <= 60 * 20);
        says("a negative one too", spinTicks(-5.0F, 1) >= 1);
    }

    /**
     * That the export's animations advance at all, which is the bug it had.
     *
     * <p>Every frame of an exported animation drew the first frame of every
     * animated texture, because the view was built with the constructor that
     * leaves the tick at zero. All the machinery was there and the detail view
     * was using it; the export simply never asked. A check on the renderer
     * would have passed throughout \u2014 the renderer was right \u2014 so what is
     * asked here is what the export requests, frame by frame.</p>
     */
    private static void whatMomentEachFrameDraws() {
        System.out.println();
        System.out.println("which moment of its animations each exported frame draws");

        int[] at = ticksFor(72, 160);
        says("there is one moment per frame", at.length == 72);
        says("the first frame is the start of every animation", at[0] == 0);

        // The bug, stated as the thing it is: a tick that never moves on.
        boolean moves = false;
        for (int tick : at) {
            if (tick != 0) {
                moves = true;
            }
        }
        says("and the rest of them are not all the start", moves);

        boolean forwards = true;
        for (int i = 1; i < at.length; i++) {
            if (at[i] < at[i - 1]) {
                forwards = false;
            }
        }
        says("time only goes forwards", forwards);
        says("and stops short of where it began, which comes round next",
                at[at.length - 1] < 160);

        // The detail view's own clock steps two ticks at a time, so an export
        // that steps no further is showing the animation at the fidelity the
        // card shows it at. Coarser than that and the export is a worse
        // recording of the thing than the thing.
        float step = 160 / 72.0F;
        says("the default samples the animation as finely as the card does",
                step <= 2.5F);
        says("which the old two-second turn could not have done",
                160 / 48.0F > step);

        // Nothing in is survived rather than thrown: these numbers come from a
        // config file somebody can edit.
        says("one frame is a list of one", ticksFor(1, 160).length == 1);
        says("no frames is still a list of one", ticksFor(0, 160).length == 1);
        says("and no ticks is the start, every time",
                ticksFor(8, 0)[7] == 0 && ticksFor(8, -5)[7] == 0);
    }

    /**
     * That a view carries its moment through every way of deriving another.
     *
     * <p>{@code withSize} did not, so resizing a view sent its animations back
     * to the beginning. Nothing calls it, which is why nobody noticed, and is
     * also why it is worth a check rather than a fix alone: the next caller
     * would have inherited the same bug the export had.</p>
     */
    private static void aViewKeepsItsMoment() {
        System.out.println();
        System.out.println("a view keeps the moment it is set to");
        var view = new dev.custommodels.cmv.explorer.render.Rasterizer.View(
                64, 64, 2, 30.0F, 10.0F, 1.0F,
                dev.custommodels.cmv.explorer.res.ModelJson.Transform.IDENTITY,
                false, 0, 37);
        says("a view is built at the moment it is given", view.tick() == 37);
        says("asked for another angle, it is still that moment",
                view.withAngle(90.0F, 0.0F).tick() == 37);
        says("asked for another size, it is still that moment",
                view.withSize(128, 128, 2).tick() == 37);
        says("and withTick is the one thing that changes it",
                view.withTick(5).tick() == 5);
        says("a still is built at the beginning of every animation",
                new dev.custommodels.cmv.explorer.render.Rasterizer.View(
                        64, 64, 2, 0, 0, 1.0F,
                        dev.custommodels.cmv.explorer.res.ModelJson.Transform.IDENTITY,
                        false, 0).tick() == 0);
    }

    /**
     * A bow drawing itself over one turn of an exported animation.
     *
     * <p>The stages arrive already in the order the game passes through them,
     * at rest first \u2014 that is what {@code LogicalModel.animationFrames} is
     * for, and it is taken from each state's own threshold rather than from its
     * name. What is settled here is only how they are spread: one pass over one
     * spin, so the frame after the last is the first again and the file loops
     * on a bow at rest rather than a bow half drawn.</p>
     */
    private static void aBowDrawingOverOneTurn() {
        System.out.println();
        System.out.println("a bow's draw stages across an exported turn");

        // Three stages, as a vanilla bow has: at rest, part drawn, fully drawn.
        java.util.List<Integer> bow = java.util.List.of(0, 4, 7);
        int[] at = statesFor(72, bow, 0);
        says("there is a stage for every frame", at.length == 72);
        says("the first frame is the item at rest", at[0] == bow.get(0));
        says("the last frame is the item fully drawn",
                at[at.length - 1] == bow.get(bow.size() - 1));
        says("so the loop goes from fully drawn back to at rest",
                at[at.length - 1] != at[0]);

        // Every stage is actually shown, and in order - a spread that skipped
        // the middle of a draw would still pass the two checks above.
        java.util.List<Integer> seen = new java.util.ArrayList<>();
        for (int state : at) {
            if (seen.isEmpty() || seen.get(seen.size() - 1) != state) {
                seen.add(state);
            }
        }
        says("every stage is shown", seen.size() == bow.size());
        says("each exactly once, never going back and forth", seen.equals(bow));
        says("and each for the same share of the turn",
                countOf(at, bow.get(0)) == 24 && countOf(at, bow.get(2)) == 24);

        // A model whose states are parts drawn together is not stepped through:
        // that would take it apart rather than animate it. animationFrames
        // returns nothing for those, and nothing is what this has to cope with.
        says("nothing to step through holds the state the card is showing",
                allOf(statesFor(72, java.util.List.of(), 3), 3));
        says("and so does a single state, which is not a sequence",
                allOf(statesFor(72, java.util.List.of(5), 3), 3));
        says("and so does none at all", allOf(statesFor(72, null, 3), 3));

        // More stages than frames must not read off the end of the list.
        int[] crowded = statesFor(4, java.util.List.of(0, 1, 2, 3, 4, 5, 6, 7), 0);
        says("more stages than frames is survived", crowded.length == 4);
        boolean inRange = true;
        for (int state : crowded) {
            if (state < 0 || state > 7) {
                inRange = false;
            }
        }
        says("and every frame still names a real stage", inRange);
        says("one frame shows the first stage",
                statesFor(1, bow, 0)[0] == bow.get(0));
    }

    public static void main(String[] args) {

        theBow();
        theCrossbow();
        theRest();
        whatGoesWhere();
        whichWay();
        wingsAndBacks();
        everyPlacement();
        whatThePackRulesOut();
        rememberingTheChoice();
        notWritingForNothing();
        somethingToPutItOn();
        whichWayItIsWorn();
        howFastAnExportTurns();
        whatMomentEachFrameDraws();
        aViewKeepsItsMoment();
        aBowDrawingOverOneTurn();

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
