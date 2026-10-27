package dev.custommodels.cmv.explorer.render;

import dev.custommodels.cmv.skin.HeadSkins;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import dev.custommodels.cmv.explorer.res.ModelJson;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.Level;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/**
 * The detail card's second view: the model on you, rather than on its own.
 *
 * <h2>What this is not</h2>
 *
 * <p>It is not a second rasterizer. Everything else in this package draws
 * models by hand into an image — see {@link Rasterizer} — because a thousand
 * card thumbnails cannot each be a render of a living thing. This draws one
 * figure, at most once a frame, through the game's own entity renderer, and
 * shares nothing with that path but the panel it sits in. The two views are
 * separate systems on purpose: the item render stays exactly as it was.</p>
 *
 * <h2>Where the animation comes from</h2>
 *
 * <p>Not from here. Read out of the 26.2 client: {@code
 * AvatarRenderer.getArmPose} decides between holding an item, drawing a bow,
 * charging a crossbow and holding a charged one purely from entity state —
 * which hand is being used, how many ticks of the use are left, and whether a
 * crossbow has projectiles in it. The pulling bow and charging crossbow
 * <em>models</em> come from the same place, because {@code use_duration} and
 * {@code crossbow/pull} are properties read off the holder.</p>
 *
 * <p>So there is no animation code in this class. There is a clock, a table of
 * how long each phase lasts, and four pieces of state set on a mannequin; the
 * game then does what it does when you draw a bow. That is why the crossbow
 * loads and the bow bends, and why the two look different from each other
 * without either being described here.</p>
 */
public final class FigurePreview {

    /**
     * Ticks to a full bow draw.
     *
     * <p>Twenty, which is vanilla's: {@code bow.json} dispatches on {@code
     * use_duration} scaled by 0.05, so the last of its three models appears at
     * a value of one, which is twenty ticks of use. Picking a different number
     * here would animate the arms past the point where the bow stops
     * bending.</p>
     */
    public static final int BOW_DRAW_TICKS = 20;

    /** How long a fully drawn bow, or a loaded crossbow, is held up. */
    private static final int HELD_TICKS = 10;
    private static final int READY_TICKS = 30;

    /** The pause between loops, so the figure visibly relaxes rather than snapping. */
    private static final int REST_TICKS = 8;

    /** The longest any other use animation runs before looping. */
    private static final int USE_TICKS_CAP = 40;

    /** Milliseconds in a game tick, so the phases are in the game's own units. */
    private static final int TICK_MILLIS = 50;

    /**
     * The figure's size in the panel: from the box the inventory screen uses
     * at scale thirty.
     */
    private static final int VANILLA_BOX_WIDTH = 49;
    private static final int VANILLA_BOX_HEIGHT = 70;
    private static final int VANILLA_SCALE = 30;

    /** How far up the figure sits in its box, as the inventory screen has it. */
    private static final float Y_OFFSET = 0.0625F;

    /**
     * The slots the figure ever fills, hoisted: {@code values()} clones its
     * array, and this is read every frame.
     */
    private static final EquipmentSlot[] FILLABLE = {
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET,
            EquipmentSlot.OFFHAND,
            EquipmentSlot.MAINHAND,
    };

    /** What the figure is doing with what it holds. */
    public enum Act {
        /** Standing, holding or wearing it. Nothing is being used. */
        HOLD,
        /** Using it the way the item says it is used — eating, blocking, looking. */
        USE,
        /** Drawing it, in three stages, and letting it go. */
        BOW,
        /** Winding it, holding it loaded, and letting the load go. */
        CROSSBOW
    }

    /**
     * Where a loop has got to: the only output of the animation.
     *
     * @param using   whether the figure is using the item this instant
     * @param elapsed ticks of use so far, which is what every pulling model is
     *                dispatched on. Zero whenever {@code using} is false
     * @param charged whether the thing in its hands is loaded, which is what
     *                tells a crossbow to be held ready rather than wound
     */
    public record Beat(boolean using, int elapsed, boolean charged) {
    }

