package dev.custommodels.cmv.explorer.render;

import dev.custommodels.cmv.explorer.res.ModelJson;
import dev.custommodels.cmv.explorer.res.ResourceSource;

import java.util.Arrays;

/**
 * A small orthographic, z-buffered software rasterizer for {@link Mesh}es.
 *
 * <h2>Why software</h2>
 *
 * <p>The obvious alternative is to submit the model through Minecraft's own
 * item pipeline, but 26.2's GUI layer is two dimensional: {@code pose()} is a
 * {@code Matrix3x2fStack}, and an item reaches the screen as a
 * {@code GuiItemRenderState} carrying a 2D transform. There is no supported way
 * to hand it an arbitrary 3D rotation, which a model inspector needs.</p>
 *
 * <p>Rasterizing on the CPU instead buys several things that matter more here
 * than raw speed: it runs entirely off the client thread, it allocates no GPU
 * memory beyond a single upload of the finished image, it produces the pixels
 * that "Copy Image" and the animated export need anyway, and a finished frame
 * can be cached and re-blitted for nothing. A 48x48 card preview is about two
 * thousand pixels of work; the expensive part of a preview is decoding the
 * texture, and that is cached and shared.</p>
 *
 * <h2>Camera</h2>
 *
 * <p>The camera looks along -Z, so +X is right, +Y is up, and larger Z is
 * nearer. Projection is orthographic because that is how Minecraft draws items
 * in the GUI; a perspective camera would make the preview subtly unlike the
 * game.</p>
 */
public final class Rasterizer {

    private Rasterizer() {
    }

    /** Alpha below this is discarded outright rather than blended. */
    private static final int ALPHA_CUTOFF = 8;

    /** Alpha at or above this counts as opaque and writes depth. */
    private static final int ALPHA_OPAQUE = 250;

    /**
     * Everything that decides what one frame looks like.
     *
     * @param width            output width in pixels
     * @param height           output height in pixels
     * @param supersample      1 for no antialiasing, 2 or 3 for smoother edges
     * @param yaw              viewer rotation about Y, in degrees
     * @param pitch            viewer rotation about X, in degrees
     * @param zoom             1.0 fits the model to the frame
     * @param displayTransform the model's own transform for the display context
     *                         being previewed, normally {@code gui}
     * @param frontLit         true when the model's {@code gui_light} is
     *                         {@code front}, which is the case for every sprite
     *                         item and anything descended from
     *                         {@code item/generated}
     * @param background       ARGB fill; use 0 for a transparent image
     */
    public record View(int width,
                       int height,
                       int supersample,
                       float yaw,
                       float pitch,
                       float zoom,
                       ModelJson.Transform displayTransform,
                       boolean frontLit,
                       int background,
                       int tick) {

        /** A still: whatever animated textures the model has show frame one. */
        public View(int width, int height, int supersample, float yaw, float pitch,
                    float zoom, ModelJson.Transform displayTransform, boolean frontLit,
                    int background) {
            this(width, height, supersample, yaw, pitch, zoom, displayTransform,
                    frontLit, background, 0);
        }

        public View withAngle(float newYaw, float newPitch) {
            return new View(width, height, supersample, newYaw, newPitch, zoom,
                    displayTransform, frontLit, background, tick);
        }

        /** The same view at another moment of its textures' animations. */
        public View withTick(int newTick) {
            return new View(width, height, supersample, yaw, pitch, zoom,
                    displayTransform, frontLit, background, newTick);
        }

        public View withSize(int newWidth, int newHeight, int newSupersample) {
            // Carrying the tick, like withAngle and withTick. It did not, and
            // resizing a view silently sent its animations back to frame one -
            // the same mistake that made the animation export a slideshow of
            // one texture frame, and the reason that one went unnoticed is
            // that nothing calls this yet.
            return new View(newWidth, newHeight, newSupersample, yaw, pitch, zoom,
                    displayTransform, frontLit, background, tick);
        }
    }

