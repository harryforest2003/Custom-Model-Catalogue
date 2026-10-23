package dev.custommodels.cmv.explorer.render;

import dev.custommodels.cmv.explorer.res.ModelJson;
import dev.custommodels.cmv.explorer.res.ModelResolver;
import dev.custommodels.cmv.explorer.res.ResolvedModel;
import dev.custommodels.cmv.explorer.res.ResourceSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a {@link ResolvedModel} into a {@link Mesh}.
 *
 * <h2>Cuboid models</h2>
 *
 * <p>Each element becomes up to six quads. Corner order and default UVs follow
 * Minecraft's own rules: a face's first UV corner is its top-left as seen from
 * outside, and when a face omits {@code uv} the coordinates are derived from
 * the cuboid's extent on the two axes the face spans. The derived values match
 * vanilla exactly, which is the cross-check that the corner ordering below is
 * the right way round rather than mirrored.</p>
 *
 * <h2>Sprite models</h2>
 *
 * <p>A model whose parent chain reaches {@code builtin/generated} has no
 * geometry of its own; Minecraft extrudes it from the {@code layerN} textures
 * into a slab one unit thick centred at z = 8. The front and back faces are
 * trivial. The sides are the interesting part: they exist only where the
 * texture's alpha changes, which is what gives a sword a silhouette instead of
 * a rectangle. Runs of adjacent edge texels are merged into single quads, so a
 * high-resolution pack costs the same as a 16x16 one to draw.</p>
 */
public final class MeshBuilder {

    /**
     * Cap on the silhouette resolution used to extrude sprite sides.
     *
     * <p>Halved from 64. The silhouette only decides where the one-pixel-thick
     * sides of a flat item are, which at preview sizes is a handful of screen
     * pixels; the front and back still use the texture at full resolution. The
     * scan is quadratic in this number, so 32 is a quarter of the work for a
     * difference that does not survive being drawn.</p>
     */
    private static final int MAX_SILHOUETTE = 32;

    /** Vanilla's per-face brightness for elements with {@code shade: true}. */
    private static float faceShade(String face) {
        return switch (face) {
            case "down" -> 0.5F;
            case "up" -> 1.0F;
            case "north", "south" -> 0.8F;
            default -> 0.6F;
        };
    }

    private final ResourceSource source;
    private final Map<String, ResourceSource.Image> textureCache;
    private final ModelResolver resolver;

    /**
     * Textures this build asked for and could not have, because reading them
     * here was not allowed.
     *
     * <p>Reported rather than swallowed. A model may name a texture directly on
     * a face instead of through a {@code #slot}, and those ids are not in
     * {@link ResolvedModel#distinctTextures()} — so whatever prefetches for
     * this builder cannot know the full set in advance. It finds out from
     * here, and the half-built mesh is thrown away rather than cached, which is
     * what used to make a missing texture permanent.</p>
     */
    private final java.util.Set<String> unavailable = new java.util.LinkedHashSet<>();

    /** Texture ids the build needed but could not read on this thread. */
    public java.util.Set<String> unavailableTextures() {
        return unavailable;
    }

    /**
     * @param textureCache shared across models so that a texture used by fifty
     *                     models is decoded once, not fifty times
     * @param resolver     asked for the cuboids, which resolved models no
     *                     longer carry — see {@link ResolvedModel#isEmpty()}
     */
    public MeshBuilder(ResourceSource source,
                       Map<String, ResourceSource.Image> textureCache,
                       ModelResolver resolver) {
        this.source = source;
        this.textureCache = textureCache;
        this.resolver = resolver;
    }

    /** Builds a mesh, or an empty one when nothing can be drawn. */
    public Mesh build(ResolvedModel model) {
        Builder builder = new Builder();
        List<ModelJson.Element> elements = model.hasCuboidGeometry() && resolver != null
                ? resolver.loadElements(model) : List.of();
        if (!elements.isEmpty()) {
            for (ModelJson.Element element : elements) {
                addElement(builder, element, model);
            }
        } else if (model.generated()) {
            for (String layer : model.layers()) {
                addSpriteLayer(builder, layer);
            }
        }
        return builder.finish();
    }

    // ------------------------------------------------------------- cuboids

