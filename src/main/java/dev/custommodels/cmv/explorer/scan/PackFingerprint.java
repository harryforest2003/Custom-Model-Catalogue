package dev.custommodels.cmv.explorer.scan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A cheap identity for "the packs currently loaded, in this order, with this
 * content".
 *
 * <p>This is what decides whether a cached scan can be reused. It has to be
 * both trustworthy and quick, and those pull in opposite directions: hashing
 * every byte of a two-hundred-megabyte pack is trustworthy and takes long
 * enough to defeat the point. So it uses each pack file's size and last
 * modified time, plus the pack list and its order.</p>
 *
 * <p>That misses one case: editing a pack so that the new file has exactly the
 * old size and the old timestamp. Nothing a person does by hand produces that,
 * and the Refresh button exists for the case where something automated did.
 * Reading a whole pack to close that gap would make every open slower for
 * everyone, which is the wrong trade for a viewer.</p>
 */
public final class PackFingerprint {

    /** Spacing between packs, so ordinals never collide across them. */
    private static final int PACK_STRIDE = 1_000_000;

    private final String value;
    private final Map<String, Integer> ordinals;
    private final List<String> packIds;
    private final long totalBytes;

    private PackFingerprint(String value, Map<String, Integer> ordinals,
                            List<String> packIds, long totalBytes) {
        this.value = value;
        this.ordinals = ordinals;
        this.packIds = packIds;
        this.totalBytes = totalBytes;
    }

    /** The fingerprint itself; equal values mean a cached scan is still valid. */
    public String value() {
        return value;
    }

    /** Enabled pack ids in load order, later packs overriding earlier ones. */
    public List<String> packIds() {
        return packIds;
    }

    /** Combined size of the pack files, for the statistics panel. */
    public long totalBytes() {
        return totalBytes;
    }

    /** Load-order position of a pack, used to sort results by first appearance. */
    public int ordinalOf(String packId) {
        return ordinals.getOrDefault(packId, Integer.MAX_VALUE - PACK_STRIDE);
    }

    /**
     * Whether a pack is one the player installed, as opposed to the game's own
     * built-in sources.
     *
     * <p>Minecraft names user packs {@code file/Something.zip}; {@code vanilla},
     * {@code fabric} and mod-supplied packs have no {@code file/} prefix. This
     * is the test that separates "the pack changed this" from "this is how the
     * game already looked".</p>
     */
    public static boolean isUserPack(String packId) {
        return packId != null && packId.startsWith("file/");
    }

    /**
     * Builds a fingerprint for the given packs.
     *
     * @param resourcePacksDir the game's {@code resourcepacks} folder
     * @param enabledPackIds   pack ids in load order, as Minecraft reports them
     */
    public static PackFingerprint build(Path resourcePacksDir, List<String> enabledPackIds) {
        StringBuilder material = new StringBuilder(256);
        Map<String, Integer> ordinals = new LinkedHashMap<>();
        List<String> packs = new ArrayList<>();
        long bytes = 0;

        int index = 0;
        for (String packId : enabledPackIds) {
            if (packId == null || packId.isEmpty()) {
                continue;
            }
            packs.add(packId);
            ordinals.put(packId, ++index * PACK_STRIDE);
            material.append(packId).append('@').append(index).append(';');

            if (!isUserPack(packId) || resourcePacksDir == null) {
                // Built-in sources change only when the game does, and the mod
                // version already covers that.
                continue;
            }
            Path file = resourcePacksDir.resolve(packId.substring("file/".length()))
                    .normalize();
            if (!file.startsWith(resourcePacksDir.normalize())) {
                // A pack id is a name inside the folder, and only that. Reading
                // the size and date of something else because a name contained
                // a "/.." is not a thing this needs to be able to do.
                continue;
            }
            try {
                if (Files.isDirectory(file)) {
                    long[] stats = folderStats(file);
                    material.append("dir:").append(stats[0]).append(':').append(stats[1]);
                    bytes += stats[1];
                } else if (Files.exists(file)) {
                    BasicFileAttributes attributes =
                            Files.readAttributes(file, BasicFileAttributes.class);
                    material.append("zip:").append(attributes.size())
                            .append(':').append(attributes.lastModifiedTime().toMillis());
                    bytes += attributes.size();
                } else {
                    material.append("missing");
                }
            } catch (IOException | RuntimeException e) {
                // An unreadable pack still has an identity: "unreadable".
                material.append("err:").append(e.getClass().getSimpleName());
            }
            material.append(';');
        }
        return new PackFingerprint(digest(material.toString()), ordinals,
                List.copyOf(packs), bytes);
    }

    /**
     * Newest modification time and total size across a folder pack.
     *
     * <p>Folder packs are what pack authors actually work in, and they are
     * edited constantly, so this walk is the case that has to be right. It is
     * also the expensive one, hence the cap: past a hundred thousand files the
     * walk stops and the fingerprint records that it was truncated, which makes
     * it stable rather than wrong.</p>
     */
    private static long[] folderStats(Path root) throws IOException {
        long[] stats = {0L, 0L};
        final int[] visited = {0};
        try (var walk = Files.walk(root)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                if (++visited[0] > 100_000) {
                    stats[0] = -1;
                    break;
                }
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                BasicFileAttributes attributes =
                        Files.readAttributes(path, BasicFileAttributes.class);
                stats[0] = Math.max(stats[0], attributes.lastModifiedTime().toMillis());
                stats[1] += attributes.size();
            }
        }
        return stats;
    }

    private static String digest(String input) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-1");
            byte[] hash = sha.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                hex.append(Character.forDigit((hash[i] >> 4) & 0xF, 16));
                hex.append(Character.forDigit(hash[i] & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    @Override
    public String toString() {
        return value + " (" + packIds.size() + " packs)";
    }
}