    /**
     * Working buffers, one set per worker thread.
     *
     * <p>A render used to allocate its colour buffer, depth buffer, projected
     * vertices and a boxed index array every time. At a few hundred renders per
     * screenful that is megabytes of short-lived garbage for no reason: the
     * buffers are the same size every time and nothing outside a single render
     * looks at them. Only the returned image is freshly allocated, because the
     * caller keeps it.</p>
     */
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private static final class Scratch {
        int[] colour = new int[0];
        float[] depth = new float[0];
        float[] projected = new float[0];
        float[] item = new float[0];
        float[] brightness = new float[0];
        /** Quad indices packed with their depth, so sorting needs no boxing. */
        long[] order = new long[0];

        int[] colour(int size) {
            if (colour.length < size) {
                colour = new int[size];
            }
            return colour;
        }

        float[] depth(int size) {
            if (depth.length < size) {
                depth = new float[size];
            }
            return depth;
        }

        float[] projected(int size) {
            if (projected.length < size) {
                projected = new float[size];
            }
            return projected;
        }

        float[] item(int size) {
            if (item.length < size) {
                item = new float[size];
            }
            return item;
        }

        float[] brightness(int size) {
            if (brightness.length < size) {
                brightness = new float[size];
            }
            return brightness;
        }

        long[] order(int size) {
            if (order.length < size) {
                order = new long[size];
            }
            return order;
        }
    }

    /** Vanilla's two diffuse light directions, normalised. */
    private static final float[] LIGHT_0 = normalise(0.2F, 1.0F, -0.7F);
    private static final float[] LIGHT_1 = normalise(-0.2F, 1.0F, 0.7F);

    /**
     * Renders one frame.
     *
     * @return {@code width * height} ARGB pixels, row-major
     */
    public static int[] render(Mesh mesh, View view) {
        int scale = Math.max(1, Math.min(4, view.supersample()));
        int w = view.width() * scale;
        int h = view.height() * scale;

        Scratch scratch = SCRATCH.get();
        int pixels = w * h;
        int[] colour = scratch.colour(pixels);
        Arrays.fill(colour, 0, pixels, view.background());
        if (mesh.isEmpty() || w == 0 || h == 0) {
            return downsample(colour, w, h, scale, view);
        }
        float[] depth = scratch.depth(pixels);
        Arrays.fill(depth, 0, pixels, -Float.MAX_VALUE);

        int quads = mesh.quadCount();
        float[] projected = scratch.projected(quads * 12);   // x, y, z per corner
        float[] brightness = scratch.brightness(quads);
        long[] order = scratch.order(quads);

        project(mesh, view, w, h, projected, brightness, order, scratch);

        // Far to near, so that translucent faces blend over what is behind
        // them. The depth buffer still decides opaque overlaps. Depth and index
        // are packed into one long so this is a primitive sort with no boxing.
        Arrays.sort(order, 0, quads);

        for (int index = 0; index < quads; index++) {
            int quad = (int) (order[index] & 0xFFFFFFFFL);
            ResourceSource.Image texture = mesh.textures().get(mesh.textureIds()[quad]);
            if (texture.width() == 0) {
                continue;
            }
            int base = quad * 12;
            int uvBase = quad * 8;
            // Which row of an animated texture this face reads from, worked out
            // once per face. Zero for everything that has a single frame, which
            // is nearly everything.
            int rowOffset = texture.rowOffset(view.tick());
            // Two triangles sharing corner 0: (0,1,2) and (0,2,3).
            triangle(colour, depth, w, h, mesh, texture, brightness[quad],
                    projected, base, uvBase, 0, 1, 2, rowOffset);
            triangle(colour, depth, w, h, mesh, texture, brightness[quad],
                    projected, base, uvBase, 0, 2, 3, rowOffset);
        }
        return downsample(colour, w, h, scale, view);
    }

    // ------------------------------------------------------------ transform

    private static void project(Mesh mesh, View view, int w, int h,
                                float[] out, float[] brightness, long[] order,
                                Scratch scratch) {
        ModelJson.Transform transform = view.displayTransform();
        float yaw = (float) Math.toRadians(view.yaw());
        float pitch = (float) Math.toRadians(view.pitch());
        float cosYaw = (float) Math.cos(yaw), sinYaw = (float) Math.sin(yaw);
        float cosPitch = (float) Math.cos(pitch), sinPitch = (float) Math.sin(pitch);

        // Pass one: model space to item space, so the fit can be measured
        // against what will actually be drawn rather than the raw geometry.
        int quads = mesh.quadCount();
        float[] item = scratch.item(quads * 12);
        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};

