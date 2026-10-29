package dev.custommodels.cmv.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.custommodels.cmv.compat.McCompat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a server says its own items are called, kept as a document of our own.
 *
 * <h2>Why a document rather than a lookup</h2>
 *
 * <p>A pack can say what a model looks like and cannot say what the server
 * calls it. Only the server knows that, and some servers publish it. But a
 * mod that asked the server every time it needed a name would stop working the
 * day that service was turned off, taken down, rate-limited or simply renamed -
 * and the names would go with it, including for anybody playing offline or
 * reading their own pack with the server a thousand miles away.</p>
 *
 * <p>So nothing is ever looked up over the network. The network writes this
 * file and this file answers every question. Fetching is a thing you do when
 * you want newer information, the way you update anything else; if it never
 * happens again, the names already here go on working for as long as the file
 * exists. The service is for updates, not for running.</p>
 *
 * <h2>What is in it</h2>
 *
 * <p>Keyed by the item and the custom model data value together, because that
 * pair is what a pack actually dispatches on and so is the one thing a server's
 * catalogue and a resource pack can both be made to agree about:</p>
 *
 * <pre>{@code
 * {
 *   "source": "https://example.invalid",
 *   "updated": "2026-10-28T11:04:19Z",
 *   "items": {
 *     "minecraft:diamond_sword#13": {
 *       "name": "Malachite Sword", "crate": "Season 13",
 *       "set": "Malachite", "rarity": "Rare",
 *       "colours": "&a&#55FF55&#4FF94F",
 *       "parts": [{"text": "\u2646 ", "colour": "green"},
 *                 {"text": "Malachite", "colour": "#55FF55"}]
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p>Plain, sorted and readable, so it can be edited by hand, kept in a backup,
 * copied between instances, or written by something that is not this mod at
 * all. A server that publishes nothing can still be described by a file someone
 * wrote themselves.</p>
 *
 * <h2>What it never does</h2>
 *
 * <p>No name here replaces anything the pack itself states. It is offered as
 * one more source of a name, the highest-confidence one, and everything the
 * explorer already infers stays exactly as it was underneath - see {@link
 * dev.custommodels.cmv.explorer.graph.ResolvedName}. Delete this file and the
 * mod is what it was before.</p>
 */
public final class ItemFacts {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * One server's word about one item. Any field may be empty.
     *
     * <p>Deliberately four things and not everything a catalogue publishes.
     * This mod is about how an item looks, so what the server calls it, which
     * set it belongs to, how rare it is and which crate it came out of all say
     * something about the appearance. What the set does when worn does not, so
     * it is not collected and not stored.</p>
     */
    /** One run of the name, and the colour the server writes it in. */
    public record Part(String text, String colour) {
    }

    public record Fact(String name, String crate, String set, String rarity,
                       String colours, java.util.List<Part> parts) {

        public boolean isEmpty() {
            return blank(name) && blank(crate) && blank(set) && blank(rarity);
        }

        /**
         * The colours this name is written in, as codes and nothing else.
         *
         * <p>Each one once, in the order it first appears: {@code &a&#55FF55},
         * never the words. The point is to put the colours on something else,
         * so the words would be in the way. A gradient gives back every step of
         * itself, because that is what the name is written in.</p>
         *
         * <p>Collected while the catalogue was read rather than recovered from
         * the name afterwards. That is not a detail: these names carry literal
         * {@code &} characters in their own text - a gradient of theirs reads
         * {@code "text": "M&"} - so a string of codes and words together cannot
         * be taken apart again with any confidence. Reading the colours where
         * they are unambiguous and keeping only those is the whole fix.</p>
         */
        public String colourCodes() {
            return colours == null ? "" : colours;
        }

        /**
         * The name as the server draws it, colours and all.
         *
         * <p>Built from the parts rather than from a string of codes, because
         * a string of codes cannot be taken apart again where the words
         * themselves contain the marker - and these do. Each part says its own
         * colour, so there is nothing to parse and nothing to get wrong.</p>
         *
         * <p>Falls back to the plain name, uncoloured, when a catalogue gave
         * the name but not how it is written.</p>
         */
        public net.minecraft.network.chat.Component coloured() {
            if (parts == null || parts.isEmpty()) {
                return net.minecraft.network.chat.Component.literal(
                        name == null ? "" : name);
            }
            net.minecraft.network.chat.MutableComponent out =
                    net.minecraft.network.chat.Component.empty();
            for (Part part : parts) {
                if (part == null || part.text() == null || part.text().isEmpty()) {
                    continue;
                }
                net.minecraft.network.chat.MutableComponent run =
                        net.minecraft.network.chat.Component.literal(part.text());
                Integer rgb = rgbOf(part.colour());
                // Italic off explicitly: a renamed item is drawn italic by the
                // game, and the server's own name is not.
                out.append(rgb == null
                        ? run.withStyle(style -> style.withItalic(false))
                        : run.withStyle(style -> style.withColor(rgb).withItalic(false)));
            }
            return out;
        }

        /**
         * A colour name or hex string as a number, or null for neither.
         *
         * <p>Through the game's own codec, which is what reads these out of a
         * text component in the first place, so {@code green} and {@code
         * #55FF55} mean here exactly what they mean there. 26.2's {@code
         * ChatFormatting} no longer offers a name lookup or a colour value, so
         * a table written here would be a second opinion about something the
         * game already has one of.</p>
         */
        private static Integer rgbOf(String colour) {
            if (colour == null || colour.isEmpty()) {
                return null;
            }
            return net.minecraft.network.chat.TextColor.CODEC
                    .parse(com.mojang.serialization.JsonOps.INSTANCE,
                            new com.google.gson.JsonPrimitive(colour))
                    .result()
                    .map(net.minecraft.network.chat.TextColor::getValue)
                    .orElse(null);
        }

        private static boolean blank(String value) {
            return value == null || value.isEmpty();
        }
    }

    /**
     * What a server said one of its sets contains.
     *
     * <p>Kept so a set can be rebuilt here without asking anything, and so the
     * question "is this the whole set" can be answered honestly. A set is only
     * worth making when every piece of it is accounted for; a four-piece armour
     * set that arrives as three is not that set, and silently making it would
     * be putting a wrong answer in front of somebody.</p>
     *
     * @param expected how many members the catalogue named
     * @param members  how many of those produced an item and model-data pair,
     *                 which is the most the catalogue itself can account for
     * @param keys     those pairs, which is what a model is found by later
     */
    public record SetFacts(int expected, int members, java.util.List<String> keys) {

        /** Whether the catalogue accounted for every piece it named. */
        public boolean whole() {
            return expected > 0 && members == expected;
        }
    }

    /** The document as it sits on disk. */
    public record Document(String source, String updated, Map<String, Fact> items,
                           Map<String, SetFacts> sets) {
    }

    private static Map<String, Fact> items;
    private static Map<String, SetFacts> sets = new TreeMap<>();
    private static String source = "";
    private static String updated = "";

    private ItemFacts() {
    }

    /**
     * Where the document lives, or null when there is nowhere yet.
     *
     * <p>There is not always a config directory to ask about: a harness has
     * none, and neither has the client before it has finished starting. Either
     * is a reason to have no facts, not a reason to throw - this is read from
     * the catalogue build, which is somewhere a null would take the whole
     * explorer down with it.</p>
     */
    private static Path file() {
        try {
            Path dir = McCompat.configDir();
            return dir == null ? null : dir.resolve("item-facts.json");
        } catch (RuntimeException noDirectory) {
            // Asking the loader where its config lives is itself a thing that
            // fails when there is no loader, which is the case in a harness and
            // briefly at startup. Catching it here is what lets everything else
            // treat "no document" as an ordinary answer.
            return null;
        }
    }

    /**
     * The key a server catalogue and a resource pack can both be joined on.
     *
     * <p>The value is written as a plain integer where it is one, because that
     * is how a person writing this file by hand would write it, and the pack
     * side of the join is a threshold that is nearly always whole.</p>
     */
    public static String key(String item, float customModelData) {
        String number = customModelData == Math.rint(customModelData)
                ? String.valueOf((long) customModelData)
                : String.valueOf(customModelData);
        return item + "#" + number;
    }

    private static Map<String, Fact> items() {
        if (items == null) {
            items = read();
        }
        return items;
    }

    private static Map<String, Fact> read() {
        Map<String, Fact> found = new TreeMap<>();
        Path path = file();
        if (path == null || !Files.exists(path)) {
            return found;
        }
        try {
            Document document = GSON.fromJson(Files.readString(path),
                    new TypeToken<Document>() { }.getType());
            if (document != null) {
                source = document.source() == null ? "" : document.source();
                updated = document.updated() == null ? "" : document.updated();
                found.putAll(itemsIn(document));
                Map<String, SetFacts> read = new TreeMap<>();
                if (document.sets() != null) {
                    document.sets().forEach((k, v) -> {
                        if (k != null && v != null && v.keys() != null) {
                            read.put(k, v);
                        }
                    });
                }
                sets = read;
            }
        } catch (IOException | RuntimeException e) {
            // Its own file, so a broken one costs the names and nothing else.
            CmvMod.LOGGER.warn("Could not read the item facts", e);
        }
        return found;
    }

    /** What the server calls this item at this value, or null for nothing known. */
    public static Fact get(String item, float customModelData) {
        if (item == null || item.isEmpty()) {
            return null;
        }
        return items().get(key(item, customModelData));
    }

    /** What each set contains, by set name. */
    public static Map<String, SetFacts> sets() {
        items();
        return java.util.Collections.unmodifiableMap(sets);
    }

    /** Replaces the document wholesale, which is what a fetch produces. */
    public static void replace(String from, Map<String, Fact> facts,
                               Map<String, SetFacts> setFacts) {
        Map<String, Fact> kept = new TreeMap<>();
        if (facts != null) {
            facts.forEach((k, v) -> {
                if (k != null && v != null && !v.isEmpty()) {
                    kept.put(k, tidied(v));
                }
            });
        }
        items = kept;
        sets = setFacts == null ? new TreeMap<>() : new TreeMap<>(setFacts);
        source = from == null ? "" : from;
        updated = java.time.Instant.now().toString();
        write();
    }

    private static void write() {
        Path path = file();
        if (path == null) {
            return;
        }
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(
                    new Document(source, updated, items(), sets)));
        } catch (IOException e) {
            CmvMod.LOGGER.warn("Could not save the item facts", e);
        }
    }

    /**
     * What the server says about the item somebody is holding.
     *
     * <p>Reads the stack's own item and {@code custom_model_data}, which is
     * the same pair the catalogue was keyed on, and tries each value the
     * component carries because a pack may dispatch on any of them.</p>
     *
     * <p>Null when nothing is known, which is the common case and the cheap
     * one: an item with no such component never reaches the map at all.</p>
     */
    public static Fact forStack(net.minecraft.world.item.ItemStack stack) {
        if (stack == null || stack.isEmpty() || items().isEmpty()) {
            return null;
        }
        net.minecraft.world.item.component.CustomModelData data =
                stack.get(net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA);
        if (data == null || data.floats().isEmpty()) {
            return null;
        }
        String item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem()).toString();
        for (Float value : data.floats()) {
            if (value == null) {
                continue;
            }
            Fact fact = get(item, value);
            if (fact != null) {
                return fact;
            }
        }
        return null;
    }

    /**
     * What the server says about a model, by its item and its values.
     *
     * <p>The values arrive as written by the grouping, which is {@code
     * String.valueOf} of a threshold and so says {@code "1001.0"} where the
     * document says {@code 1001}. Read as a number and formatted the one way,
     * so the two sides meet.</p>
     *
     * @param baseItemId the item the model is reached through
     * @param values     the custom model data values that reach it
     */
    public static Fact forModel(String baseItemId, java.util.List<String> values) {
        if (baseItemId == null || baseItemId.isEmpty() || values == null
                || values.isEmpty() || items().isEmpty()) {
            return null;
        }
        for (String value : values) {
            if (value == null || value.isEmpty()) {
                continue;
            }
            try {
                Fact fact = get(baseItemId, Float.parseFloat(value.trim()));
                if (fact != null) {
                    return fact;
                }
            } catch (NumberFormatException notANumber) {
                // A dispatch on a string. The document keys on numbers.
            }
        }
        return null;
    }

    /**
     * Something that changes whenever the document does.
     *
     * <p>For whoever caches work derived from it. The names are read when the
     * wrap catalogue is built, and that catalogue is kept until the models
     * change - so without this a fetch would write a perfectly good document
     * that nothing ever looked at, because the models had not moved and
     * nothing knew to build again. Which is exactly what happened.</p>
     */
    public static String stamp() {
        items();
        return updated + "/" + items.size() + "/" + sets.size();
    }

    /** How many items the document describes. */
    public static int count() {
        return items().size();
    }

    /** Where it came from, for the screen to say. */
    public static String source() {
        items();
        return source;
    }

    /** When it was last written, for the screen to say. */
    public static String updated() {
        items();
        return updated;
    }


    /**
     * The items a document describes, each tidied, ready to be answered with.
     *
     * <p>A seam rather than four lines inside the read, because the question
     * \u201cis the tidying actually applied to what comes off the disk\u201d is a
     * different question from \u201cdoes the tidying work\u201d, and this mod has
     * shipped the first failure twice while the second was perfectly fine. A
     * check on {@link #tidied} alone passes just as happily when nothing ever
     * calls it.</p>
     */
    public static Map<String, Fact> itemsIn(Document document) {
        Map<String, Fact> found = new TreeMap<>();
        if (document == null || document.items() == null) {
            return found;
        }
        document.items().forEach((k, v) -> {
            if (k != null && v != null && !v.isEmpty()) {
                found.put(k, tidied(v));
            }
        });
        return found;
    }

    /** As {@link #itemsIn(Document)}, from a document's text. */
    public static Map<String, Fact> itemsIn(String documentJson) {
        try {
            return itemsIn(GSON.<Document>fromJson(documentJson,
                    new TypeToken<Document>() { }.getType()));
        } catch (RuntimeException unreadable) {
            return new TreeMap<>();
        }
    }

    /**
     * The same fact with a separator the server left in its own name taken out.
     *
     * <p>Some of these names are built by something that wrote a colour code
     * before every letter and did not take the marker off again, so the runs
     * arrive as {@code "O&"}, {@code "c&"}, {@code "e&"} and the name draws as
     * {@code O&c&e&a&n&i&c}. Forty-three of the sixteen hundred in the
     * catalogue this was built against, every one of them a gradient.</p>
     *
     * <p>Done here, as the document is read, rather than where the catalogue
     * is downloaded. A document already on disk is the normal case - fetching
     * is something you do when you want newer information, so a name written
     * last week has to come out right today without asking the service
     * anything. It also means a file somebody wrote or edited by hand gets the
     * same treatment.</p>
     *
     * <p>Taking a trailing marker off each run is a guess, so it is checked
     * rather than trusted: the document also carries the name as plain text,
     * and the shortened runs are kept only if they spell that name exactly.
     * Where they do not - a name that really ends in one, a server that does
     * this differently - the runs are left as they came. So this tidies the
     * case it was written for and cannot invent a name.</p>
     */
    public static Fact tidied(Fact fact) {
        if (fact == null || fact.parts() == null || fact.parts().isEmpty()
                || fact.name() == null || fact.name().isEmpty()) {
            return fact;
        }
        java.util.List<Part> shorter = new java.util.ArrayList<>(fact.parts().size());
        StringBuilder spelled = new StringBuilder();
        boolean anyShortened = false;
        for (Part part : fact.parts()) {
            String text = part == null ? null : part.text();
            if (text != null && text.length() > 1 && text.endsWith("&")) {
                text = text.substring(0, text.length() - 1);
                anyShortened = true;
            }
            shorter.add(new Part(text, part == null ? null : part.colour()));
            spelled.append(text == null ? "" : text);
        }
        if (!anyShortened || !spelled.toString().equals(fact.name())) {
            return fact;
        }
        return new Fact(fact.name(), fact.crate(), fact.set(), fact.rarity(),
                fact.colours(), shorter);
    }

    /** Forgets what was read, so the next question reads the file again. */
    public static void reload() {
        items = null;
        sets = new TreeMap<>();
        source = "";
        updated = "";
    }
}
