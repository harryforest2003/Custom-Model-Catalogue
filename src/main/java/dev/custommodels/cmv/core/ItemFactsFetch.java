package dev.custommodels.cmv.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Builds {@link ItemFacts} from a server's published catalogue.
 *
 * <p>The only part of this mod that touches the network, and it is reached by
 * asking for it. Nothing here runs on its own, on a timer, on joining a world
 * or on opening a screen: a catalogue is downloaded when somebody says to
 * download one, and what it leaves behind is a file that works afterwards
 * whether or not the service ever answers again.</p>
 *
 * <h2>What it expects</h2>
 *
 * <p>Nothing specific to any one server. Three endpoints under a base address,
 * in the shape the catalogue this was built against publishes:</p>
 *
 * <ul>
 *   <li>{@code /api/items} — either a bare array or an object with a {@code
 *       data} array. Each entry may carry {@code ItemName}, {@code CrateID}
 *       and {@code RawData}.</li>
 *   <li>{@code /api/crates} — {@code id} and {@code CrateName}.</li>
 *   <li>{@code /api/sets} — {@code Name} and {@code ItemList}.</li>
 * </ul>
 *
 * <p>{@code RawData} is the item's own component JSON, and it is the whole
 * reason this join is possible: it carries the base item and the {@code
 * custom_model_data} value, which is the one thing a server's catalogue and a
 * resource pack can both be made to agree about. A catalogue that does not
 * publish it can still be read for names, but there is nothing to attach them
 * to, and this says so rather than writing an empty document.</p>
 *
 * <p>Everything is optional and everything is guarded. A field that is absent,
 * null, renamed or of the wrong type costs that one item and no more, because
 * the shape of somebody else's service is not something this can rely on.</p>
 */
public final class ItemFactsFetch {

