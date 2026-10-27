package dev.custommodels.cmv.wrap;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Dresses a conjured stack in a wrap, for a preview and nothing else.
 *
 * <h2>Why this exists in a viewer</h2>
 *
 * <p>This mod applies nothing. It does not own a way to write a wrap onto an
 * item, and it is not getting one here: there is no slot, no inventory, no
 * packet and no undo record anywhere in this class. What it does have is one
 * view — the detail card's figure — that has to put a model into a figure's
 * hands, and the game will only render a model that some stack asks for.</p>
 *
 * <p>So the stack is made here, from nothing, given the components that make
 * it look like the model being previewed, handed to a mannequin that stands in
 * no level, and dropped when the card closes. Nothing it touches outlives the
 * screen.</p>
 *
 * <h2>Why the components and not a shortcut</h2>
 *
 * <p>Because the preview has to go wrong in the same way the real thing would.
 * The three mechanisms a pack can use to summon a model are not
 * interchangeable — a number is dispatched on per item, a name has to be equal
 * to the one the pack matches, and {@code item_model} overrides both — and a
 * figure dressed by some other means would show a model the player could not
 * actually get that way. Writing what a wrap writes is the only way the figure
 * can be trusted.</p>
 */
public final class WrapPreview {

    private WrapPreview() {
    }

    /**
     * A copy of a conjured stack carrying this appearance, or null when the
     * appearance does not fit the item.
     *
     * <p>Deliberately the same shape as applying a wrap, minus everything
     * about applying one. There is no record of what the appearance was
     * before, because this stack had no before: it was made a moment ago to be
     * looked at.</p>
     */
    public static ItemStack dressed(ItemStack stack, WrapTarget target) {
        if (stack == null || stack.isEmpty() || target == null) {
            return null;
        }
        String itemId = itemIdOf(stack);
        if (!target.isCompatibleWith(itemId)) {
            return null;
        }
        ItemStack modified = stack.copy();
        switch (target.kind()) {
            case ITEM_MODEL -> {
                Identifier id = Identifier.tryParse(target.itemModelId());
                if (id == null) {
                    return null;
                }
                modified.set(DataComponents.ITEM_MODEL, id);
            }
            case CUSTOM_NAME -> {
                if (target.customName().isEmpty()) {
                    return null;
                }
                // Styleless, and see DispatchName for why that is the
                // whole mechanism rather than a matter of taste.
                modified.set(DataComponents.CUSTOM_NAME,
                        DispatchName.of(target.customName()));
            }
            case CUSTOM_MODEL_DATA -> {
                List<WrapTarget.CustomModelDataEntry> entries = target.entriesFor(itemId);
                if (entries.isEmpty()) {
                    // The pack gives this model no number on this item, so
                    // there is no number to write. Another item's number would
                    // be accepted by the game and would draw that item's model
                    // instead, which is worse than showing nothing.
                    Identifier generated = target.itemModelId().isEmpty()
                            ? null : Identifier.tryParse(target.itemModelId());
                    if (generated == null) {
                        return null;
                    }
                    modified.set(DataComponents.ITEM_MODEL, generated);
                    return modified;
                }
                // Each value goes in the list its property actually reads, at
                // the index that property was given. The stack was conjured
                // bare, so nothing else is in either list — but the indices
                // still have to be filled up to, because a pack dispatching on
                // index 3 reads index 3 and not the first thing it finds.
                List<Float> floats = new ArrayList<>();
                List<String> strings = new ArrayList<>();
                for (WrapTarget.CustomModelDataEntry entry : entries) {
                    if (entry.numeric()) {
                        grow(floats, entry.index(), 0.0F);
                        floats.set(entry.index(), entry.number());
                    } else {
                        grow(strings, entry.index(), "");
                        strings.set(entry.index(), entry.text());
                    }
                }
                modified.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(
                        List.copyOf(floats), List.of(), List.copyOf(strings), List.of()));
            }
        }
        return modified;
    }

    private static <T> void grow(List<T> list, int index, T filler) {
        while (list.size() <= index) {
            list.add(filler);
        }
    }

    private static String itemIdOf(ItemStack stack) {
        return Optional.ofNullable(BuiltInRegistries.ITEM.getKey(stack.getItem()))
                .map(Identifier::toString)
                .orElse("");
    }
}
