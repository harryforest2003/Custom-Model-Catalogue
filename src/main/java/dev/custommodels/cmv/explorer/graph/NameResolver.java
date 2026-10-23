package dev.custommodels.cmv.explorer.graph;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.custommodels.cmv.explorer.res.ResourceSource;

import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds the name a pack author actually gave a model, falling back to the path
 * when they gave none.
 *
 * <h2>Where a real name can come from</h2>
 *
 * <p>Only one place, in practice: a language file. A pack that ships
 * {@code assets/<ns>/lang/en_us.json} with an entry for an item or a model has
 * stated what it calls that thing, and that is the name to show. Everything
 * else the explorer displays is derived from a path, and derived names are
 * marked as such — see {@link ResolvedName}.</p>
 *
 * <p>It is worth being blunt about the hit rate. Of the packs examined while
 * this was written, the large server pack shipped five language files between
 * them containing a single entry, which blanked the inventory title. So this
 * class is written to find a name when one exists and to get out of the way
 * quietly when one does not, which is the common case.</p>
 *
 * <h2>Which keys are consulted</h2>
 *
 * <p>Minecraft's own convention is {@code item.<namespace>.<path>}, with the
 * path's slashes replaced by dots. A pack naming an item definition would use
 * the definition's id; one naming a model would use the model's. Both are
 * tried, along with the bare path, because packs are inconsistent and a lookup
 * costs a hash probe.</p>
 *
 * <p>What is deliberately <em>not</em> done is matching a language value by
 * resemblance to a file name. "Dragon Blade" appearing somewhere in a language
 * file is not evidence that it names {@code item/weapons/dragon_blade}; it
 * might be an advancement, a menu button or a quest line. A name shown as fact
 * has to have been keyed to the thing it names.</p>
 */
public final class NameResolver {

    /** Language files consulted, best first. */
    private static final List<String> LANGUAGE_CODES =
            List.of("en_us", "en_gb", "en_au", "en_ca", "en_nz");

    private final Map<String, String> translations = new HashMap<>();
    private final Map<String, String> keySource = new HashMap<>();
    private int entriesLoaded;
    private int filesRead;

    /**
     * Loads every language file the packs contribute.
     *
     * <p>Runs on the scan worker. A pack with real names has a few thousand
     * entries; reading them once and keeping a map is cheaper than opening the
     * file per model, and the map is a few hundred kilobytes at worst.</p>
     */
    public NameResolver(ResourceSource source) {
        for (String namespace : source.namespaces()) {
            for (String code : LANGUAGE_CODES) {
                load(source, namespace, code);
            }
        }
    }

    /** Number of translation entries available. Zero is normal. */
    public int entriesLoaded() {
        return entriesLoaded;
    }

    public int filesRead() {
        return filesRead;
    }

    private void load(ResourceSource source, String namespace, String code) {
        try (Reader reader = source.openLang(namespace, code)) {
            if (reader == null) {
                return;
            }
            JsonElement root = JsonParser.parseReader(reader);
            if (root == null || !root.isJsonObject()) {
                return;
            }
            filesRead++;
            String origin = "assets/" + namespace + "/lang/" + code + ".json";
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
                if (!entry.getValue().isJsonPrimitive()) {
                    continue;
                }
                String value = entry.getValue().getAsString();
                if (value.isBlank()) {
                    // A pack blanking a vanilla string is not naming anything.
                    continue;
                }
                // First file wins, so en_us beats the regional variants.
                if (translations.putIfAbsent(entry.getKey(), value) == null) {
                    keySource.put(entry.getKey(), origin);
                    entriesLoaded++;
                }
            }
        } catch (IOException | RuntimeException e) {
            // A malformed language file costs its own names and nothing else.
        }
    }

    /**
     * The best name available for a model.
     *
     * @param modelId       e.g. {@code example:item/ancient/dragonblade}
     * @param definitionId  e.g. {@code example:items/ancient_blade.json}
     * @param baseItemId    e.g. {@code minecraft:diamond_sword}
     * @param customModelData the select values that reach the model, if any
     * @param derivedName   the path-derived name to fall back to
     */
    public ResolvedName resolve(String modelId,
                                String definitionId,
                                String baseItemId,
                                List<String> customModelData,
                                String derivedName) {
        for (String key : candidateKeys(modelId, definitionId)) {
            String value = translations.get(key);
            if (value != null) {
                return ResolvedName.of(value, ResolvedName.Source.TRANSLATION,
                        key + "  in  " + keySource.getOrDefault(key, "lang"));
            }
        }
        // A custom model data value is the author's own label for the variant,
        // which beats a path but is not a stated name.
        if (!customModelData.isEmpty()) {
            String first = customModelData.get(0);
            if (!first.isEmpty() && !GraphBuilder.isNumeric(first)) {
                return ResolvedName.of(GraphBuilder.prettify(first),
                        ResolvedName.Source.CUSTOM_MODEL_DATA, first);
            }
        }
        if (!derivedName.isEmpty()) {
            return ResolvedName.of(derivedName, ResolvedName.Source.PATH,
                    modelId.isEmpty() ? definitionId : modelId);
        }
        return ResolvedName.of(GraphBuilder.prettify(shortPath(baseItemId)),
                ResolvedName.Source.FALLBACK, baseItemId);
    }

    /**
     * The language keys that could name this model, most specific first.
     *
     * <p>Both {@code item.} and {@code model.} prefixes are tried because no
     * convention exists for naming a model directly and packs invent one.</p>
     */
    static List<String> candidateKeys(String modelId, String definitionId) {
        Map<String, Boolean> keys = new LinkedHashMap<>();
        addKeysFor(keys, modelId);
        addKeysFor(keys, stripJson(definitionId));
        return List.copyOf(keys.keySet());
    }

    private static void addKeysFor(Map<String, Boolean> keys, String id) {
        if (id == null || id.isEmpty()) {
            return;
        }
        int colon = id.indexOf(':');
        String namespace = colon >= 0 ? id.substring(0, colon) : "minecraft";
        String path = colon >= 0 ? id.substring(colon + 1) : id;
        // Minecraft's own translation keys replace path separators with dots.
        String dotted = path.replace('/', '.');
        // An item definition id carries an "items/" prefix that a translation
        // key would not repeat.
        String withoutItems = dotted.startsWith("items.")
                ? dotted.substring("items.".length()) : dotted;

        keys.put("item." + namespace + "." + withoutItems, Boolean.TRUE);
        keys.put("item." + namespace + "." + dotted, Boolean.TRUE);
        keys.put("model." + namespace + "." + dotted, Boolean.TRUE);
        keys.put(namespace + "." + dotted, Boolean.TRUE);
        // Some packs key on the last segment alone.
        int lastDot = withoutItems.lastIndexOf('.');
        if (lastDot >= 0) {
            keys.put("item." + namespace + "." + withoutItems.substring(lastDot + 1),
                    Boolean.TRUE);
        }
    }

    private static String stripJson(String id) {
        return id != null && id.endsWith(".json")
                ? id.substring(0, id.length() - ".json".length()) : id;
    }

    private static String shortPath(String id) {
        if (id == null) {
            return "";
        }
        int colon = id.indexOf(':');
        String path = colon >= 0 ? id.substring(colon + 1) : id;
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /** Lower-cased lookup used by the debug panel. */
    public String rawTranslation(String key) {
        return translations.get(key.toLowerCase(Locale.ROOT));
    }
}
