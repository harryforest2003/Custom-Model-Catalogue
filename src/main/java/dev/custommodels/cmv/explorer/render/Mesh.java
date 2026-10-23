package dev.custommodels.cmv.explorer.render;

import dev.custommodels.cmv.explorer.res.ResourceSource;

import java.util.List;

/**
 * Renderer-ready geometry: flat arrays of quads with the textures they sample.
 *
 * <p>Built once per model and then reused for every frame and every rotation
 * angle. Nothing here references JSON, the resource manager or Minecraft, so a
 * mesh is safe to hold across resource reloads and safe to rasterize from any
 * thread.</p>
 *
 * <p>Positions are in Minecraft model units, where a block spans 0..16 on each
 * axis. Texture coordinates are normalised 0..1 against the quad's own texture,
 * so a quad does not care how large its texture is.</p>
 *
 * @param positions  {@code 12 * quadCount} floats: four xyz corners per quad,
 *                   ordered top-left, bottom-left, bottom-right, top-right as
 *                   seen from the face's outward side
 * @param uvs        {@code 8 * quadCount} floats matching the corner order
 * @param textureIds {@code quadCount} indices into {@link #textures}
 * @param shades     {@code quadCount} baked face brightnesses, 0..1
 * @param textures   the distinct images this mesh samples
 * @param min        lower corner of the geometry's bounding box
 * @param max        upper corner of the geometry's bounding box
 */
public record Mesh(float[] positions,
                   float[] uvs,
                   int[] textureIds,
                   float[] shades,
                   List<ResourceSource.Image> textures,
                   float[] min,
                   float[] max) {

    public int quadCount() {
        return textureIds.length;
    }

    public boolean isEmpty() {
        return textureIds.length == 0;
    }

    /** Centre of the bounding box, the point previews rotate about. */
    public float[] center() {
        return new float[]{
                (min[0] + max[0]) * 0.5F,
                (min[1] + max[1]) * 0.5F,
                (min[2] + max[2]) * 0.5F};
    }

    /** Longest bounding-box edge, used to fit the model to the viewport. */
    public float extent() {
        float dx = max[0] - min[0];
        float dy = max[1] - min[1];
        float dz = max[2] - min[2];
        return Math.max(0.001F, Math.max(dx, Math.max(dy, dz)));
    }

    /**
     * Rough memory cost of the geometry alone, for bounding the mesh cache.
     *
     * <p>Deliberately excludes the textures. They are held in a cache shared by
     * every mesh, so charging each mesh for the full size of images it merely
     * points at counted the same megabyte dozens of times. On a pack with large
     * textures that made the mesh cache believe it was full after a handful of
     * models and evict continuously, so meshes were rebuilt over and over —
     * which measured as the single largest cost in drawing a grid.</p>
     */
    public int approximateBytes() {
        return positions.length * 4 + uvs.length * 4
                + textureIds.length * 4 + shades.length * 4;
    }
}