    private static final Gson GSON = new Gson();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** One at a time, off the game thread, and a daemon so it holds nothing open. */
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "CMV-ItemFacts");
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY - 2);
                return thread;
            });

    private static volatile boolean running;

    private ItemFactsFetch() {
    }

    /** What happened, in one line fit to show somebody. */
    public record Outcome(boolean ok, int items, int withKey, String message) {
    }

    /** Whether a download is already in flight. */
    public static boolean running() {
        return running;
    }

    /**
     * Downloads a catalogue and rewrites the document, off the game thread.
     *
     * @param base   the catalogue's base address, e.g. {@code https://example.invalid}
     * @param report handed the outcome, on the worker; the caller marshals it
     */
    public static void update(String base, Consumer<Outcome> report) {
        if (base == null || base.isBlank()) {
            report.accept(new Outcome(false, 0, 0, "No catalogue address is set"));
            return;
        }
        if (running) {
            report.accept(new Outcome(false, 0, 0, "Already fetching"));
            return;
        }
        running = true;
        WORKER.execute(() -> {
            try {
                report.accept(fetch(base.replaceAll("/+$", "")));
            } catch (RuntimeException e) {
                CmvMod.LOGGER.warn("Item catalogue fetch failed", e);
                report.accept(new Outcome(false, 0, 0, "Fetch failed: " + e));
            } finally {
                running = false;
            }
        });
    }

    private static Outcome fetch(String base) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        JsonElement itemsJson = get(client, base + "/api/items",
                "id;CrateID;ItemName;RarityHuman;RawData");
        if (itemsJson == null) {
            return new Outcome(false, 0, 0, "No answer from " + base + "/api/items");
        }
        Map<String, String> crates = names(get(client, base + "/api/crates", null),
                "id", "CrateName");
        JsonElement setsJson = get(client, base + "/api/sets", null);
        Map<String, String> sets = setsByItem(setsJson);
        Map<String, java.util.List<String>> membersOf = membersOf(setsJson);

        Map<String, ItemFacts.Fact> built = new TreeMap<>();
        Map<String, java.util.List<String>> keyed = new HashMap<>();
        int seen = 0;

        // The second list. A catalogue may keep the things that come out of
        // crates apart from the things that do not - furniture, cosmetics,
        // everything else - and reading only the first leaves most of what a
        // player owns unnamed. Read first so that a crate item wins any value
        // they both claim, since that is the list with rarities behind it.
        JsonElement miscJson = get(client, base + "/api/miscitems",
                "id;GroupID;ItemName;RawData");
        if (miscJson != null) {
            Map<String, String> groups = names(get(client, base + "/api/miscgroups", null),
                    "id", "GroupName");
            for (JsonElement element : rows(miscJson)) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject row = element.getAsJsonObject();
                seen++;
                String rawData = string(row, "RawData");
                String miscName = string(row, "ItemName");
                ItemFacts.Fact fact = new ItemFacts.Fact(
                        miscName,
                        groups.getOrDefault(string(row, "GroupID"), ""),
                        "", "", coloured(rawData), parts(rawData));
                for (String key : keysOf(rawData)) {
                    built.put(key, fact);
                }
            }
        }
        for (JsonElement element : rows(itemsJson)) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject row = element.getAsJsonObject();
            seen++;
            String name = string(row, "ItemName");
            String crate = crates.getOrDefault(string(row, "CrateID"), "");
            String set = sets.getOrDefault(string(row, "id"), "");
            String rarity = string(row, "RarityHuman");
            String colours = coloured(string(row, "RawData"));
            java.util.List<ItemFacts.Part> parts = parts(string(row, "RawData"));
            java.util.List<String> keys = keysOf(string(row, "RawData"));
            for (String key : keys) {
                // The last writer wins, which matches the game: among equal
                // thresholds a range dispatch takes the last entry.
                built.put(key, new ItemFacts.Fact(name, crate, set, rarity, colours, parts));
            }
            if (!keys.isEmpty()) {
                keyed.computeIfAbsent(string(row, "id"), k -> new java.util.ArrayList<>())
                        .addAll(keys);
            }
        }

        // What each set is made of, and whether the catalogue accounted for all
        // of it. A set missing a piece is recorded as missing it rather than
        // quietly shrinking, because a part of a set is not the set.
        Map<String, ItemFacts.SetFacts> setFacts = new TreeMap<>();
        membersOf.forEach((setName, memberIds) -> {
            java.util.List<String> keys2 = new java.util.ArrayList<>();
            int accounted = 0;
            for (String memberId : memberIds) {
                java.util.List<String> mine = keyed.get(memberId);
                if (mine != null && !mine.isEmpty()) {
                    accounted++;
                    keys2.addAll(mine);
                }
            }
            setFacts.put(setName,
                    new ItemFacts.SetFacts(memberIds.size(), accounted, keys2));
        });
        if (built.isEmpty()) {
            return new Outcome(false, seen, 0,
                    "Read " + seen + " items, none with an item and model-data pair");
        }
        ItemFacts.replace(base, built, setFacts);
        return new Outcome(true, seen, built.size(),
                "Wrote " + built.size() + " of " + seen + " items");
    }

    /**
     * Every key one catalogue entry answers to.
     *
     * <p>A list, because the component holds a list of values and a pack may
     * dispatch on any of them.</p>
     */
    private static java.util.List<String> keysOf(String rawData) {
        java.util.List<String> keys = new java.util.ArrayList<>(1);
        if (rawData == null || rawData.isBlank()) {
            return keys;
        }
        try {
            JsonObject raw = JsonParser.parseString(rawData).getAsJsonObject();
            String item = string(raw, "id");
            JsonObject components = raw.getAsJsonObject("components");
            if (item.isEmpty() || components == null) {
                return keys;
            }
            JsonObject cmd = components.getAsJsonObject("minecraft:custom_model_data");
            if (cmd == null) {
                return keys;
            }
            JsonArray floats = cmd.getAsJsonArray("floats");
            if (floats == null) {
                return keys;
            }
            for (JsonElement value : floats) {
                try {
                    keys.add(ItemFacts.key(item, value.getAsFloat()));
                } catch (RuntimeException notANumber) {
                    // One odd value, not a reason to lose the item.
                }
            }
        } catch (RuntimeException unreadable) {
            // Somebody else's field, in somebody else's shape. Skip the item.
        }
        return keys;
    }

    /** Minecraft's own colour names, in the order of the codes that spell them. */
    private static final String COLOURS =
            "black dark_blue dark_green dark_aqua dark_red dark_purple gold gray "
            + "dark_gray blue green aqua red light_purple yellow white";

    /**
     * The item's name with its colours, as codes.
     *
     * <p>Taken from the {@code minecraft:custom_name} component rather than
     * from the plain name field, because that component is the name the server
     * actually puts on the item and it carries the styling segment by segment.
     * Written with {@code &} rather than the section sign so the result is
     * something a person can read, keep in a file, and paste into the places
     * that take colour codes.</p>
     *
     * <p>Only colour is kept. Bold, italic and the rest describe how that one
     * item is drawn, and the point of holding this is to have the colours to
     * use elsewhere.</p>
     */
    /**
     * The colours a server's own name for an item is written in.
     *
     * <p>Taken from the {@code minecraft:custom_name} component, which is the
     * name the server actually puts on the item and carries its styling
     * segment by segment. Each colour once, in the order it first appears,
     * written with {@code &} so it can be pasted where colour codes go.</p>
     *
     * <p>Only the colours, and collected here rather than recovered later. The
     * names themselves contain literal {@code &} characters - a gradient of
     * theirs reads {@code "text": "M&"} - so a string mixing codes and words
     * cannot be taken apart again afterwards. Here there is no ambiguity: the
     * component says what each colour is.</p>
     *
     * <p>Bold, italic and the rest are left behind. They describe how that one
     * item is drawn; the point of keeping this is to have the colours.</p>
     */
    public static String coloured(String rawData) {
        if (rawData == null || rawData.isBlank()) {
            return "";
        }
        try {
            JsonObject raw = JsonParser.parseString(rawData).getAsJsonObject();
            JsonObject components = raw.getAsJsonObject("components");
            if (components == null) {
                return "";
            }
            JsonElement nameComponent = components.get("minecraft:custom_name");
            if (nameComponent == null || nameComponent.isJsonNull()) {
                return "";
            }
            // It arrives as a string holding a text component, not as one.
            JsonElement text = nameComponent.isJsonPrimitive()
                    ? JsonParser.parseString(nameComponent.getAsString())
                    : nameComponent;
            java.util.Set<String> found = new java.util.LinkedHashSet<>();
            collect(text, found, "");
            StringBuilder out = new StringBuilder();
            for (String code : found) {
                out.append('&').append(code);
            }
            return out.toString();
        } catch (RuntimeException unreadable) {
            return "";
        }
    }

    /**
     * The name broken into runs, each with the colour it is written in.
     *
     * <p>Kept so the name can be drawn the way the server draws it. Not a
     * string of codes: these names contain the marker character in their own
     * text, so a flattened string could not be taken apart again. Each run
     * carries its own colour and there is nothing to parse.</p>
     */
    public static java.util.List<ItemFacts.Part> parts(String rawData) {
        java.util.List<ItemFacts.Part> parts = new java.util.ArrayList<>();
        if (rawData == null || rawData.isBlank()) {
            return parts;
        }
        try {
            JsonObject raw = JsonParser.parseString(rawData).getAsJsonObject();
            JsonObject components = raw.getAsJsonObject("components");
            if (components == null) {
                return parts;
            }
            JsonElement nameComponent = components.get("minecraft:custom_name");
            if (nameComponent == null || nameComponent.isJsonNull()) {
                return parts;
            }
            JsonElement text = nameComponent.isJsonPrimitive()
                    ? JsonParser.parseString(nameComponent.getAsString())
                    : nameComponent;
            gather(text, parts, "");
            return parts;
        } catch (RuntimeException unreadable) {
            return new java.util.ArrayList<>();
        }
    }

    /** Walks a text component, keeping each run of text with its colour. */
    private static void gather(JsonElement node, java.util.List<ItemFacts.Part> parts,
                               String inherited) {
        if (node == null || node.isJsonNull()) {
            return;
        }
        if (node.isJsonArray()) {
            for (JsonElement child : node.getAsJsonArray()) {
                gather(child, parts, inherited);
            }
            return;
        }
        if (node.isJsonPrimitive()) {
            parts.add(new ItemFacts.Part(node.getAsString(), inherited));
            return;
        }
        if (!node.isJsonObject()) {
            return;
        }
        JsonObject object = node.getAsJsonObject();
        String colour = string(object, "color");
        String here = colour.isEmpty() ? inherited : colour;
        String body = string(object, "text");
        if (!body.isEmpty()) {
            parts.add(new ItemFacts.Part(body, here));
        }
        gather(object.get("extra"), parts, here);
    }

    /** Walks a text component, noting every colour that actually paints text. */
    private static void collect(JsonElement node, java.util.Set<String> found,
                                String inherited) {
        if (node == null || node.isJsonNull()) {
            return;
        }
        if (node.isJsonArray()) {
            for (JsonElement child : node.getAsJsonArray()) {
                collect(child, found, inherited);
            }
            return;
        }
        if (!node.isJsonObject()) {
            return;
        }
        JsonObject object = node.getAsJsonObject();
        String colour = string(object, "color");
        String here = colour.isEmpty() ? inherited : colour;
        // Only where there is something for the colour to be. A segment with a
        // colour and no text of its own has not used it.
        if (!string(object, "text").isEmpty()) {
            String code = codeFor(here);
            if (!code.isEmpty()) {
                found.add(code);
            }
        }
        collect(object.get("extra"), found, here);
    }

    /** The code for a colour name, or a hex colour written as it came. */
    private static String codeFor(String colour) {
        if (colour == null || colour.isEmpty()) {
            return "";
        }
        if (colour.startsWith("#")) {
            // Kept exactly as written: a hex colour squeezed into the nearest
            // one-letter code would be a different colour.
            return colour;
        }
        String[] names = COLOURS.split(" ");
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(colour)) {
                return String.valueOf("0123456789abcdef".charAt(i));
            }
        }
        return "";
    }

    /** id to name, for a list of small objects. */
    private static Map<String, String> names(JsonElement json, String idKey, String nameKey) {
        Map<String, String> out = new HashMap<>();
        if (json == null) {
            return out;
        }
        for (JsonElement element : rows(json)) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject row = element.getAsJsonObject();
            String id = string(row, idKey);
            String name = string(row, nameKey);
            if (!id.isEmpty() && !name.isEmpty()) {
                out.put(id, name);
            }
        }
        return out;
    }

    /**
     * Set name to the catalogue's own ids for its members.
     *
     * <p>Separate from {@link #setsByItem} because that answers "which set is
     * this item in" and this answers "what is in this set" - and only the
     * second can say whether a set is complete.</p>
     */
    private static Map<String, java.util.List<String>> membersOf(JsonElement json) {
        Map<String, java.util.List<String>> out = new java.util.LinkedHashMap<>();
        if (json == null) {
            return out;
        }
        for (JsonElement element : rows(json)) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject row = element.getAsJsonObject();
            String name = string(row, "Name");
            String list = string(row, "ItemList");
            if (name.isEmpty() || list.isEmpty()) {
                continue;
            }
            try {
                java.util.List<String> members = new java.util.ArrayList<>();
                for (JsonElement member : JsonParser.parseString(list).getAsJsonArray()) {
                    members.add(member.getAsString());
                }
                if (!members.isEmpty()) {
                    out.put(name, members);
                }
            } catch (RuntimeException unreadable) {
                // A set whose membership cannot be read is not described here.
            }
        }
        return out;
    }

    /**
     * Item id to set name, from sets that list their members.
     *
     * <p>The member list arrives as a string holding a JSON array rather than
     * as an array, which is read as written rather than corrected.</p>
     */
    private static Map<String, String> setsByItem(JsonElement json) {
        Map<String, String> out = new HashMap<>();
        if (json == null) {
            return out;
        }
        for (JsonElement element : rows(json)) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject row = element.getAsJsonObject();
            String name = string(row, "Name");
            String list = string(row, "ItemList");
            if (name.isEmpty() || list.isEmpty()) {
                continue;
            }
            try {
                for (JsonElement member : JsonParser.parseString(list).getAsJsonArray()) {
                    out.put(member.getAsString(), name);
                }
            } catch (RuntimeException unreadable) {
                // A set whose membership cannot be read simply has none here.
            }
        }
        return out;
    }

    /** Either a bare array or one under {@code data}. */
    private static JsonArray rows(JsonElement json) {
        if (json.isJsonArray()) {
            return json.getAsJsonArray();
        }
        if (json.isJsonObject()) {
            JsonArray data = json.getAsJsonObject().getAsJsonArray("data");
            if (data != null) {
                return data;
            }
        }
        return new JsonArray();
    }

    private static String string(JsonObject row, String key) {
        try {
            JsonElement value = row.get(key);
            return value == null || value.isJsonNull() ? "" : value.getAsString();
        } catch (RuntimeException notAString) {
            return "";
        }
    }

    private static JsonElement get(HttpClient client, String url, String fields) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("Accept", "application/json")
                    .GET();
            if (fields != null) {
                // How this catalogue is asked for more than the default columns.
                request.header("I-INCLUDED-INFO", fields);
            }
            HttpResponse<String> response = client.send(request.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                CmvMod.LOGGER.warn("Item catalogue {} answered {}",
                        url, response.statusCode());
                return null;
            }
            return JsonParser.parseString(response.body());
        } catch (java.io.IOException | RuntimeException e) {
            CmvMod.LOGGER.warn("Could not read {}: {}", url, e.toString());
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