    private void addElement(Builder builder, ModelJson.Element element, ResolvedModel model) {
        float x1 = element.from()[0], y1 = element.from()[1], z1 = element.from()[2];
        float x2 = element.to()[0], y2 = element.to()[1], z2 = element.to()[2];

        float[][] rotation = rotationMatrix(element.rotation());
        float[] origin = element.rotation() == null
                ? new float[]{8, 8, 8} : element.rotation().origin();

        for (Map.Entry<String, ModelJson.Face> entry : element.faces().entrySet()) {
            String direction = entry.getKey().toLowerCase(java.util.Locale.ROOT);
            ModelJson.Face face = entry.getValue();

            ResourceSource.Image image = resolveFaceTexture(face.texture(), model);
            if (image == null) {
                continue;
            }

            float[] corners = faceCorners(direction, x1, y1, z1, x2, y2, z2);
            if (corners == null) {
                continue;
            }
            float[] uv = face.uv() != null
                    ? face.uv()
                    : defaultUv(direction, x1, y1, z1, x2, y2, z2);

            if (rotation != null) {
                for (int i = 0; i < 4; i++) {
                    rotatePoint(corners, i * 3, rotation, origin,
                            element.rotation().rescale(), element.rotation().axis());
                }
            }

            float shade = element.shade() ? faceShade(direction) : 1.0F;
            builder.add(corners, uvCorners(uv, face.rotation(), frameScale(image)),
                    image, shade);
        }
    }

    /**
     * The four corners of a face, in the order top-left, bottom-left,
     * bottom-right, top-right as seen from outside the cuboid.
     */
    private static float[] faceCorners(String direction,
                                       float x1, float y1, float z1,
                                       float x2, float y2, float z2) {
        return switch (direction) {
            // Viewed from +Z: +X runs right, +Y runs up.
            case "south" -> new float[]{x1, y2, z2, x1, y1, z2, x2, y1, z2, x2, y2, z2};
            // Viewed from -Z: +X runs left.
            case "north" -> new float[]{x2, y2, z1, x2, y1, z1, x1, y1, z1, x1, y2, z1};
            // Viewed from +X: +Z runs left.
            case "east" -> new float[]{x2, y2, z2, x2, y1, z2, x2, y1, z1, x2, y2, z1};
            // Viewed from -X: +Z runs right.
            case "west" -> new float[]{x1, y2, z1, x1, y1, z1, x1, y1, z2, x1, y2, z2};
            // Viewed from above, looking down: -Z is towards the top of the image.
            case "up" -> new float[]{x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1};
            case "down" -> new float[]{x1, y1, z2, x1, y1, z1, x2, y1, z1, x2, y1, z2};
            default -> null;
        };
    }

    /**
     * Minecraft's derived UVs when a face omits them. Kept identical to vanilla
     * because it is also the proof that {@link #faceCorners} is not mirrored:
     * the two have to agree corner for corner.
     */
    private static float[] defaultUv(String direction,
                                     float x1, float y1, float z1,
                                     float x2, float y2, float z2) {
        return switch (direction) {
            case "south" -> new float[]{x1, 16 - y2, x2, 16 - y1};
            case "north" -> new float[]{16 - x2, 16 - y2, 16 - x1, 16 - y1};
            case "east" -> new float[]{16 - z2, 16 - y2, 16 - z1, 16 - y1};
            case "west" -> new float[]{z1, 16 - y2, z2, 16 - y1};
            case "up" -> new float[]{x1, z1, x2, z2};
            case "down" -> new float[]{x1, 16 - z2, x2, 16 - z1};
            default -> new float[]{0, 0, 16, 16};
        };
    }

    /**
     * Expands a {@code [u1,v1,u2,v2]} rectangle into four normalised corners
     * matching the position corner order, then applies the face's UV rotation.
     *
     * @param frameScale how much of the texture's height is one animation
     *                   frame, 1 for the usual still texture. A model's UVs are
     *                   written against a single frame, so on an animated strip
     *                   they have to be squeezed into the first one rather than
     *                   stretched down the whole file
     */
    private static float[] uvCorners(float[] uv, int rotationDegrees, float frameScale) {
        float u1 = uv[0] / 16.0F, v1 = uv[1] / 16.0F * frameScale;
        float u2 = uv[2] / 16.0F, v2 = uv[3] / 16.0F * frameScale;
        float[] corners = {u1, v1, u1, v2, u2, v2, u2, v1};

        int steps = Math.floorMod(rotationDegrees / 90, 4);
        if (steps == 0) {
            return corners;
        }
        float[] rotated = new float[8];
        for (int i = 0; i < 4; i++) {
            int from = (i + steps) % 4;
            rotated[i * 2] = corners[from * 2];
            rotated[i * 2 + 1] = corners[from * 2 + 1];
        }
        return rotated;
    }

