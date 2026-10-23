package dev.custommodels.cmv.explorer.graph;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The category a logical model belongs to, derived from its base Minecraft item
 * rather than from what the pack author named the file.
 *
 * <p>Classification is deliberately not a hand-written table of model names.
 * A model attached to {@code minecraft:diamond_sword} is a sword whatever the
 * pack calls it, and a pack that hangs a hundred hats off
 * {@code minecraft:brown_dye} has told us those are dyes, not hats. The base
 * item is the fact; anything else is a guess.</p>
 *
 * <p>Which is not to say the guess is useless. When the base item is one of the
 * generic carriers packs habitually use — paper, dye, a carrot on a stick —
 * the item tells us nothing anyone wants to filter by, so the model path is
 * consulted as a clearly-marked fallback. {@link Classification} records which
 * of the two produced the answer.</p>
 */
public enum ItemType {

    ALL("All", "minecraft:compass"),
    SWORD("Swords", "minecraft:iron_sword"),
    AXE("Axes", "minecraft:iron_axe"),
    PICKAXE("Pickaxes", "minecraft:iron_pickaxe"),
    SHOVEL("Shovels", "minecraft:iron_shovel"),
    HOE("Hoes", "minecraft:iron_hoe"),
    BOW("Bows", "minecraft:bow"),
    CROSSBOW("Crossbows", "minecraft:crossbow"),
    SHIELD("Shields", "minecraft:shield"),
    /** Fishing rods, and the rod-shaped things packs hang off them. */
    FISHING_ROD("Fishing Rods", "minecraft:fishing_rod"),
    /** Helmets and every kind of cosmetic headwear, which packs mix freely. */
    HELMET("Hats & Helmets", "minecraft:iron_helmet"),
    ARMOUR("Armour", "minecraft:iron_chestplate"),
    TOOL("Tools", "minecraft:shears"),
    FOOD("Food", "minecraft:bread"),
    BLOCK("Blocks", "minecraft:grass_block"),
    DECORATION("Decorations", "minecraft:flower_pot"),
    DYE("Dyes", "minecraft:red_dye"),
    FEATHER("Feathers", "minecraft:feather"),
    ITEM("Items", "minecraft:stick"),
    MISC("Misc", "minecraft:bone_meal");

    private final String displayName;
    private final String iconItemId;

    ItemType(String displayName, String iconItemId) {
        this.displayName = displayName;
        this.iconItemId = iconItemId;
    }

    public String displayName() {
        return displayName;
    }

    /** Item id used to draw the filter chip's icon. */
    public String iconItemId() {
        return iconItemId;
    }

    /** How confident the classification is, and where it came from. */
    public enum Source {
        /** Read from the base item's registry tags, e.g. {@code #minecraft:swords}. */
        ITEM_TAG,
        /** Read from the base item's data components, e.g. it is edible. */
        ITEM_COMPONENT,
        /** The base item's id matched a known item exactly, e.g. {@code bow}. */
        ITEM_ID,
        /** Inferred from the model path because the base item is a generic carrier. */
        MODEL_PATH,
        /** Nothing matched. */
        NONE
    }

    /** The result of classifying one model, kept so the UI can be honest about it. */
    public record Classification(ItemType type, Source source, String evidence) {

        public boolean isInferred() {
            return source == Source.MODEL_PATH;
        }
    }

    /**
     * Base items packs use as generic carriers. A model hanging off one of these
     * has been given no meaningful type by its author, so the model path is
     * allowed to speak instead.
     */
    private static final Set<String> GENERIC_CARRIERS = Set.of(
            "minecraft:paper", "minecraft:carrot_on_a_stick",
            "minecraft:warped_fungus_on_a_stick", "minecraft:stick",
            "minecraft:flint", "minecraft:clay_ball", "minecraft:nether_star",
            "minecraft:music_disc_13", "minecraft:name_tag");

    /** Tabs, left to right. {@link #ALL} always leads. */
    public static ItemType[] tabOrder() {
        return new ItemType[]{
                ALL, SWORD, AXE, PICKAXE, SHOVEL, HOE, BOW, CROSSBOW, SHIELD,
                FISHING_ROD, HELMET, ARMOUR, TOOL, FOOD, BLOCK, DECORATION, DYE,
                FEATHER, ITEM, MISC};
    }

