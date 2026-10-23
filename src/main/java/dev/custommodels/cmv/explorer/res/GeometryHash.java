package dev.custommodels.cmv.explorer.res;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/**
 * Identity of a model's shape, computed while its file is open.
 *
 * <p>This used to be done later, from the cuboids retained on every
 * {@link ResolvedModel}. Retaining them was costing 623 MB on a
 * ten-thousand-model pack, so the shape is reduced to sixteen characters at the
 * one moment the data is already in hand and the cuboids are then let go.</p>
 */
public final class GeometryHash {

    private static final ThreadLocal<MessageDigest> SHA1 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    });

    private GeometryHash() {
    }

    /** Everything about a model that decides what silhouette it draws. */
    public static String of(java.util.List<ModelJson.Element> elements,
                            boolean generated,
                            int layerCount,
                            Map<String, ModelJson.Transform> transforms) {
        StringBuilder builder = new StringBuilder(256);
        if (generated) {
            builder.append("generated:").append(layerCount);
        }
        for (ModelJson.Element element : elements) {
            builder.append('e');
            append(builder, element.from());
            append(builder, element.to());
            if (element.rotation() != null) {
                builder.append('r').append(element.rotation().axis())
                        .append(element.rotation().angle());
                append(builder, element.rotation().origin());
            }
            for (Map.Entry<String, ModelJson.Face> face : element.faces().entrySet()) {
                builder.append('f').append(face.getKey())
                        .append(face.getValue().texture())
                        .append(face.getValue().rotation())
                        .append(face.getValue().tintIndex());
                if (face.getValue().uv() != null) {
                    append(builder, face.getValue().uv());
                }
            }
        }
        for (Map.Entry<String, ModelJson.Transform> transform : transforms.entrySet()) {
            builder.append('t').append(transform.getKey());
            append(builder, transform.getValue().rotation());
            append(builder, transform.getValue().translation());
            append(builder, transform.getValue().scale());
        }
        return digest(builder.toString());
    }

    private static void append(StringBuilder builder, float[] values) {
        for (float value : values) {
            builder.append(value).append(',');
        }
    }

    private static String digest(String input) {
        try {
            byte[] bytes = SHA1.get().digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(Character.forDigit((bytes[i] >> 4) & 0xF, 16));
                hex.append(Character.forDigit(bytes[i] & 0xF, 16));
            }
            return hex.toString();
        } catch (RuntimeException e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
