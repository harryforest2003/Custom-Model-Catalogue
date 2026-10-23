package dev.custommodels.cmv.explorer.res;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One model JSON file, parsed but not yet resolved.
 *
 * <p>The 26.2 model format is the long-standing block/item model format:
 * {@code parent}, {@code textures}, {@code elements}, {@code display} and
 * {@code gui_light}. Verified against the game's own
 * {@code assets/minecraft/models/item/*.json} in the 26.2 client jar.</p>
 *
 * <p>Parsing is hand-rolled rather than delegated to
 * {@code CuboidModel.fromStream}, so that a single malformed element degrades
 * to a warning instead of losing the file, and so that the raw texture strings
 * survive for the information panel. It also keeps this class free of
 * Minecraft imports, which is what allows resolution to run on a worker thread
 * and to be exercised outside the game.</p>
 */
public final class ModelJson {

    /** A cuboid, in model space where the block occupies 0..16 on each axis. */
    public record Element(float[] from,
                          float[] to,
                          Map<String, Face> faces,
                          Rotation rotation,
                          boolean shade) {
    }

    /**
     * One face of a cuboid.
     *
     * @param texture   the slot reference, e.g. {@code #layer0} or {@code #side}
     * @param uv        {@code [u1, v1, u2, v2]} in 0..16 texture space, or null
     *                  to derive from the cuboid's own extent as Minecraft does
     * @param rotation  0, 90, 180 or 270 degrees of UV rotation
     * @param tintIndex tint slot, or -1
     * @param cullFace  the direction this face is culled against, or null
     */
    public record Face(String texture, float[] uv, int rotation, int tintIndex, String cullFace) {
    }

    /** An element rotation: one axis, one angle, about an origin. */
    public record Rotation(float[] origin, String axis, float angle, boolean rescale) {
    }

    /** A display transform, e.g. the {@code gui} entry. */
    /**
     * A display transform.
     *
     * <p>{@code equals} and {@code hashCode} are written out because a record
     * of arrays does not have the value semantics it appears to: the generated
     * ones compare the array <em>objects</em>, so two transforms holding the
     * same nine numbers are unequal. That is silently wrong anywhere a
     * {@link ResolvedModel} is compared — after a round trip through the cache,
     * most obviously, where every model is rebuilt from its numbers and none of
     * them would match itself.</p>
     */
    public record Transform(float[] rotation, float[] translation, float[] scale) {

        public static final Transform IDENTITY = new Transform(
                new float[]{0, 0, 0}, new float[]{0, 0, 0}, new float[]{1, 1, 1});

        @Override
        public boolean equals(Object other) {
            return other instanceof Transform t
                    && java.util.Arrays.equals(rotation, t.rotation)
                    && java.util.Arrays.equals(translation, t.translation)
                    && java.util.Arrays.equals(scale, t.scale);
        }

        @Override
        public int hashCode() {
            return java.util.Arrays.hashCode(rotation) * 31 * 31
                    + java.util.Arrays.hashCode(translation) * 31
                    + java.util.Arrays.hashCode(scale);
        }

        @Override
        public String toString() {
            return "Transform" + java.util.Arrays.toString(rotation)
                    + java.util.Arrays.toString(translation)
                    + java.util.Arrays.toString(scale);
        }
    }

    private final String modelId;
    private final String parentId;
    private final Map<String, String> textures;
    private final Map<String, Boolean> forceTranslucent;
    private final List<Element> elements;
    private final Map<String, Transform> display;
    private final String guiLight;
    private final boolean hasElementsKey;
    private final List<String> warnings;
    private final String rawJson;

    private ModelJson(String modelId, String parentId, Map<String, String> textures,
                      Map<String, Boolean> forceTranslucent,
                      List<Element> elements, Map<String, Transform> display,
                      String guiLight, boolean hasElementsKey, List<String> warnings,
                      String rawJson) {
        this.modelId = modelId;
        this.parentId = parentId;
        this.textures = textures;
        this.forceTranslucent = forceTranslucent;
        this.elements = elements;
        this.display = display;
        this.guiLight = guiLight;
        this.hasElementsKey = hasElementsKey;
        this.warnings = warnings;
        this.rawJson = rawJson;
    }

    /**
     * The file's text, when it was asked for at parse time.
     *
     * <p>Only kept for files that declare geometry, so that the geometry can be
     * re-parsed later without going back to the resource pack. Null otherwise.
     * See {@code ModelResolver.geometryJson} for why going back to the pack is
     * something to avoid.</p>
     */
    public String rawJson() {
        return rawJson;
    }

    /** e.g. {@code example:item/bows/dragon_bow}. */
    public String modelId() {
        return modelId;
    }

    /** The {@code parent} field, namespaced, or null when absent. */
    public String parentId() {
        return parentId;
    }

    /** Raw texture slots as written, values may be {@code #references}. */
    public Map<String, String> textures() {
        return textures;
    }

    /** Slots declared with {@code force_translucent}, reported in the detail panel. */
    public Map<String, Boolean> forceTranslucent() {
        return forceTranslucent;
    }

    public List<Element> elements() {
        return elements;
    }

    /** True when the file declared {@code elements}, even if the array was empty. */
    public boolean hasElementsKey() {
        return hasElementsKey;
    }

    public Map<String, Transform> display() {
        return display;
    }

    /** {@code "front"} or {@code "side"}, or null when not specified. */
    public String guiLight() {
        return guiLight;
    }

    public List<String> warnings() {
        return warnings;
    }

    // ------------------------------------------------------------------ parse

    /** Parses one model file. Never throws; problems become warnings. */
    public static ModelJson parse(String modelId, Reader reader) {
        return parse(modelId, reader, false);
    }

    /**
     * @param keepRaw whether to retain the file's text on the result, so the
     *                geometry cache can be filled without reading it again.
     *                Only worth doing for files that declare geometry.
     */
    public static ModelJson parse(String modelId, Reader reader, boolean keepRaw) {
        List<String> warnings = new ArrayList<>();
        JsonObject root;
        String raw = null;
        try {
            if (keepRaw) {
                raw = readAll(reader);
                reader = new java.io.StringReader(raw);
            }
            JsonElement element = JsonParser.parseReader(reader);
            if (element == null || !element.isJsonObject()) {
                warnings.add("Model is not a JSON object");
                return empty(modelId, warnings);
            }
            root = element.getAsJsonObject();
        } catch (java.io.IOException | RuntimeException e) {
            warnings.add("Malformed JSON: " + e.getMessage());
            return empty(modelId, warnings);
        }

        String parent = namespaced(string(root.get("parent")));
        String guiLight = string(root.get("gui_light"));

        Map<String, String> textures = new LinkedHashMap<>();
        Map<String, Boolean> translucent = new LinkedHashMap<>();
        JsonElement texturesNode = root.get("textures");
        if (texturesNode != null && texturesNode.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : texturesNode.getAsJsonObject().entrySet()) {
                JsonElement value = entry.getValue();
                if (value.isJsonObject()) {
                    // 26.x also accepts the expanded form
                    // {"sprite": "ns:path", "force_translucent": true}.
                    // 110 vanilla models use it, so it is not an edge case.
                    JsonObject material = value.getAsJsonObject();
                    String sprite = string(material.get("sprite"));
                    if (sprite != null) {
                        textures.put(entry.getKey(), sprite);
                        if (booleanOr(material.get("force_translucent"), false)) {
                            translucent.put(entry.getKey(), Boolean.TRUE);
                        }
                    } else {
                        warnings.add("Texture slot \"" + entry.getKey()
                                + "\" has no \"sprite\"");
                    }
                    continue;
                }
                String plain = string(value);
                if (plain != null) {
                    textures.put(entry.getKey(), plain);
                }
            }
        }

        boolean hasElementsKey = root.has("elements");
        List<Element> elements = new ArrayList<>();
        JsonElement elementsNode = root.get("elements");
        if (elementsNode != null && elementsNode.isJsonArray()) {
            JsonArray array = elementsNode.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                Element parsed = parseElement(array.get(i), i, warnings);
                if (parsed != null) {
                    elements.add(parsed);
                }
            }
        }

        Map<String, Transform> display = new LinkedHashMap<>();
        JsonElement displayNode = root.get("display");
        if (displayNode != null && displayNode.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : displayNode.getAsJsonObject().entrySet()) {
                if (!entry.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject transform = entry.getValue().getAsJsonObject();
                display.put(entry.getKey(), new Transform(
                        floats(transform.get("rotation"), 3, 0.0F),
                        floats(transform.get("translation"), 3, 0.0F),
                        floats(transform.get("scale"), 3, 1.0F)));
            }
        }

        return new ModelJson(modelId, parent, textures, translucent, elements, display,
                guiLight, hasElementsKey, warnings,
                // Only geometry is ever re-read, so only that text is worth keeping.
                hasElementsKey && !elements.isEmpty() ? raw : null);
    }

    private static ModelJson empty(String modelId, List<String> warnings) {
        return new ModelJson(modelId, null, Map.of(), Map.of(), List.of(), Map.of(),
                null, false, warnings, null);
    }

    private static String readAll(Reader reader) throws java.io.IOException {
        StringBuilder builder = new StringBuilder(4096);
        char[] buffer = new char[8192];
        int read;
        while ((read = reader.read(buffer)) > 0) {
            builder.append(buffer, 0, read);
        }
        return builder.toString();
    }

    private static Element parseElement(JsonElement node, int index, List<String> warnings) {
        if (!node.isJsonObject()) {
            warnings.add("Element " + index + " is not an object");
            return null;
        }
        JsonObject object = node.getAsJsonObject();
        float[] from = floats(object.get("from"), 3, 0.0F);
        float[] to = floats(object.get("to"), 3, 16.0F);
        if (object.get("from") == null || object.get("to") == null) {
            warnings.add("Element " + index + " is missing \"from\" or \"to\"");
            return null;
        }

        Map<String, Face> faces = new LinkedHashMap<>();
        JsonElement facesNode = object.get("faces");
        if (facesNode != null && facesNode.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : facesNode.getAsJsonObject().entrySet()) {
                if (!entry.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject face = entry.getValue().getAsJsonObject();
                String texture = string(face.get("texture"));
                if (texture == null) {
                    warnings.add("Element " + index + " face " + entry.getKey()
                            + " has no texture");
                    continue;
                }
                float[] uv = face.has("uv") ? floats(face.get("uv"), 4, 0.0F) : null;
                faces.put(entry.getKey(), new Face(
                        texture,
                        uv,
                        intOr(face.get("rotation"), 0),
                        intOr(face.get("tintindex"), -1),
                        string(face.get("cullface"))));
            }
        }

        Rotation rotation = null;
        JsonElement rotationNode = object.get("rotation");
        if (rotationNode != null && rotationNode.isJsonObject()) {
            JsonObject r = rotationNode.getAsJsonObject();
            String axis = string(r.get("axis"));
            if (axis != null) {
                rotation = new Rotation(
                        floats(r.get("origin"), 3, 8.0F),
                        axis.toLowerCase(java.util.Locale.ROOT),
                        floatOr(r.get("angle"), 0.0F),
                        booleanOr(r.get("rescale"), false));
            }
        }

        return new Element(from, to, faces, rotation, booleanOr(object.get("shade"), true));
    }

    // ---------------------------------------------------------------- helpers

    /** Model and texture references default to the {@code minecraft} namespace. */
    public static String namespaced(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        return id.indexOf(':') >= 0 ? id : "minecraft:" + id;
    }

    private static String string(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        try {
            return element.getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static float[] floats(JsonElement element, int size, float fallback) {
        float[] out = new float[size];
        java.util.Arrays.fill(out, fallback);
        if (element == null || !element.isJsonArray()) {
            return out;
        }
        JsonArray array = element.getAsJsonArray();
        for (int i = 0; i < size && i < array.size(); i++) {
            out[i] = floatOr(array.get(i), fallback);
        }
        return out;
    }

    private static float floatOr(JsonElement element, float fallback) {
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsFloat();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static int intOr(JsonElement element, int fallback) {
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsInt();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static boolean booleanOr(JsonElement element, boolean fallback) {
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsBoolean();
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