    /** Broad groupings offered alongside the per-type tabs. */
    public static List<ItemType> weapons() {
        return List.of(SWORD, AXE, BOW, CROSSBOW, SHIELD);
    }

    public static List<ItemType> tools() {
        return List.of(PICKAXE, SHOVEL, HOE, TOOL, AXE, FISHING_ROD);
    }

    public static List<ItemType> armour() {
        return List.of(HELMET, ARMOUR, SHIELD);
    }

    public static boolean isGenericCarrier(String baseItemId) {
        return GENERIC_CARRIERS.contains(baseItemId);
    }

    /**
     * Maps a set of item tag ids to a type. Tags are Minecraft's own answer to
     * "what kind of thing is this", which is why they are consulted first.
     *
     * @param tagIds e.g. {@code minecraft:swords}, with or without the namespace
     */
    public static ItemType fromTags(Set<String> tagIds) {
        Set<String> shortIds = new LinkedHashSet<>();
        for (String tag : tagIds) {
            int colon = tag.indexOf(':');
            shortIds.add(colon >= 0 ? tag.substring(colon + 1) : tag);
        }
        if (shortIds.contains("swords")) {
            return SWORD;
        }
        if (shortIds.contains("pickaxes")) {
            return PICKAXE;
        }
        if (shortIds.contains("axes")) {
            return AXE;
        }
        if (shortIds.contains("shovels")) {
            return SHOVEL;
        }
        if (shortIds.contains("hoes")) {
            return HOE;
        }
        if (shortIds.contains("head_armor")) {
            return HELMET;
        }
        if (shortIds.contains("chest_armor") || shortIds.contains("leg_armor")
                || shortIds.contains("foot_armor") || shortIds.contains("trimmable_armor")) {
            return ARMOUR;
        }
        // The enchantable tags are nested — the id is "enchantable/bow", not
        // "bow_enchantable". They were spelled the other way round here and so
        // never matched anything; bows and crossbows were being caught further
        // down by their item id instead, and fishing rods, which have no
        // top-level tag at all, would have fallen through to Misc.
        if (shortIds.contains("enchantable/fishing")) {
            return FISHING_ROD;
        }
        if (shortIds.contains("enchantable/bow")) {
            return BOW;
        }
        if (shortIds.contains("enchantable/crossbow")) {
            return CROSSBOW;
        }
        if (shortIds.contains("spears") || shortIds.contains("enchantable/trident")
                || shortIds.contains("enchantable/mace")) {
            return SWORD;
        }
        // Checked last of the tags: "durability" covers nearly every tool and
        // weapon in the game, so anything it would answer for is better
        // answered by one of the specific tags above.
        if (shortIds.contains("enchantable/mining")
                || shortIds.contains("enchantable/durability")) {
            return TOOL;
        }
        if (shortIds.contains("fishes") || shortIds.contains("meat")) {
            return FOOD;
        }
        return null;
    }

    /** Well-known base items that no tag covers cleanly. */
    public static ItemType fromItemId(String baseItemId) {
        int colon = baseItemId.indexOf(':');
        String path = colon >= 0 ? baseItemId.substring(colon + 1) : baseItemId;
        // Dyes and feathers get their own categories rather than being swept
        // into Misc: packs use them as carriers heavily enough that they are
        // worth navigating to, and there are sixteen dyes to fold together.
        if (path.endsWith("_dye")) {
            return DYE;
        }
        if (path.equals("feather")) {
            return FEATHER;
        }
        return switch (path) {
            case "bow" -> BOW;
            case "crossbow" -> CROSSBOW;
            case "shield" -> SHIELD;
            case "trident", "mace" -> SWORD;
            case "elytra" -> ARMOUR;
            case "fishing_rod" -> FISHING_ROD;
            case "shears", "flint_and_steel", "brush", "spyglass",
                 "compass", "clock", "bucket" -> TOOL;
            default -> null;
        };
    }
}
