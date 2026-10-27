package dev.custommodels.cmv.skin;

import dev.custommodels.cmv.core.CmvConfig;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * Shows a worn model as its model, on the head, rather than as armour.
 *
 * <h2>The problem</h2>
 *
 * <p>A model meant to be worn is invisible in the one view whose job is to
 * show you wearing it. The detail card's figure puts a helmet on its head, and
 * the model disappears: the ordinary armour texture is drawn instead. That is
 * not a fault in the pack — it is how Minecraft draws armour, and these models
 * were drawn to be worn.</p>
 *
 * <h2>Why it happens, exactly</h2>
 *
 * <p>Read out of the 26.2 client rather than guessed at. One method decides
 * it — {@code HumanoidArmorLayer.shouldRender(stack, slot)}, which is true
 * when the stack's {@code minecraft:equippable} component names an armour
 * asset for that slot — and two places ask it about the head:</p>
 *
 * <ul>
 *   <li>{@code LivingEntityRenderer.extractRenderState} populates the render
 *       state's {@code headItem} — the item model drawn on the head — only
 *       when the answer is <em>no</em>. A helmet answers yes, so no item model
 *       is ever prepared for it.</li>
 *   <li>{@code HumanoidMobRenderer.getEquipmentIfRenderable} records the head
 *       stack for the armour layer only when the answer is <em>yes</em>, which
 *       is what puts the helmet texture on the figure.</li>
 * </ul>
 *
 * <p>So the two are already exclusive, and a single answer decides which of
 * them happens. Saying "not armour" for one particular stack gives that stack
 * the treatment a carved pumpkin gets: its own model, on the head, at the size
 * and position the model's {@code head} display transform asks for — which the
 * packs already provide.</p>
 *
 * <h2>What this does not do</h2>
 *
 * <p>It never touches an item you own. This mod applies nothing, and the only
 * stack that can ever qualify here is the one the detail card conjured for its
 * own figure: a mannequin that stands in no level, built when the view opens
 * and dropped when it closes. The comparison is by identity against that one
 * figure's head, so no helmet in the world — yours, another player's, or an
 * armour stand's — can match it, even when it is the very same item with the
 * very same components.</p>
 *
 * <p>That is also why there is no second rule for the player's own head, as
 * there would be in a mod that puts models onto real items. There is nothing
 * to detect: nothing here ever wrote one.</p>
 */
public final class HeadSkins {

    private HeadSkins() {
    }

    /**
     * The figure whose head is to be drawn from its model.
     *
     * <p>Set by a preview while it has a figure, and cleared with it. Null the
     * rest of the time, which is every frame of ordinary play.</p>
     */
    private static LivingEntity standIn;

    public static void standIn(LivingEntity figure) {
        standIn = figure;
    }

    /**
     * Gives up the stand-in, but only if it is still this figure.
     *
     * <p>There is one slot and more than one view may want it. A card closing
     * with an unconditional clear would leave anything underneath showing the
     * plain armour texture until something else moved — the exact fault the
     * stand-in was added to fix. Closing politely costs one comparison.</p>
     */
    public static void standDown(LivingEntity figure) {
        if (standIn == figure) {
            standIn = null;
        }
    }

    /**
     * Whether this stack should be drawn as its own model on the head.
     *
     * <p>Called for every armour slot of every entity on screen, every frame,
     * so it is ordered to leave immediately for all but one stack: the slot,
     * then the stand-in, which is absent unless a figure view is open, and
     * only then the config. Nothing is looked up and nothing is copied.</p>
     */
    public static boolean drawAsItem(ItemStack stack, EquipmentSlot slot) {
        if (slot != EquipmentSlot.HEAD || stack == null || stack.isEmpty()) {
            return false;
        }
        if (standIn == null || standIn.getItemBySlot(EquipmentSlot.HEAD) != stack) {
            return false;
        }
        return CmvConfig.get().headSkins;
    }
}