    // ----------------------------------------------------------- rotations

    private static float[][] rotationMatrix(ModelJson.Rotation rotation) {
        if (rotation == null || rotation.angle() == 0.0F) {
            return null;
        }
        double radians = Math.toRadians(rotation.angle());
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        return switch (rotation.axis()) {
            case "x" -> new float[][]{{1, 0, 0}, {0, cos, -sin}, {0, sin, cos}};
            case "y" -> new float[][]{{cos, 0, sin}, {0, 1, 0}, {-sin, 0, cos}};
            case "z" -> new float[][]{{cos, -sin, 0}, {sin, cos, 0}, {0, 0, 1}};
            default -> null;
        };
    }

    /**
     * Rotates one corner about the element origin. {@code rescale} grows the
     * element so that a rotated face still meets its neighbours, which is what
     * stops 22.5 degree elements from leaving gaps.
     */
    private static void rotatePoint(float[] points, int offset, float[][] matrix,
                                    float[] origin, boolean rescale, String axis) {
        float x = points[offset] - origin[0];
        float y = points[offset + 1] - origin[1];
        float z = points[offset + 2] - origin[2];

        float rx = matrix[0][0] * x + matrix[0][1] * y + matrix[0][2] * z;
        float ry = matrix[1][0] * x + matrix[1][1] * y + matrix[1][2] * z;
        float rz = matrix[2][0] * x + matrix[2][1] * y + matrix[2][2] * z;

        if (rescale) {
            // Vanilla's factor: 1 / cos(angle), applied on the two axes the
            // rotation sweeps through.
            float scale = 1.0F / (float) Math.cos(Math.toRadians(22.5)) ;
            switch (axis) {
                case "x" -> { ry *= scale; rz *= scale; }
                case "y" -> { rx *= scale; rz *= scale; }
                default -> { rx *= scale; ry *= scale; }
            }
        }

        points[offset] = rx + origin[0];
        points[offset + 1] = ry + origin[1];
        points[offset + 2] = rz + origin[2];
    }

    // --------------------------------------------------------- sprite items

    private void addSpriteLayer(Builder builder, String textureId) {
        ResourceSource.Image image = texture(textureId);
        if (image == null) {
            return;
        }
        int w = image.width();
        int h = spriteFrameHeight(image);
        if (w <= 0 || h <= 0) {
            return;
        }
        // Animated textures store frames stacked vertically; only the first is
        // shown, so the v range is narrowed rather than the whole strip drawn.
        float vMax = h / (float) image.height();

        // Front and back of the one-unit-thick slab.
        builder.add(new float[]{0, 16, 8.5F, 0, 0, 8.5F, 16, 0, 8.5F, 16, 16, 8.5F},
                new float[]{0, 0, 0, vMax, 1, vMax, 1, 0}, image, 1.0F);
        builder.add(new float[]{16, 16, 7.5F, 16, 0, 7.5F, 0, 0, 7.5F, 0, 16, 7.5F},
                new float[]{1, 0, 1, vMax, 0, vMax, 0, 0}, image, 1.0F);

        addSpriteEdges(builder, image, w, h, vMax);
    }