    /**
     * How a figure is turned to face the card's angle.
     *
     * @param bodyRot   the body's yaw, which is the absolute one
     * @param headYaw   the head's yaw <em>relative to the body</em>
     * @param headPitch the head's pitch, likewise its own
     */
    public record Facing(float bodyRot, float headYaw, float headPitch) {
    }

    /**
     * Turns the card's angle into a figure's rotation.
     *
     * <p>Read out of {@code LivingEntityRenderer.extractRenderState}, which is
     * the only place that says what these two fields mean:</p>
     *
     * <pre>  state.bodyRot = solveBodyRot(entity, headYaw, partialTick);
     *  state.yRot = Mth.wrapDegrees(headYaw - state.bodyRot);</pre>
     *
     * <p>So {@code yRot} is the head's yaw <em>relative to the body</em>, and
     * {@code xRot} is the head's own pitch. The inventory screen sets both
     * from the pointer, which is how its figure glances at your cursor — and
     * copying that here was wrong, because it only looks like a glance across
     * the forty degrees a mouse can reach. Given to a card that turns the
     * whole way round it became a neck: the head and the arms moved twice as
     * far as the body, and half a turn in, the body faced away while the face
     * still looked at you.</p>
     *
     * <p>The card is a turntable. A turntable turns the whole figure, so the
     * body carries all of it and the head is left in line — which also aims a
     * drawn bow straight ahead rather than off to one side, because the arm
     * poses are built from the head's angles.</p>
     */
    public static Facing facing(float yaw) {
        // The half turn is the inventory screen's, and is what has the figure
        // facing you rather than away at an angle of zero.
        return new Facing(180.0F + yaw, 0.0F, 0.0F);
    }

    private FigureMannequin figure;
    /** The level the figure was made for, so a changed world makes a new one. */
    private Level builtFor;

    private ItemStack shown = ItemStack.EMPTY;
    private EquipmentSlot slot = EquipmentSlot.MAINHAND;
    private Act act = Act.HOLD;
    /** Ticks of use the item asks for, which the countdown is measured against. */
    private int useDuration;
    /** Ticks to wind this particular crossbow, which enchantments can shorten. */
    private int chargeTicks = 25;
    /** Whether the shown stack currently has a projectile written into it. */
    private boolean loaded;

    /**
     * How far from the hand, in sixteenths of a block, a held item's transform
     * normally stays.
     *
     * <p>Six, with the real values in front of me rather than guessed at. Read
     * out of the 26.2 assets, the furthest any vanilla <em>held</em> model
     * sends a hand is four: {@code item/handheld}, which every sword, axe and
     * pickaxe inherits, translates by {@code [0, 4, 0.5]}, and {@code item/bow}
     * by {@code [-1, -2, 2.5]}. A model drawn to sit on your back has to cross
     * a torso to get there. Six leaves half again over the largest thing that
     * is genuinely being held.</p>
     *
     * <p>{@code item/shield} does reach twelve, and is the reason the slot a
     * stack is <em>worn</em> in is settled before any of this is consulted: a
     * shield belongs in the offhand because it is equippable there, which is a
     * fact, not because its transform was measured.</p>
     */
    private static final float HAND_REACH = 6.0F;

    /** Two translations within this are the same placement. */
    private static final float SAME_PLACE = 0.5F;

