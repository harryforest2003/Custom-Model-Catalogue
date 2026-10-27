package dev.custommodels.cmv.mixin;

import dev.custommodels.cmv.skin.HeadSkins;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets a worn model be drawn as its own model on the head.
 *
 * <p>This one method is the whole switch between the two ways the game can
 * draw something worn on the head — the armour texture, or the item's model —
 * and both callers read it the same frame. Answering "not armour" for one
 * stack therefore both suppresses the helmet texture and causes the item model
 * to be prepared, with no second injection to keep in step with the first.
 * See {@link HeadSkins} for the two callers and why this is the right lever.</p>
 *
 * <p>This is the only mixin in the mod, and it exists for one view. A viewer
 * that changes nothing has no business rewriting the game's rendering, so the
 * answer here is "no" for every stack in the world but the one the detail
 * card made for its own figure — which stands in no level and belongs to no
 * player.</p>
 */
@Mixin(HumanoidArmorLayer.class)
public abstract class HumanoidArmorLayerMixin {

    @Inject(method = "shouldRender(Lnet/minecraft/world/item/ItemStack;"
            + "Lnet/minecraft/world/entity/EquipmentSlot;)Z",
            at = @At("HEAD"), cancellable = true)
    private static void cmv$drawWornModelAsItem(ItemStack stack, EquipmentSlot slot,
                                                CallbackInfoReturnable<Boolean> cir) {
        if (HeadSkins.drawAsItem(stack, slot)) {
            cir.setReturnValue(false);
        }
    }
}