    /**
     * Extrudes the sides of a sprite wherever its alpha silhouette has an edge.
     *
     * <p>Sampling is capped at {@link #MAX_SILHOUETTE} steps per axis. A 512x512
     * pack texture would otherwise produce tens of thousands of edge quads for a
     * preview a few dozen pixels wide, which costs a great deal and shows
     * nothing. The front and back faces still use the texture at full
     * resolution, so no detail is lost where it is actually visible.</p>
     */
    private void addSpriteEdges(Builder builder, ResourceSource.Image image,
                                int width, int height, float vMax) {
        int cols = Math.min(width, MAX_SILHOUETTE);
        int rows = Math.min(height, MAX_SILHOUETTE);
        boolean[] solid = new boolean[cols * rows];
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int sx = (int) ((col + 0.5F) * width / cols);
                int sy = (int) ((row + 0.5F) * height / rows);
                solid[row * cols + col] = (image.pixel(sx, sy) >>> 24) >= 16;
            }
        }

        float cellW = 16.0F / cols;
        float cellH = 16.0F / rows;
        float front = 8.5F, back = 7.5F;

        // Vertical edges: scan each column boundary, merging runs down a column.
        for (int col = 0; col <= cols; col++) {
            int runStart = -1;
            boolean runFacesEast = false;
            for (int row = 0; row <= rows; row++) {
                boolean left = col > 0 && row < rows && solid[row * cols + col - 1];
                boolean right = col < cols && row < rows && solid[row * cols + col];
                boolean edge = left != right;
                boolean facesEast = left;
                if (edge && runStart >= 0 && facesEast == runFacesEast) {
                    continue;
                }
                if (runStart >= 0) {
                    emitVerticalEdge(builder, image, col, runStart, row,
                            cellW, cellH, front, back, runFacesEast, width, height, vMax);
                    runStart = -1;
                }
                if (edge) {
                    runStart = row;
                    runFacesEast = facesEast;
                }
            }
        }

        // Horizontal edges: scan each row boundary, merging runs across a row.
        for (int row = 0; row <= rows; row++) {
            int runStart = -1;
            boolean runFacesUp = false;
            for (int col = 0; col <= cols; col++) {
                boolean above = row > 0 && col < cols && solid[(row - 1) * cols + col];
                boolean below = row < rows && col < cols && solid[row * cols + col];
                boolean edge = above != below;
                boolean facesUp = below;
                if (edge && runStart >= 0 && facesUp == runFacesUp) {
                    continue;
                }
                if (runStart >= 0) {
                    emitHorizontalEdge(builder, image, row, runStart, col,
                            cellW, cellH, front, back, runFacesUp, width, height, vMax);
                    runStart = -1;
                }
                if (edge) {
                    runStart = col;
                    runFacesUp = facesUp;
                }
            }
        }
    }

    private void emitVerticalEdge(Builder builder, ResourceSource.Image image,
                                  int col, int rowFrom, int rowTo,
                                  float cellW, float cellH, float front, float back,
                                  boolean facesEast, int width, int height, float vMax) {
        float x = col * cellW;
        float yTop = 16.0F - rowFrom * cellH;
        float yBottom = 16.0F - rowTo * cellH;

        // Sample the texture column just inside the solid side, so the edge
        // takes its colour from the pixels it belongs to.
        int sampleCol = facesEast ? col - 1 : col;
        float u = (sampleCol + 0.5F) / (float) Math.min(width, MAX_SILHOUETTE);
        float v0 = rowFrom / (float) Math.min(height, MAX_SILHOUETTE) * vMax;
        float v1 = rowTo / (float) Math.min(height, MAX_SILHOUETTE) * vMax;

        float[] corners = facesEast
                ? new float[]{x, yTop, front, x, yBottom, front, x, yBottom, back, x, yTop, back}
                : new float[]{x, yTop, back, x, yBottom, back, x, yBottom, front, x, yTop, front};
        builder.add(corners, new float[]{u, v0, u, v1, u, v1, u, v0}, image, 0.6F);
    }

    private void emitHorizontalEdge(Builder builder, ResourceSource.Image image,
                                    int row, int colFrom, int colTo,
                                    float cellW, float cellH, float front, float back,
                                    boolean facesUp, int width, int height, float vMax) {
        float y = 16.0F - row * cellH;
        float xLeft = colFrom * cellW;
        float xRight = colTo * cellW;

        int sampleRow = facesUp ? row : row - 1;
        float v = (sampleRow + 0.5F) / (float) Math.min(height, MAX_SILHOUETTE) * vMax;
        float u0 = colFrom / (float) Math.min(width, MAX_SILHOUETTE);
        float u1 = colTo / (float) Math.min(width, MAX_SILHOUETTE);

        float[] corners = facesUp
                ? new float[]{xLeft, y, back, xLeft, y, front, xRight, y, front, xRight, y, back}
                : new float[]{xLeft, y, front, xLeft, y, back, xRight, y, back, xRight, y, front};
        builder.add(corners, new float[]{u0, v, u0, v, u1, v, u1, v}, image,
                facesUp ? 1.0F : 0.5F);
    }

    /**
     * Height of one animation frame.
     *
     * <p>Carried on the image, decided when the texture was loaded. It used to
     * be read here, from the {@code .mcmeta} sidecar — which meant opening a
     * pack file while building a mesh, on a preview worker, which is exactly
     * the thing that was putting missing-texture squares on the game's own
     * lazily-loaded textures.</p>
     */
    /**
     * The fraction of a texture's height that one animation frame occupies.
     *
     * <p>An animated texture is a vertical strip of frames, and a face's UVs
     * are written against one frame. Mapping them across the whole file made
     * every face on such a model sample a sliver of the wrong rows — the pack
     * this was found on has a 64x1024 pickaxe texture, sixteen frames, and the
     * model drew as a scatter of diagonal streaks rather than a pickaxe.</p>
     */
    private static float frameScale(ResourceSource.Image image) {
        if (image == null || image.height() <= 0) {
            return 1.0F;
        }
        return Math.min(1.0F, image.frameHeight() / (float) image.height());
    }

    private static int spriteFrameHeight(ResourceSource.Image image) {
        return Math.max(1, Math.min(image.frameHeight(), image.height()));
    }

    // ----------------------------------------------------------- textures

    /** Resolves a face's {@code #slot} reference against the model's slots. */
    private ResourceSource.Image resolveFaceTexture(String reference, ResolvedModel model) {
        String textureId;
        if (reference.startsWith("#")) {
            textureId = model.textureSlots().get(reference.substring(1));
        } else {
            textureId = ModelJson.namespaced(reference);
        }
        return textureId == null ? null : texture(textureId);
    }

    private ResourceSource.Image texture(String textureId) {
        ResourceSource.Image cached = textureCache.get(textureId);
        if (cached != null) {
            return cached == MISSING ? null : cached;
        }
        if (!source.canLoadHere()) {
            // Reading a pack from this thread is not allowed. The miss is
            // deliberately not cached — the texture is not missing, it is
            // merely not here yet — and it is recorded so the caller can fetch
            // it on a thread that may, and build again.
            unavailable.add(textureId);
            return null;
        }
        ResourceSource.Image loaded = load(textureId);
        if (loaded == null) {
            // The id as written found nothing. Some packs file a texture under
            // a shorter namespace than their models ask for; see
            // ResourceSource.alternativeTextureIds, which is empty for every
            // ordinary id and so costs nothing to consult.
            for (String alternative : ResourceSource.alternativeTextureIds(textureId)) {
                loaded = load(alternative);
                if (loaded != null) {
                    break;
                }
            }
        }
        // Cached under the id the model asked for, whichever one answered, so
        // the search happens once per texture rather than once per face.
        textureCache.put(textureId, loaded == null ? MISSING : loaded);
        return loaded;
    }

    private ResourceSource.Image load(String textureId) {
        try {
            return source.loadTexture(textureId);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Sentinel so that a missing texture is not looked up over and over. */
    /** Shared marker meaning "looked for and not there", so it is looked for once. */
    public static ResourceSource.Image missing() {
        return MISSING;
    }

    private static final ResourceSource.Image MISSING =
            new ResourceSource.Image(0, 0, new int[0]);

    // ------------------------------------------------------------- builder

    private static final class Builder {

        private final List<float[]> positions = new ArrayList<>();
        private final List<float[]> uvs = new ArrayList<>();
        private final List<Integer> textureIds = new ArrayList<>();
        private final List<Float> shades = new ArrayList<>();
        private final Map<ResourceSource.Image, Integer> textureIndex = new LinkedHashMap<>();

        private final float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        private final float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};

        void add(float[] corners, float[] uv, ResourceSource.Image image, float shade) {
            if (image == null || image.width() == 0) {
                return;
            }
            int index = textureIndex.computeIfAbsent(image, key -> textureIndex.size());
            positions.add(corners);
            uvs.add(uv);
            textureIds.add(index);
            shades.add(shade);
            for (int i = 0; i < 4; i++) {
                for (int axis = 0; axis < 3; axis++) {
                    float value = corners[i * 3 + axis];
                    min[axis] = Math.min(min[axis], value);
                    max[axis] = Math.max(max[axis], value);
                }
            }
        }

        Mesh finish() {
            int count = textureIds.size();
            if (count == 0) {
                return new Mesh(new float[0], new float[0], new int[0], new float[0],
                        List.of(), new float[]{0, 0, 0}, new float[]{16, 16, 16});
            }
            float[] positionArray = new float[count * 12];
            float[] uvArray = new float[count * 8];
            int[] textureArray = new int[count];
            float[] shadeArray = new float[count];
            for (int i = 0; i < count; i++) {
                System.arraycopy(positions.get(i), 0, positionArray, i * 12, 12);
                System.arraycopy(uvs.get(i), 0, uvArray, i * 8, 8);
                textureArray[i] = textureIds.get(i);
                shadeArray[i] = shades.get(i);
            }
            return new Mesh(positionArray, uvArray, textureArray, shadeArray,
                    List.copyOf(textureIndex.keySet()), min, max);
        }
    }
}