    /**
     * Whether a model is drawn to be worn from the offhand rather than held.
     *
     * <p>Wings, capes and backpacks are a real pattern in packs and they all
     * work the same way: the geometry is positioned by {@code
     * thirdperson_lefthand}, which puts it behind the player, and it only looks
     * right with the item in the offhand. In the hand it is a feather. So the
     * preview has to put it where the pack meant it to go, or it shows
     * something the player would never choose to look at.</p>
     *
     * <p>This is read off the model's own display transforms, which is as close
     * to asking the pack directly as there is. Three things have to hold, and
     * the first is the one that carries the weight:</p>
     *
     * <ul>
     *   <li>{@code thirdperson_lefthand} is <b>declared</b>. Minecraft mirrors
     *       the right-hand transform onto the left when it is absent, so a
     *       model without one has said nothing whatever about the offhand and
     *       guessing from a mirrored copy would be guessing from nothing.</li>
     *   <li>It reaches further than a hand. Something a torso's width from the
     *       grip is not being held.</li>
     *   <li>The right hand is not asking for more. A model that displaces both
     *       hands, but the right one further, was drawn for the right one.</li>
     * </ul>
     *
     * <p>It is an inference and it is labelled as one on the card, with a
     * control to put it back. There is no corpus of these packs to calibrate a
     * threshold against, so the honest thing is to say what was inferred and
     * from what, and to be overridable.</p>
     */
    public static boolean wantsOffhand(ModelJson.Transform left, boolean leftDeclared,
            ModelJson.Transform right, boolean rightDeclared) {
        if (!leftDeclared || left == null) {
            return false;
        }
        float reach = furthest(left);
        if (reach < HAND_REACH) {
            return false;
        }
        if (rightDeclared && right != null) {
            if (furthest(right) > reach) {
                return false;
            }
            if (samePlace(left, right)) {
                // Both hands told to do the same thing is a model that does not
                // care which hand it is in, however far it has been moved.
                return false;
            }
        }
        return true;
    }

    /** The furthest a transform moves the geometry on any one axis. */
    private static float furthest(ModelJson.Transform transform) {
        float[] moved = transform.translation();
        if (moved == null) {
            return 0.0F;
        }
        float most = 0.0F;
        for (float each : moved) {
            most = Math.max(most, Math.abs(each));
        }
        return most;
    }

