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
    /**
     * The item a generated appearance is put on when nothing else will carry
     * it.
     *
     * <p>Paper, which heads the list of carriers packs themselves use for a
     * model that belongs to no particular item. Nothing is being given away —
     * the stack is conjured for the figure and dropped with it — so the choice
     * only has to be something that exists and draws nothing of its own.</p>
     */
    public static final String CARRIER = "minecraft:paper";

    /**
     * Which item to put an appearance on, or empty when nothing can carry it.
     *
     * <p>The item the pack gives this model a number on, when it works that
     * way; failing that an item the pack says it fits; failing that the item
     * the definition is named after.</p>
     *
     * <p>And failing <em>that</em>, a carrier. This is the case that matters
     * for a pack whose definitions live in its own namespace: a definition at
     * {@code lf:items/count_heart_of_the_sea.json} names no registered item,
     * so the first three answers are all empty, and what is left is an
     * appearance that reaches anything with no thing to put it on. In the pack
     * this was found against that is four models in five. The target is asked
     * rather than assumed, so a wrap that genuinely cannot be written onto
     * paper still says so by returning nothing.</p>
     */
    public static String carrierFor(WrapTarget target, String baseItemId,
                                    boolean baseRegistered) {
        if (target != null && target.kind() == WrapTarget.Kind.CUSTOM_MODEL_DATA) {
            for (String item : target.itemsWithValues()) {
                return item;
            }
        }
        if (target != null && !target.compatibleItems().isEmpty()) {
            return target.compatibleItems().get(0);
        }
        if (baseRegistered) {
            return baseItemId;
        }
        return target != null && target.isCompatibleWith(CARRIER) ? CARRIER : "";
    }

    /**
     * The {@code item_model} value that draws what an item definition draws.
     *
     * <p>For a model with no wrap behind it at all. The definition is a real
     * file in the pack and {@code item_model} is the component that points at
     * one, so pointing a carrier at it draws the model without the pack having
     * offered it on anything.</p>
     *
     * <p>Note what this cannot do: a definition that switches on custom model
     * data draws its default case this way, because nothing here supplies the
     * value it switches on. One model of a set is then shown where another was
     * asked for, which is worse than correct and better than nothing.</p>
     *
     * @param definitionId e.g. {@code lf:items/count_heart_of_the_sea.json}
     * @return e.g. {@code lf:count_heart_of_the_sea}, or empty if unreadable
     */
    public static String definitionAsItemModel(String definitionId) {
        if (definitionId == null) {
            return "";
        }
        int colon = definitionId.indexOf(':');
        if (colon <= 0) {
            return "";
        }
        String namespace = definitionId.substring(0, colon);
        String path = definitionId.substring(colon + 1);
        if (path.startsWith("items/")) {
            path = path.substring("items/".length());
        }
        if (path.endsWith(".json")) {
            path = path.substring(0, path.length() - ".json".length());
        }
        return path.isEmpty() ? "" : namespace + ":" + path;
    }

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
