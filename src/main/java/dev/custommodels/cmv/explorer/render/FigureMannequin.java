package dev.custommodels.cmv.explorer.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.ClientMannequin;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.PlayerSkinRenderCache;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * The figure the detail card shows a model on: a mannequin wearing your skin,
 * which can stand part-way through using what it holds.
 *
 * <h2>Why not the player</h2>
 *
 * <p>The obvious thing is to render {@code minecraft.player}, which is what the
 * inventory screen does, and to swap its equipment for the model while the
 * frame is extracted. That is the wrong design. A player's {@code
 * EntityEquipment} is the very object its {@link
 * net.minecraft.world.entity.player.Inventory} was constructed around — read
 * out of the 26.2 jar, where {@code Inventory(Player, EntityEquipment)} takes
 * it — so putting a previewed helmet on the player for the length of a draw
 * call is a write to the real inventory. A try/finally puts it back, and then
 * one exception anywhere inside the render leaves the player wearing something
 * the server never agreed to. A preview should not be able to lose your
 * armour.</p>
 *
 * <p>So the model goes on a mannequin instead: a separate entity, carrying no
 * inventory, that 26.2 already ships a renderer for. Nothing it wears is
 * anywhere near the player's own items.</p>
 *
 * <h2>Why the skin is taken rather than resolved</h2>
 *
 * <p>A mannequin ordinarily gets its appearance from a profile written into its
 * synched data, which starts an asynchronous lookup through the skin cache and
 * lands a frame or more later — and only if the entity is ticked, since that is
 * where {@code ClientMannequin} collects the finished lookup. None of that is
 * needed to show your own skin, because the client has already resolved it: the
 * local player is holding it. Overriding the getter borrows that answer
 * directly, which is why this entity never needs to be ticked or registered.
 * It also means the preview is right on the first frame.</p>
 *
 * <h2>Why the use pose is set here and not by a mixin</h2>
 *
 * <p>Everything the bow and crossbow animations need is already on {@code
 * LivingEntity} — {@code useItem}, {@code useItemRemaining} and {@code
 * setLivingEntityFlag} are all {@code protected} — so a subclass can write them
 * and no mixin is needed. That was worth checking rather than assuming: a mixin
 * on {@code LivingEntity} would apply to every living thing in the world to
 * serve one preview panel.</p>
 *
 * <h2>Why {@code startUsingItem} is not called</h2>
 *
 * <p>It is the obvious method and it does not work here. Read out of the 26.2
 * jar: on the client it sets {@code useItem} and {@code useItemRemaining} and
 * then stops, because the two flags that say <em>something is being used</em>
 * and <em>in which hand</em> are synched from the server and a client is not
 * supposed to invent them. On an entity the server has never heard of, nothing
 * sets them at all, so {@code getUsedItemHand()} would answer "main hand"
 * whatever the figure were doing and the pose would be wrong for an offhand
 * shield. It also fires a game-event vibration and allocates a tracking map
 * for kinetic weapons, neither of which a picture should do.</p>
 *
 * <p>So this sets the four pieces of state the renderer actually reads, and
 * nothing else. No projectile is ever loosed, no ammunition is spent, nothing
 * is written to the player's inventory and no packet is sent: a mannequin is
 * not in any level's entity lookup, and {@code releaseUsingItem} — the method
 * that would shoot — is never reached, because the figure is never ticked.</p>
 */
public final class FigureMannequin extends ClientMannequin {

    public FigureMannequin(Level level, PlayerSkinRenderCache cache) {
        super(level, cache);
    }

    /**
     * Your skin, not the mannequin's default.
     *
     * <p>Falls back to whatever the mannequin would have shown when there is no
     * player to ask, which is the case on the title screen and in a harness.</p>
     */
    @Override
    public PlayerSkin getSkin() {
        AbstractClientPlayer player = Minecraft.getInstance().player;
        return player == null ? super.getSkin() : player.getSkin();
    }

    /**
     * Puts the figure part-way through using what the given hand holds.
     *
     * <p>The stack is taken from the hand rather than passed in, and that is
     * the point of doing it here. {@code UseDuration.get} — the property every
     * pulling bow and charging crossbow model is dispatched on — returns zero
     * unless {@code entity.getUseItem()} is the <em>same object</em> as the
     * stack being drawn, compared by identity. Reading it back out of the slot
     * is what guarantees that, and getting it wrong would have shown a bow in
     * the full-draw pose holding a model that never bent.</p>
     *
     * @param hand      the hand doing it, which decides the arm the pose moves
     * @param remaining ticks left of the use, counting down as vanilla's does
     */
    public void useFor(InteractionHand hand, int remaining) {
        ItemStack holding = getItemInHand(hand);
        if (holding.isEmpty()) {
            restHands();
            return;
        }
        this.useItem = holding;
        this.useItemRemaining = Math.max(0, remaining);
        setLivingEntityFlag(1, true);
        setLivingEntityFlag(2, hand == InteractionHand.OFF_HAND);
    }

    /** Stops the figure using anything, so its arms fall back to holding. */
    public void restHands() {
        this.useItem = ItemStack.EMPTY;
        this.useItemRemaining = 0;
        setLivingEntityFlag(1, false);
        setLivingEntityFlag(2, false);
    }
}
