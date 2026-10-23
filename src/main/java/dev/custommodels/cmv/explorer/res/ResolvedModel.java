package dev.custommodels.cmv.explorer.res;

import java.util.List;
import java.util.Map;

/**
 * A model after its parent chain, texture variables and display transforms have
 * been flattened into one self-contained description.
 *
 * <p>Everything the renderer and the information panel need is here, and
 * nothing here needs re-resolving, which is what makes resolution a one-off
 * cost per model rather than a per-frame one.</p>
 *
 * @param modelId        e.g. {@code example:item/bows/dragon_bow}
 * @param archivePath    e.g. {@code assets/example/models/item/bows/dragon_bow.json}
 * @param sourcePackId   the pack the model file itself came from
 * @param exists         false when the model referenced by an item definition
 *                       has no file behind it
 * @param parentChain    model ids from this model's direct parent upward, in
 *                       order; empty when the model has no parent
 * @param textureSlots   slot name to fully resolved texture id, e.g.
 *                       {@code layer0 -> example:item/bows/dragon_bow}
 * @param missingTextures texture ids referenced but not present in any pack
 * @param unresolvedSlots slot references such as {@code #missing} that never
 *                       resolved to a texture id
 * @param translucentSlots slots declared with {@code force_translucent}, a 26.x
 *                       spelling used by 110 vanilla models
 * @param geometryModelId the model whose file declares the geometry — this one
 *                       or the nearest ancestor that has an {@code elements}
 *                       block. Empty for sprite models and for models with no
 *                       geometry at all
 * @param elementCount   how many cuboids that file declares
 * @param geometryHash   identity of the shape, computed while the file was open
 * @param generated      true when the chain reaches {@code builtin/generated},
 *                       meaning geometry is extruded from the layer textures
 * @param layers         for generated models, the {@code layerN} texture ids in
 *                       order
 * @param transforms     display transforms by name, e.g. {@code gui}
 * @param guiLightFront  true when {@code gui_light} resolved to {@code front}
 * @param warnings       problems found while resolving, shown in debug mode
 */
public record ResolvedModel(String modelId,
                            String archivePath,
                            String sourcePackId,
                            boolean exists,
                            List<String> parentChain,
                            Map<String, String> textureSlots,
                            List<String> missingTextures,
                            List<String> unresolvedSlots,
                            java.util.Set<String> translucentSlots,
                            String geometryModelId,
                            int elementCount,
                            String geometryHash,
                            boolean generated,
                            List<String> layers,
                            Map<String, ModelJson.Transform> transforms,
                            boolean guiLightFront,
                            List<String> warnings) {

    /** The display transform for a context, falling back to identity. */
    public ModelJson.Transform transform(String context) {
        ModelJson.Transform found = transforms.get(context);
        return found == null ? ModelJson.Transform.IDENTITY : found;
    }

    /**
     * True when there is nothing at all to draw.
     *
     * <p>Note that the cuboids themselves are deliberately <em>not</em> held
     * here. Retaining them for every model in a pack cost 623 MB on a
     * ten-thousand-model pack — 62 KB each, since a single detailed model can
     * carry a thousand faces — which is what made the viewer unusable on a
     * laptop. Only the count, the identity and where to find them are kept; the
     * mesh builder reads the file again for the handful of models actually
     * being drawn, and the resulting meshes are cached and bounded.</p>
     */
    public boolean isEmpty() {
        return elementCount == 0 && layers.isEmpty();
    }

    /** True when the geometry has to be read back from a file to be drawn. */
    public boolean hasCuboidGeometry() {
        return elementCount > 0 && !geometryModelId.isEmpty();
    }

    /** Distinct texture ids used, in a stable order, for the detail panel. */
    public List<String> distinctTextures() {
        return textureSlots.values().stream().distinct().sorted().toList();
    }
}
