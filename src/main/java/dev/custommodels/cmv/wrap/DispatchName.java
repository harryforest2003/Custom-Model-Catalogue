package dev.custommodels.cmv.wrap;

import net.minecraft.network.chat.Component;

/**
 * Builds the custom name that a name-dispatch pack will actually match.
 *
 * <h2>Why this is a class and not one line at each call site</h2>
 *
 * <p>Because getting it wrong is invisible. A pack like "Blades Of Majestica"
 * summons a model by the item's name, and the game does that by looking the
 * {@link Component} up in a map — {@code Object2ObjectOpenHashMap.get(value)} in
 * {@code SelectItemModel}. So the component written onto the item has to be
 * <em>equal</em> to the one the pack's {@code "when"} decodes to, hash code and
 * all. A plain JSON string decodes to a literal with an empty style.</p>
 *
 * <p>An earlier version wrote the name with italics explicitly turned off, on
 * the reasoning that a renamed item is drawn in italics and the pack's own
 * screenshots are not. That produced {@code literal{X}[style={!italic}]} instead
 * of {@code literal{X}}: same text, different component, different hash, no
 * match. The item was renamed and looked exactly as before, which is
 * indistinguishable from the wrap having silently failed — and sent two rounds
 * of debugging at the resource packs instead.</p>
 *
 * <p>So: no style. Not "the style we think looks best", none at all. If a future
 * change wants to restyle these names, it has to change what the pack matches
 * too, which is not possible for a pack the player did not write.</p>
 */
public final class DispatchName {

    private DispatchName() {
    }

    /** The name to write so that a pack switching on it will match. */
    public static Component of(String name) {
        return Component.literal(name);
    }
}
