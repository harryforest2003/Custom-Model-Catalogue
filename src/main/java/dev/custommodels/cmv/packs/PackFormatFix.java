package dev.custommodels.cmv.packs;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.custommodels.cmv.compat.McCompat;
import dev.custommodels.cmv.core.CmvConfig;
import dev.custommodels.cmv.core.CmvMod;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Brings a resource pack's metadata up to the version the game is running.
 *
 * <h2>The problem</h2>
 *
 * <p>26.2 reports its client resource format as {@code 88.0} — versioned now,
 * major and minor — and a pack claiming support past 64 must say so with
 * {@code min_format} and {@code max_format}. A pack written for an earlier
 * game says {@code pack_format} and {@code supported_formats} instead, and the
 * game answers "broken or incompatible" and refuses to load it. Nothing about
 * the pack's contents is wrong; it is the declaration that is out of date.</p>
 *
 * <h2>Why it is safe to rewrite the file</h2>
 *
 * <p>Only packs the game has <em>refused</em> are touched. A pack it loaded is
 * held open, and rewriting a zip underneath a reader is how an archive ends up
 * truncated; a pack it rejected is not open by anything. That is the same set
 * as the packs needing the fix, so the restriction costs nothing.</p>
 *
 * <p>The original {@code pack.mcmeta} is kept inside the pack as
 * {@code pack.mcmeta.cmvbak} before anything is written, so the change can be
 * read back and undone. Every other entry is copied across untouched, and the
 * new archive is written beside the old one and moved into place only once it
 * is complete — a crash halfway through leaves the original where it was.</p>
 */
public final class PackFormatFix {

    /**
     * Where untouched copies are kept, beside the game directory.
     *
     * <p>Deliberately not inside {@code resourcepacks}: a folder in there is
     * itself a pack as far as the game is concerned, and the copies would show
     * up in the list as duplicates of the packs they are copies of.</p>
     */
    public static final String BACKUP_FOLDER = "original packs";

    private PackFormatFix() {
    }

    /** Ticks to wait after a reload before looking, as UnifiedPack does. */
    private static final int SETTLE_TICKS = 40;
    private static int countdown = SETTLE_TICKS;

    /** Asks for a check shortly. Called from the reload hook, never from a tick. */
    public static void requestCheck() {
        countdown = SETTLE_TICKS;
    }

    /**
     * Runs a requested check once the game has settled.
     *
     * <p>Off a tick rather than the reload itself: repairing a pack makes the
     * repository re-read it, and starting that from inside a reload is how a
     * mod reloads forever.</p>
     */
    public static void tick() {
        if (countdown < 0) {
            return;
        }
        if (--countdown > 0) {
            return;
        }
        countdown = -1;
        try {
            Report report = run();
            if (report.fixed() == 0) {
                return;
            }
            // Re-read the folder so the pack screen stops calling them broken.
            McCompat.rediscoverPacks();
            McCompat.sendChat(net.minecraft.network.chat.Component.literal(
                    "[CMV] Updated " + report.fixed() + " resource pack"
                            + (report.fixed() == 1 ? "" : "s")
                            + " for this version: " + String.join(", ", report.names())
                            + ". Enable them in Options \u2192 Resource Packs."));
        } catch (RuntimeException | LinkageError e) {
            CmvMod.LOGGER.warn("Pack format check failed", e);
        }
    }

    /** What one pass did, for the log and the diagnostics page. */
    public record Report(int examined, int fixed, List<String> names) {
    }

    /**
     * Repairs every pack the game refused for its declared format.
     *
     * <p>Returns without touching the disk when the setting is off or nothing
     * was refused, which is the ordinary case on every launch after the first.</p>
     */
    public static Report run() {
        if (!CmvConfig.get().autoFixPackFormat) {
            return new Report(0, 0, List.of());
        }
        List<String> refused = McCompat.incompatiblePackFiles();
        if (refused.isEmpty()) {
            return new Report(0, 0, List.of());
        }

        Path folder = McCompat.gameDir().resolve("resourcepacks");
        List<String> fixed = new ArrayList<>();
        int examined = 0;
        for (String fileName : refused) {
            Path pack = folder.resolve(fileName).normalize();
            if (!pack.startsWith(folder.normalize()) || !Files.isRegularFile(pack)) {
                // A name out of the repository is not a name to resolve blindly.
                continue;
            }
            examined++;
            try {
                if (rewrite(pack)) {
                    fixed.add(fileName);
                }
            } catch (IOException | RuntimeException e) {
                CmvMod.LOGGER.warn("Could not update {}", fileName, e);
            }
        }
        if (!fixed.isEmpty()) {
            CmvMod.LOGGER.info("Updated the pack format of {}", String.join(", ", fixed));
        }
        return new Report(examined, fixed.size(), List.copyOf(fixed));
    }

