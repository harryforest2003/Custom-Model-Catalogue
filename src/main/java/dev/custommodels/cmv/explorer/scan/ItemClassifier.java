package dev.custommodels.cmv.explorer.scan;

import dev.custommodels.cmv.compat.McCompat;
import dev.custommodels.cmv.explorer.graph.ItemType;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps a base item to a type using the game's own registry data.
 *
 * <p>Item tags are Minecraft's own classification and are tried first; failing
 * that, components and then a short list of items no tag covers. Kept in the
 * scan package rather than in {@code graph} so that the graph stays free of
 * Minecraft imports and testable outside the game.</p>
 *
 * <p>Used by the scan to classify the item a model sits on, and by the wrap
 * catalogue to ask what the item in the player's hand is — which is a
 * different question from what a model is, and has to be answered by the
 * registry rather than inferred from the models that happen to mention it.</p>
 */
public final class ItemClassifier {

    /**
     * One answer per item, not one per model that carries it.
     *
     * <p>A {@link ConcurrentHashMap} rather than a plain one because nothing in
     * the pipeline promises to stay on one thread forever, and the cost of that
     * promise being broken quietly is a corrupted map rather than a slow
     * scan.</p>
     */
    private final Map<String, ItemType.Classification> known = new ConcurrentHashMap<>();

    public ItemType.Classification classify(String baseItemId) {
        return known.computeIfAbsent(baseItemId, ItemClassifier::work);
    }

    /** Just the type, for callers that do not care where it came from. */
    public ItemType typeOf(String baseItemId) {
        return classify(baseItemId).type();
    }

    private static ItemType.Classification work(String baseItemId) {
        Set<String> tags = McCompat.tagsOf(baseItemId);
        if (!tags.isEmpty()) {
            ItemType fromTags = ItemType.fromTags(tags);
            if (fromTags != null) {
                return new ItemType.Classification(fromTags,
                        ItemType.Source.ITEM_TAG, firstRelevantTag(tags));
            }
        }
        ItemType fromId = ItemType.fromItemId(baseItemId);
        if (fromId != null) {
            return new ItemType.Classification(fromId, ItemType.Source.ITEM_ID, baseItemId);
        }
        ItemType fromComponents = McCompat.typeFromComponents(baseItemId);
        if (fromComponents != null) {
            return new ItemType.Classification(fromComponents,
                    ItemType.Source.ITEM_COMPONENT, baseItemId);
        }
        return new ItemType.Classification(ItemType.MISC, ItemType.Source.NONE, baseItemId);
    }

    private static String firstRelevantTag(Set<String> tags) {
        for (String tag : tags) {
            if (ItemType.fromTags(Set.of(tag)) != null) {
                return "#" + tag;
            }
        }
        return "";
    }
}