        for (int i = 0; i < quads * 4; i++) {
            float x = mesh.positions()[i * 3] - 8.0F;
            float y = mesh.positions()[i * 3 + 1] - 8.0F;
            float z = mesh.positions()[i * 3 + 2] - 8.0F;
            float[] p = applyDisplayTransform(x, y, z, transform);
            item[i * 3] = p[0];
            item[i * 3 + 1] = p[1];
            item[i * 3 + 2] = p[2];
            for (int axis = 0; axis < 3; axis++) {
                min[axis] = Math.min(min[axis], p[axis]);
                max[axis] = Math.max(max[axis], p[axis]);
            }
        }

        float centreX = (min[0] + max[0]) * 0.5F;
        float centreY = (min[1] + max[1]) * 0.5F;
        float centreZ = (min[2] + max[2]) * 0.5F;

        // Fit against the bounding sphere, not the box, so the model does not
        // grow and shrink as it spins.
        float radius = 0.0F;
        for (int i = 0; i < quads * 4; i++) {
            float dx = item[i * 3] - centreX;
            float dy = item[i * 3 + 1] - centreY;
            float dz = item[i * 3 + 2] - centreZ;
            radius = Math.max(radius, dx * dx + dy * dy + dz * dz);
        }
        radius = Math.max(0.001F, (float) Math.sqrt(radius));

        float pixelsPerUnit = (Math.min(w, h) * 0.5F * 0.92F * view.zoom()) / radius;
        float originX = w * 0.5F;
        float originY = h * 0.5F;

