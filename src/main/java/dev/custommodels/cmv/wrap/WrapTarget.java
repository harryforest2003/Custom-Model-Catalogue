package dev.custommodels.cmv.wrap;

import dev.custommodels.cmv.explorer.graph.ItemType;
import dev.custommodels.cmv.explorer.graph.LogicalModel;
import dev.custommodels.cmv.explorer.graph.ResolvedName;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One appearance that can be put on an item.
 *
 * <p>A wrap is not a new item and does not carry one. It is a note saying
 * "these components make Minecraft draw this model", plus the evidence for
 * which items that is true of.</p>
 *
 * @param id              stable identity, the underlying model's canonical id
 * @param name            the display name and where it came from
 * @param type            the item type it was classified as
 * @param kind            which component carries the appearance
 * @param itemModelId     for {@link Kind#ITEM_MODEL}, the value to write
 * @param customModelData for {@link Kind#CUSTOM_MODEL_DATA}, the entries the
 *                        model's own base item needs, each knowing which list
 *                        and which index it belongs in; representative only,
 *                        for display — {@link #entriesFor} is what to write
 * @param customModelDataByItem the entries per item, because the value that
 *                        reaches one model is the pack's choice per item and
 *                        is routinely different on each
 * @param compatibleItems registered items the pack associates this model with;
 *                        empty means the pack does not say, which for an
 *                        {@code item_model} wrap means any item will render it
 * @param model           the logical model, for previews and the detail panel
 */
public record WrapTarget(String id,
                         ResolvedName name,
                         ItemType type,
                         Kind kind,
                         String itemModelId,
                         String customName,
                         List<CustomModelDataEntry> customModelData,
                         Map<String, List<CustomModelDataEntry>> customModelDataByItem,
                         List<String> compatibleItems,
                         LogicalModel model) {

    /**
     * One value to write into the {@code custom_model_data} component.
     *
     * <p>Which list it goes in is not a detail. Confirmed by reading the 26.2
     * jar: a {@code range_dispatch} on custom model data reads
     * {@code floats.get(index)} and a {@code select} reads
     * {@code strings.get(index)}. Writing a numeric threshold into the string
     * list, which is the obvious mistake, produces a wrap that applies cleanly
     * and changes nothing at all — the pack examined during development is
     * almost entirely numeric, so this distinction decides whether the feature
     * works on it.</p>
     *
     * @param numeric true to write into the float list, false for strings
     * @param index   position within that list
     * @param number  the value when numeric
     * @param text    the value when not
     */
    public record CustomModelDataEntry(boolean numeric, int index, float number, String text) {

        public String display() {
            return numeric
                    ? "floats[" + index + "] = " + trim(number)
                    : "strings[" + index + "] = \"" + text + "\"";
        }

        private static String trim(float value) {
            return value == (long) value
                    ? String.valueOf((long) value) : String.valueOf(value);
        }
    }

    /**
     * How Minecraft is told to draw the model.
     *
     * <p>Both are the game's own mechanisms, so a wrapped item behaves exactly
     * as the pack author intended, including any state dispatch: a wrapped bow
     * still moves through its pull models when drawn, because nothing here
     * pins a state — it points at the definition and lets the game resolve it,
     * frame by frame, as it always would.</p>
     */
    public enum Kind {
        /**
         * {@code minecraft:item_model}, pointing at an item definition id. This
         * is how a pack's own namespace is reached, and how the server-side
         * packs examined during development do it.
         */
        ITEM_MODEL("Item model"),

        /**
         * {@code minecraft:custom_model_data}, carrying the string a
         * {@code select} in the base item's own definition switches on.
         */
        CUSTOM_MODEL_DATA("Custom model data"),

        /**
         * {@code minecraft:custom_name}, carrying the exact name a
         * {@code select} on that component switches on.
         *
         * <p>A whole class of packs works this way: the item definition reads
         * {@code minecraft:component} for {@code custom_name}, so renaming a
         * sword to "Abominable Blade" is what summons the model. Applying one
         * means writing that name, which is visible in a way the other two
         * mechanisms are not — the item really is called that afterwards,
         * because that is what makes it look right.</p>
         */
        CUSTOM_NAME("Item name");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public String displayName() {
        return name.value();
    }

    /** True when the pack does not restrict this model to particular items. */
    public boolean isUniversal() {
        return compatibleItems.isEmpty();
    }

    /**
     * Whether this wrap may be applied to an item.
     *
     * <p>A custom model data wrap is only meaningful on an item whose own
     * definition contains the matching case — putting the string on anything
     * else changes nothing visible. An item model wrap is not restricted by the
     * game at all, so the honest answer for one the pack says nothing about is
     * yes, with the caveat surfaced in {@link #compatibilityReason}.</p>
     */
    public boolean isCompatibleWith(String itemId) {
        if (kind == Kind.CUSTOM_MODEL_DATA) {
            // Being listed is not enough: the pack has to give this model a
            // number on this item, and only that number may be written.
            if (!entriesFor(itemId).isEmpty()) {
                return true;
            }
            // Unless the mod has written a definition of its own for this
            // appearance, which item_model reaches whatever it is put on. That
            // is the whole point of generating one: a model the pack offers
            // only on a sheet of paper is otherwise unusable.
            return !itemModelId.isEmpty();
        }
        if (compatibleItems.contains(itemId)) {
            return true;
        }
        return kind == Kind.ITEM_MODEL && compatibleItems.isEmpty();
    }

    /** Why the answer above is what it is, for the debug panel. */
    public String compatibilityReason(String itemId) {
        if (kind == Kind.CUSTOM_MODEL_DATA && !entriesFor(itemId).isEmpty()) {
            return "The pack's " + itemId + " definition reaches this model at "
                    + componentSummary(itemId);
        }
        if (kind == Kind.CUSTOM_MODEL_DATA && !itemModelId.isEmpty()) {
            return "The pack does not offer this on " + itemId
                    + ", so item_model points at the definition the mod generated"
                    + " for it instead";
        }
        if (kind == Kind.CUSTOM_MODEL_DATA && compatibleItems.contains(itemId)) {
            return "The pack's " + itemId + " definition mentions this model but "
                    + "gives it no custom model data value, so there is nothing to write";
        }
        if (compatibleItems.contains(itemId)) {
            return "The pack's " + itemId + " item definition references this model";
        }
        if (kind == Kind.ITEM_MODEL && compatibleItems.isEmpty()) {
            return "item_model applies to any item; the pack does not say which "
                    + "item this model belongs to";
        }
        if (compatibleItems.isEmpty()) {
            return "No item definition references this model";
        }
        return "Referenced only by " + String.join(", ", compatibleItems);
    }

    /**
     * The custom model data that reaches this model <em>on this item</em>.
     *
     * <p>Not a detail, and not the same list for every item. A pack numbers its
     * models per item definition, so one appearance is commonly a different
     * number on each item it is offered on — in the packs this was built
     * against, the Poseidon pickaxe is 473 on a netherite pickaxe and 596 on a
     * diamond one, while 473 on the diamond pickaxe is the Poseidon crown.
     * Writing one item's number onto another therefore does not fail: it
     * silently summons whatever the pack put at that number instead, which is
     * how applying a pickaxe skin produced a crown.</p>
     *
     * <p>Empty when the pack does not offer this model on that item. That is a
     * refusal to write, not a reason to fall back on another item's number —
     * the fallback is exactly the bug.</p>
     */
    public List<CustomModelDataEntry> entriesFor(String itemId) {
        List<CustomModelDataEntry> own = customModelDataByItem.get(itemId);
        return own == null ? List.of() : own;
    }

    /** Every item this wrap knows a custom model data value for. */
    public java.util.Set<String> itemsWithValues() {
        return customModelDataByItem.keySet();
    }

    /** A short line describing what will be written, for the confirmation. */
    public String componentSummary() {
        return componentSummary("");
    }

    /**
     * The same, for one item.
     *
     * <p>Which matters now that the value is per item: a summary showing the
     * number some other item uses would be describing a write that is not the
     * one about to happen.</p>
     */
    public String componentSummary(String itemId) {
        if (kind == Kind.ITEM_MODEL) {
            return "minecraft:item_model = " + itemModelId;
        }
        if (kind == Kind.CUSTOM_NAME) {
            return "minecraft:custom_name = \"" + customName + "\"";
        }
        List<CustomModelDataEntry> entries = itemId.isEmpty()
                ? customModelData : entriesFor(itemId);
        if (entries.isEmpty()) {
            return itemId.isEmpty()
                    ? "minecraft:custom_model_data"
                    : "This pack does not offer this model on a " + itemId;
        }
        List<String> parts = new java.util.ArrayList<>(entries.size());
        for (CustomModelDataEntry entry : entries) {
            parts.add(entry.display());
        }
        return "minecraft:custom_model_data  " + String.join(", ", parts);
    }

    /** Lower-cased searchable text, built once by the catalogue. */
    public String searchBlob() {
        StringBuilder builder = new StringBuilder(160);
        append(builder, name.value());
        append(builder, type.displayName());
        append(builder, itemModelId);
        append(builder, model.defaultState().modelId());
        append(builder, model.namespace());
        for (List<CustomModelDataEntry> entries : customModelDataByItem.values()) {
            for (CustomModelDataEntry entry : entries) {
                append(builder, entry.numeric()
                        ? String.valueOf(entry.number()) : entry.text());
            }
        }
        for (CustomModelDataEntry entry : customModelData) {
            append(builder, entry.numeric()
                    ? String.valueOf(entry.number()) : entry.text());
        }
        for (String item : compatibleItems) {
            append(builder, item);
        }
        return builder.toString();
    }

    private static void append(StringBuilder builder, String value) {
        if (value != null && !value.isEmpty()) {
            builder.append(value.toLowerCase(Locale.ROOT)).append(' ');
        }
    }
}