    private static boolean samePlace(ModelJson.Transform one,
            ModelJson.Transform other) {
        float[] a = one.translation();
        float[] b = other.translation();
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) > SAME_PLACE) {
                return false;
            }
        }
        return true;
    }

    /**
     * Which slot an item goes in, asked of the item rather than of a list.
     *
     * <p>The {@code minecraft:equippable} component is the game's own answer to
     * "is this worn, and where", so a helmet goes on the head and a shield to
     * the offhand without either being named here — and so does anything a pack
     * or another mod makes equippable. Armour for animals and saddles name a
     * slot a player has not got, so those are held like anything else.</p>
     */
    public static EquipmentSlot slotFor(ItemStack stack) {
        return slotFor(stack, false);
    }

    /**
     * Where an item goes, given what the model said about the offhand.
     *
     * <p>What the item is worn as still wins. Something equippable has a slot
     * of its own and a display transform cannot argue with it: a helmet goes on
     * the head whatever its {@code thirdperson_lefthand} says.</p>
     */
    public static EquipmentSlot slotFor(ItemStack stack, boolean preferOffhand) {
        Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
        EquipmentSlot worn = slotFor(equippable == null ? null : equippable.slot());
        return worn == EquipmentSlot.MAINHAND && preferOffhand
                ? EquipmentSlot.OFFHAND : worn;
    }

    /**
     * The same decision with the component already read, so a harness can walk
     * every slot the game has without a loaded registry behind it.
     *
     * <p>Item components in 26.2 are bound by a datapack load rather than by
     * registering the item, so a check that wanted real stacks would have to
     * stand up most of a server to ask four questions. The part worth checking
     * is this table, and this is the table.</p>
     *
     * @param equippable the slot the item's {@code equippable} component names,
     *                   or null when it has none
     */
    public static EquipmentSlot slotFor(EquipmentSlot equippable) {
        if (equippable != null
                && (equippable.getType() == EquipmentSlot.Type.HUMANOID_ARMOR
                        || equippable == EquipmentSlot.OFFHAND)) {
            return equippable;
        }
        return EquipmentSlot.MAINHAND;
    }

    /**
     * What to do with an item, asked of the item for the same reason.
     *
     * <p>{@code getUseAnimation} is what {@code AvatarRenderer} itself reads to
     * choose an arm pose, so asking it here means a modded bow animates as a
     * bow because the game agrees it is one — not because its name was listed.
     * Something worn is never used: a helmet on the head has nothing to do.</p>
     */
    public static Act actFor(ItemStack stack, EquipmentSlot slot) {
        return actFor(stack.isEmpty() ? null : stack.getUseAnimation(), slot);
    }

    /** The same decision with the animation already read. See above. */
    public static Act actFor(ItemUseAnimation animation, EquipmentSlot slot) {
        if (animation == null || slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) {
            return Act.HOLD;
        }
        return switch (animation) {
            case BOW -> Act.BOW;
            case CROSSBOW -> Act.CROSSBOW;
            case NONE -> Act.HOLD;
            default -> Act.USE;
        };
    }

    /**
     * Where the loop has got to at a given moment.
     *
     * <p>Static and taking the clock, so that a harness can walk a whole loop
     * and say what it saw. This is the only part of the animation that can be
     * off by one, and it is the part worth proving.</p>
     *
     * @param act         what the figure is doing
     * @param millis      the clock
     * @param chargeTicks ticks to wind a crossbow, ignored by the other acts
     * @param useTicks    ticks the item's own use animation runs for, which the
     *                    {@link Act#USE} loop is held to a cap of
     */
    public static Beat beat(Act act, long millis, int chargeTicks, int useTicks) {
        int charge = Math.max(1, chargeTicks);
        int use = Math.max(1, Math.min(useTicks, USE_TICKS_CAP));
        return switch (act) {
            case HOLD -> new Beat(false, 0, false);
            case BOW -> {
                int period = BOW_DRAW_TICKS + HELD_TICKS + REST_TICKS;
                int tick = tickIn(millis, period);
                if (tick < BOW_DRAW_TICKS) {
                    yield new Beat(true, tick, false);
                }
                // Held at full draw. The pull is pinned rather than left to run
                // on, because past twenty ticks the arms would keep moving while
                // the bow had stopped bending.
                yield tick < BOW_DRAW_TICKS + HELD_TICKS
                        ? new Beat(true, BOW_DRAW_TICKS, false)
                        : new Beat(false, 0, false);
            }
            case CROSSBOW -> {
                int period = charge + READY_TICKS + REST_TICKS;
                int tick = tickIn(millis, period);
                if (tick < charge) {
                    // Winding: using it, and empty, which is the pair of
                    // conditions that both the charging pose and the pulling
                    // models are chosen by.
                    yield new Beat(true, tick, false);
                }
                // Loaded. Not using it any more, which is what turns the wind
                // into the ready stance - and a loaded crossbow draws its own
                // model, with the arrow in it.
                yield tick < charge + READY_TICKS
                        ? new Beat(false, 0, true)
                        : new Beat(false, 0, false);
            }
            case USE -> {
                int period = use + REST_TICKS;
                int tick = tickIn(millis, period);
                yield tick < use ? new Beat(true, tick, false) : new Beat(false, 0, false);
            }
        };
    }

    /** Which tick of a loop a clock reading falls on. */
    private static int tickIn(long millis, int period) {
        // floorMod rather than %, so a clock before the epoch does not index
        // backwards.
        return (int) Math.floorMod(millis / TICK_MILLIS, (long) period);
    }

    /**
     * Whether there is anything to show on a figure.
     *
     * <p>Deliberately not "is there a figure". There is no figure on the title
     * screen, and a card opened there and then joined to a world would be
     * latched on that answer for as long as it stayed open. Whether a figure
     * can be made is asked per frame, by the draw, which is where it can
     * change.</p>
     */
    public boolean hasItem() {
        return !shown.isEmpty();
    }

    /** What the figure is holding, for the screen to describe. */
    public ItemStack shown() {
        return shown;
    }

    /** Which slot it went in, for the screen to describe. */
    public EquipmentSlot slot() {
        return slot;
    }

    /**
     * Whether the item's own {@code equippable} component chose the slot.
     *
     * <p>So the card can say why. A shield and a pair of wings both end up in
     * the offhand and they get there for quite different reasons — one is worn
     * there, the other was inferred from a display transform — and only one of
     * those is worth telling the player about.</p>
     */
    public boolean slotFromItem() {
        Equippable equippable = shown.get(DataComponents.EQUIPPABLE);
        return equippable != null && equippable.slot() == slot;
    }

    /** What it is doing with it, for the screen to describe. */
    public Act act() {
        return act;
    }

    /**
     * Settles what the figure holds and what it does with it.
     *
     * <p>Everything expensive happens here rather than per frame: deciding a
     * slot reads components, and an answer that only changes when the model
     * changes has no business being worked out sixty times a second.</p>
     */
    public void show(ItemStack stack) {
        show(stack, false);
    }

    /** As {@link #show(ItemStack)}, with what the model said about the offhand. */
    public void show(ItemStack stack, boolean preferOffhand) {
        this.shown = stack == null ? ItemStack.EMPTY : stack;
        this.slot = slotFor(this.shown, preferOffhand);
        this.act = actFor(this.shown, this.slot);
        this.useDuration = 0;
        this.chargeTicks = 25;
        this.loaded = false;
        if (!this.shown.isEmpty()) {
            figure();
            if (figure != null) {
                // Both of these ask the item about the holder, so they are
                // asked once the figure exists and not before.
                this.useDuration = this.shown.getUseDuration(figure);
                if (this.act == Act.CROSSBOW) {
                    this.chargeTicks = CrossbowItem.getChargeDuration(this.shown, figure);
                }
            }
        }
    }

    /**
     * Draws the figure at the given angle.
     *
     * <p>The render state is built the way the inventory screen builds it —
     * renderer, {@code createRenderState}, shadow cleared — and then given the
     * panel's own angle instead of one worked out from the pointer. That is the
     * whole reason this does not simply call {@code
     * extractEntityInInventoryFollowsMouse}: that helper writes the figure's
     * rotation from the mouse position, over a span of about forty degrees, and
     * the detail card turns all the way round.</p>
     *
     * @param yaw   degrees round, continuing the card's own spin
     * @param pitch degrees of tilt, as the card's drag sets it
     * @param zoom  the card's zoom, applied to the figure's scale
     */
    public void draw(GuiGraphicsExtractor graphics, int x1, int y1, int x2, int y2,
            float yaw, float pitch, float zoom) {
        figure();
        FigureMannequin standing = this.figure;
        if (standing == null || shown.isEmpty()) {
            return;
        }

        if (act != Act.HOLD && useDuration <= 0) {
            // Both of these ask the item about its holder, and on the title
            // screen there was no holder to ask about. Asked again now there
            // is one, or the loop would run against a duration of nothing and
            // the figure would stand there holding a bow it never drew.
            //
            // Only for something that is used. Anything merely held has a use
            // duration of nothing by right, and without this guard it would be
            // asked again on every frame for the rest of the screen's life.
            useDuration = shown.getUseDuration(standing);
            if (act == Act.CROSSBOW) {
                chargeTicks = CrossbowItem.getChargeDuration(shown, standing);
            }
        }

        dress(standing);

        Minecraft client = Minecraft.getInstance();
        EntityRenderDispatcher dispatcher = client.getEntityRenderDispatcher();
        // var, because the type matters: getRenderer gives back an
        // EntityRenderer<? super FigureMannequin, ?>, and writing it down as
        // EntityRenderer<?, ?> throws away the bound that lets the figure be
        // passed to its own renderer.
        var renderer = dispatcher.getRenderer(standing);
        if (renderer == null) {
            return;
        }
        EntityRenderState state = renderer.createRenderState(standing, 1.0F);
        // No shadow and no outline: both belong to a figure standing in a world.
        state.shadowPieces.clear();
        state.outlineColor = 0;

        if (state instanceof LivingEntityRenderState living) {
            Facing facing = facing(yaw);
            living.bodyRot = facing.bodyRot();
            living.yRot = facing.headYaw();
            living.xRot = facing.headPitch();
            // The box is in the entity's own scale and the draw expects it in
            // ones. The inventory screen does exactly this.
            living.boundingBoxWidth /= living.scale;
            living.boundingBoxHeight /= living.scale;
            living.scale = 1.0F;
        }

        Quaternionf tilt = new Quaternionf().rotateX(-pitch * ((float) Math.PI / 180.0F));
        Quaternionf camera = new Quaternionf().rotateZ((float) Math.PI).mul(tilt);
        int scale = Math.max(8, (int) (Math.min(
                VANILLA_SCALE * (x2 - x1) / VANILLA_BOX_WIDTH,
                VANILLA_SCALE * (y2 - y1) / VANILLA_BOX_HEIGHT) * zoom));
        Vector3f where = new Vector3f(0.0F, state.boundingBoxHeight / 2.0F + Y_OFFSET, 0.0F);
        graphics.entity(state, scale, where, camera, tilt, x1, y1, x2, y2);
    }

    /**
     * Hangs the item on the figure and moves the loop on a step.
     *
     * <p>Safe in the way the same thing done to the player would not be: these
     * slots belong to an entity with no inventory behind them. See {@link
     * FigureMannequin} for why that matters.</p>
     */
    private void dress(FigureMannequin standing) {
        for (EquipmentSlot each : FILLABLE) {
            standing.setItemSlot(each, each == slot ? shown : ItemStack.EMPTY);
        }
        // A worn helmet is drawn from its armour texture rather than its model
        // unless something says otherwise, which would hide the very thing the
        // card is showing.
        HeadSkins.standIn(standing);

        Beat beat = beat(act, System.currentTimeMillis(), chargeTicks, useDuration);
        if (act == Act.CROSSBOW && beat.charged() != loaded) {
            // Written to the same stack rather than a copy: the use properties
            // compare the held stack against the used one by identity.
            //
            // And written only when it changes. Doing it every frame allocated
            // an arrow, a list and a component sixty times a second to say what
            // it had already said, and rewrote the stack's component patch each
            // time - which is a third of the work of this whole view for an
            // answer that changes twice a loop.
            shown.set(DataComponents.CHARGED_PROJECTILES, beat.charged()
                    ? ChargedProjectiles.ofNonEmpty(List.of(new ItemStack(Items.ARROW)))
                    : ChargedProjectiles.EMPTY);
            loaded = beat.charged();
        }
        if (beat.using() && slot.getType() == EquipmentSlot.Type.HAND) {
            InteractionHand hand = slot == EquipmentSlot.OFFHAND
                    ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
            standing.useFor(hand, useDuration - beat.elapsed());
        } else {
            standing.restHands();
        }
    }

    /** Lets go of the figure, and of standing in for the player's own head. */
    public void close() {
        if (figure != null) {
            HeadSkins.standDown(figure);
        }
        figure = null;
        builtFor = null;
    }

    /**
     * The figure, made once per world.
     *
     * <p>Positioned on the player and given the player's own entity id: a
     * renderer asks where a figure stands, and {@code ItemModelResolver} seeds
     * a held item's model with the holder's id — which throws on an entity
     * that was never added to a level.</p>
     */
    private void figure() {
        Minecraft client = Minecraft.getInstance();
        Level level = client.level;
        LocalPlayer player = client.player;
        if (level == null || player == null) {
            close();
            return;
        }
        if (figure == null || builtFor != level) {
            figure = new FigureMannequin(level, client.playerSkinRenderCache());
            builtFor = level;
        }
        figure.setId(player.getId());
        figure.snapTo(player.getX(), player.getY(), player.getZ(), 0.0F, 0.0F);
    }
}