        for (int quad = 0; quad < quads; quad++) {
            float sumZ = 0;
            for (int corner = 0; corner < 4; corner++) {
                int i = quad * 4 + corner;
                float x = item[i * 3] - centreX;
                float y = item[i * 3 + 1] - centreY;
                float z = item[i * 3 + 2] - centreZ;

                // Yaw about Y, then pitch about X.
                float rx = cosYaw * x + sinYaw * z;
                float rz = -sinYaw * x + cosYaw * z;
                float ry = cosPitch * y - sinPitch * rz;
                rz = sinPitch * y + cosPitch * rz;

                int o = quad * 12 + corner * 3;
                out[o] = originX + rx * pixelsPerUnit;
                out[o + 1] = originY - ry * pixelsPerUnit;
                out[o + 2] = rz;
                sumZ += rz;
            }
            // Float bits, flipped so that the ordering of the bit pattern
            // matches numeric order for negatives too, then the index in the
            // low half. Sorting the longs sorts by depth, far first.
            int bits = Float.floatToIntBits(sumZ * 0.25F);
            bits = bits < 0 ? ~bits : bits ^ 0x80000000;
            order[quad] = ((long) bits << 32) | quad;
            brightness[quad] = shade(out, quad * 12, mesh.shades()[quad], view.frontLit());
        }
    }

    /**
     * Applies a display transform the way {@code ItemTransform.apply} does:
     * centre, then scale, then rotate X, Y, Z in that order, then translate.
     * Confirmed by reading the method's bytecode out of the 26.2 jar rather
     * than inferring it from the JSON field order, which is different.
     */
    private static float[] applyDisplayTransform(float x, float y, float z,
                                                 ModelJson.Transform transform) {
        float sx = x * transform.scale()[0];
        float sy = y * transform.scale()[1];
        float sz = z * transform.scale()[2];

        float rx = (float) Math.toRadians(transform.rotation()[0]);
        float ry = (float) Math.toRadians(transform.rotation()[1]);
        float rz = (float) Math.toRadians(transform.rotation()[2]);

        // M = Rx * Ry * Rz, matching Quaternionf.rotationXYZ.
        float cz = (float) Math.cos(rz), snz = (float) Math.sin(rz);
        float x1 = cz * sx - snz * sy;
        float y1 = snz * sx + cz * sy;
        float z1 = sz;

        float cy = (float) Math.cos(ry), sny = (float) Math.sin(ry);
        float x2 = cy * x1 + sny * z1;
        float y2 = y1;
        float z2 = -sny * x1 + cy * z1;

        float cx = (float) Math.cos(rx), snx = (float) Math.sin(rx);
        float x3 = x2;
        float y3 = cx * y2 - snx * z2;
        float z3 = snx * y2 + cx * z2;

        return new float[]{
                x3 + transform.translation()[0],
                y3 + transform.translation()[1],
                z3 + transform.translation()[2]};
    }

    /**
     * Per-quad brightness.
     *
     * <p>Side-lit models, which is anything descended from {@code block/block},
     * get Minecraft's baked face constants times its two-light diffuse term.
     * Front-lit models, which is every sprite item and anything descended from
     * {@code item/generated}, are drawn by the game with a light along the view
     * axis, so a flat item in the inventory shows its texture's exact colours.
     * That is reproduced here: at the default angle a front-lit face reaches
     * full brightness, so a preview matches the PNG. Face shading is applied at
     * half strength as the model turns away, which the game does not do — it is
     * a deliberate concession, because an unlit cuboid model rotates into an
     * unreadable silhouette otherwise.</p>
     */
    private static float shade(float[] projected, int base, float bakedShade, boolean frontLit) {
        float ax = projected[base + 3] - projected[base];
        float ay = projected[base + 4] - projected[base + 1];
        float az = projected[base + 5] - projected[base + 2];
        float bx = projected[base + 9] - projected[base];
        float by = projected[base + 10] - projected[base + 1];
        float bz = projected[base + 11] - projected[base + 2];

        // Screen Y points down, so negate it to get a right-handed normal.
        float nx = (-ay) * bz - az * (-by);
        float ny = az * bx - ax * bz;
        float nz = ax * (-by) - (-ay) * bx;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 1.0e-6F) {
            return bakedShade;
        }
        nx /= length;
        ny /= length;
        nz /= length;
        if (nz < 0) {
            nx = -nx;
            ny = -ny;
            nz = -nz;
        }

        if (frontLit) {
            float softened = 1.0F - (1.0F - bakedShade) * 0.5F;
            return clamp(softened * (0.85F + 0.15F * nz));
        }
        float light = Math.max(0, nx * LIGHT_0[0] + ny * LIGHT_0[1] + nz * LIGHT_0[2])
                + Math.max(0, nx * LIGHT_1[0] + ny * LIGHT_1[1] + nz * LIGHT_1[2]);
        return clamp(bakedShade * (0.4F + 0.6F * Math.min(1.0F, light)));
    }

    // ------------------------------------------------------------ triangles

    private static void triangle(int[] colour, float[] depth, int w, int h,
                                 Mesh mesh, ResourceSource.Image texture, float brightness,
                                 float[] projected, int base, int uvBase,
                                 int a, int b, int c, int rowOffset) {
        float ax = projected[base + a * 3], ay = projected[base + a * 3 + 1];
        float bx = projected[base + b * 3], by = projected[base + b * 3 + 1];
        float cx = projected[base + c * 3], cy = projected[base + c * 3 + 1];

        float area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
        if (Math.abs(area) < 1.0e-7F) {
            return;
        }
        float inverseArea = 1.0F / area;

        int minX = Math.max(0, (int) Math.floor(Math.min(ax, Math.min(bx, cx))));
        int maxX = Math.min(w - 1, (int) Math.ceil(Math.max(ax, Math.max(bx, cx))));
        int minY = Math.max(0, (int) Math.floor(Math.min(ay, Math.min(by, cy))));
        int maxY = Math.min(h - 1, (int) Math.ceil(Math.max(ay, Math.max(by, cy))));
        if (minX > maxX || minY > maxY) {
            return;
        }

        float az = projected[base + a * 3 + 2];
        float bz = projected[base + b * 3 + 2];
        float cz = projected[base + c * 3 + 2];
        float au = mesh.uvs()[uvBase + a * 2], av = mesh.uvs()[uvBase + a * 2 + 1];
        float bu = mesh.uvs()[uvBase + b * 2], bv = mesh.uvs()[uvBase + b * 2 + 1];
        float cu = mesh.uvs()[uvBase + c * 2], cv = mesh.uvs()[uvBase + c * 2 + 1];

        int textureWidth = texture.width();
        int textureHeight = texture.height();

        for (int y = minY; y <= maxY; y++) {
            float py = y + 0.5F;
            int row = y * w;
            for (int x = minX; x <= maxX; x++) {
                float px = x + 0.5F;
                float w0 = ((bx - ax) * (py - ay) - (by - ay) * (px - ax)) * inverseArea;
                float w1 = ((px - ax) * (cy - ay) - (py - ay) * (cx - ax)) * inverseArea;
                if (w0 < 0 || w1 < 0 || w0 + w1 > 1) {
                    continue;
                }
                float w2 = 1.0F - w0 - w1;
                // w2 weights a, w1 weights b, w0 weights c.
                float z = w2 * az + w1 * bz + w0 * cz;
                int pixel = row + x;
                if (z <= depth[pixel]) {
                    continue;
                }
                float u = w2 * au + w1 * bu + w0 * cu;
                float v = w2 * av + w1 * bv + w0 * cv;

                int tx = Math.floorMod((int) Math.floor(u * textureWidth), textureWidth);
                int ty = Math.floorMod((int) Math.floor(v * textureHeight), textureHeight);
                // Both are already inside the texture, so the wrap is a compare
                // and a subtract rather than a modulo in the innermost loop.
                ty += rowOffset;
                if (ty >= textureHeight) {
                    ty -= textureHeight;
                }
                int texel = texture.argb()[ty * textureWidth + tx];

                int alpha = texel >>> 24;
                if (alpha < ALPHA_CUTOFF) {
                    continue;
                }
                int shaded = shadePixel(texel, brightness);
                if (alpha >= ALPHA_OPAQUE) {
                    colour[pixel] = shaded;
                    depth[pixel] = z;
                } else {
                    colour[pixel] = blend(shaded, colour[pixel]);
                }
            }
        }
    }

    private static int shadePixel(int argb, float brightness) {
        int r = (int) (((argb >> 16) & 0xFF) * brightness);
        int g = (int) (((argb >> 8) & 0xFF) * brightness);
        int b = (int) ((argb & 0xFF) * brightness);
        return (argb & 0xFF000000) | (Math.min(255, r) << 16)
                | (Math.min(255, g) << 8) | Math.min(255, b);
    }

    /** Standard source-over blend in straight (non-premultiplied) alpha. */
    private static int blend(int source, int destination) {
        int sa = source >>> 24;
        if (sa == 255 || (destination >>> 24) == 0) {
            return source;
        }
        int da = destination >>> 24;
        int outA = sa + da * (255 - sa) / 255;
        if (outA == 0) {
            return 0;
        }
        int r = channel(source, 16, sa, destination, da, outA);
        int g = channel(source, 8, sa, destination, da, outA);
        int b = channel(source, 0, sa, destination, da, outA);
        return (outA << 24) | (r << 16) | (g << 8) | b;
    }

    private static int channel(int source, int shift, int sa, int destination, int da, int outA) {
        int sc = (source >> shift) & 0xFF;
        int dc = (destination >> shift) & 0xFF;
        int value = (sc * sa + dc * da * (255 - sa) / 255) / outA;
        return Math.min(255, Math.max(0, value));
    }

    // ----------------------------------------------------------- downsample

    /** Box filter in premultiplied alpha, so edges do not darken. */
    private static int[] downsample(int[] source, int w, int h, int scale, View view) {
        if (scale == 1) {
            // The working buffer is shared with the next render on this thread,
            // so the caller gets a copy of exactly the pixels it asked for.
            return Arrays.copyOf(source, view.width() * view.height());
        }
        int outWidth = view.width();
        int outHeight = view.height();
        int[] out = new int[outWidth * outHeight];
        int samples = scale * scale;
        for (int y = 0; y < outHeight; y++) {
            for (int x = 0; x < outWidth; x++) {
                long a = 0, r = 0, g = 0, b = 0;
                for (int dy = 0; dy < scale; dy++) {
                    int row = (y * scale + dy) * w;
                    for (int dx = 0; dx < scale; dx++) {
                        int pixel = source[row + x * scale + dx];
                        int pa = pixel >>> 24;
                        a += pa;
                        r += ((pixel >> 16) & 0xFF) * pa;
                        g += ((pixel >> 8) & 0xFF) * pa;
                        b += (pixel & 0xFF) * pa;
                    }
                }
                int outA = (int) (a / samples);
                if (a == 0) {
                    out[y * outWidth + x] = 0;
                    continue;
                }
                out[y * outWidth + x] = (outA << 24)
                        | ((int) (r / a) << 16) | ((int) (g / a) << 8) | (int) (b / a);
            }
        }
        return out;
    }

    private static float clamp(float value) {
        return value < 0 ? 0 : Math.min(value, 1.0F);
    }

    private static float[] normalise(float x, float y, float z) {
        float length = (float) Math.sqrt(x * x + y * y + z * z);
        return new float[]{x / length, y / length, z / length};
    }
}