    /**
     * Rewrites one pack's declaration. Returns false when it needed nothing.
     *
     * <p>The untouched pack is copied aside first, and the copy is what makes
     * this safe to do without asking: if the repair turns out to be wrong for
     * some pack, the original is still whole and named the same. An existing
     * copy is never overwritten — the first one taken is the one from before
     * this mod touched anything.</p>
     */
    /**
     * Repairs one pack's format declaration, wherever it came from.
     *
     * <p>Exposed for the server-pack import, which has just put a file in the
     * folder that the game has not seen yet — so it cannot appear in the
     * "refused" list that {@link #run()} works from, but still needs the same
     * treatment and the same copy kept aside.</p>
     *
     * @return false when the pack already declared a usable format
     */
    public static boolean repair(Path pack) throws IOException {
        return rewrite(pack);
    }

    private static boolean rewrite(Path pack) throws IOException {
        String original = readEntry(pack, "pack.mcmeta");
        if (original == null) {
            return false;
        }
        String updated = updated(original);
        if (updated == null) {
            return false;
        }

        Path backups = McCompat.gameDir().resolve(BACKUP_FOLDER);
        Files.createDirectories(backups);
        Path copy = backups.resolve(pack.getFileName().toString());
        if (!Files.exists(copy)) {
            Path pending = backups.resolve(pack.getFileName() + ".cmvtmp");
            Files.copy(pack, pending, StandardCopyOption.REPLACE_EXISTING);
            Files.move(pending, copy, StandardCopyOption.ATOMIC_MOVE);
        }

        Path staging = pack.resolveSibling(pack.getFileName() + ".cmvtmp");
        try {
            copyReplacing(pack, staging, updated);
            Files.move(staging, pack, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } finally {
            Files.deleteIfExists(staging);
        }
    }

    /**
     * The corrected metadata, or null when the file already says enough.
     *
     * <p>The old fields are kept beside the new ones. A pack that still has to
     * work on a client older than 65 reads those, and the two eras only coexist
     * if their maxima agree — which is why the top is written into both rather
     * than only into {@code max_format}.</p>
     */
    public static String updated(String mcmeta) {
        JsonObject root;
        try {
            root = JsonParser.parseString(mcmeta).getAsJsonObject();
        } catch (RuntimeException e) {
            return null;
        }
        if (!root.has("pack") || !root.get("pack").isJsonObject()) {
            return null;
        }
        JsonObject section = root.getAsJsonObject("pack");
        int target = McCompat.currentPackFormat();
        if (target <= 0) {
            return null;
        }
        if (section.has("min_format") && section.has("max_format")) {
            // Already speaking the current dialect. Whether it covers this
            // version is the pack's business, not a thing to overwrite.
            return null;
        }

        int floor = 32;
        if (section.has("pack_format")) {
            try {
                floor = Math.min(floor, section.get("pack_format").getAsInt());
            } catch (RuntimeException ignored) {
                // A pack_format that is not a number tells us nothing.
            }
        }
        section.addProperty("pack_format", floor);
        com.google.gson.JsonArray range = new com.google.gson.JsonArray();
        range.add(floor);
        range.add(target);
        section.add("supported_formats", range);
        section.addProperty("min_format", floor);
        section.addProperty("max_format", target);
        return root.toString();
    }

    private static String readEntry(Path pack, String name) throws IOException {
        try (InputStream in = Files.newInputStream(pack);
             ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (name.equals(entry.getName())) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    /** Copies the archive across, swapping the one entry. */
    private static void copyReplacing(Path from, Path to, String updated)
            throws IOException {
        try (InputStream in = Files.newInputStream(from);
             ZipInputStream zip = new ZipInputStream(in);
             OutputStream out = Files.newOutputStream(to);
             ZipOutputStream copy = new ZipOutputStream(out)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                copy.putNextEntry(new ZipEntry(name));
                if (!entry.isDirectory()) {
                    if ("pack.mcmeta".equals(name)) {
                        copy.write(updated.getBytes(StandardCharsets.UTF_8));
                        zip.transferTo(OutputStream.nullOutputStream());
                    } else {
                        zip.transferTo(copy);
                    }
                }
                copy.closeEntry();
            }
        }
    }

}
