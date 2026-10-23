package dev.custommodels.cmv.packs;

import dev.custommodels.cmv.compat.McCompat;
import dev.custommodels.cmv.core.CmvMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.Locale;
import java.util.zip.ZipFile;

/**
 * Keeps the pack a server just sent you.
 *
 * <p>A server's pack is downloaded into {@code server-resource-packs} under a
 * name that is its hash, with no extension, and the client treats it as
 * disposable: it is applied while you are connected and can be cleared without
 * warning. Keeping one meant finding the newest hash-named file by hand,
 * copying it out, giving it a name, adding {@code .zip}, and then usually
 * repairing its format declaration because it was built for an older game.
 *
 * <p>This does those four steps. It takes only the newest file, because that is
 * the one belonging to the server being played right now rather than every
 * server ever joined, and it names the copy after that server so the
 * resourcepacks folder ends up readable instead of a row of hashes.
 */
public final class ServerPackImport {

    /** Where the client puts a pack a server sent. */
    private static final String CACHE = "server-resource-packs";

    /**
     * What happened, in a form the screen can put on one line.
     *
     * @param file the name written into resourcepacks, empty when none was
     */
    public record Report(Outcome outcome, String file, String detail) {

        public boolean worked() {
            return outcome == Outcome.IMPORTED || outcome == Outcome.ALREADY_HERE;
        }
    }

    public enum Outcome {
        IMPORTED,
        ALREADY_HERE,
        NOTHING_TO_IMPORT,
        FAILED
    }

    private ServerPackImport() {
    }

    /** How long an answer about the cache folder is reused, in milliseconds. */
    private static final long PROBE_TTL = 1000L;

    private static long probedAt;
    private static boolean probeResult;

    /**
     * Whether there is a downloaded pack to keep, for enabling the button.
     *
     * <p>Answered from a one-second cache because the button asks once a frame
     * and the honest answer costs a directory listing plus a stat per file —
     * measured at 105 microseconds with six packs cached, or six milliseconds a
     * second thrown away at sixty frames. Worse than the average is the shape
     * of the cost: it is disk work on the render thread, so a cold lookup
     * blocks drawing for as long as the disk takes. A pack arrives while the
     * player is connected to a server, so a second of staleness costs
     * nothing.</p>
     */
    public static boolean available() {
        long now = System.currentTimeMillis();
        if (now - probedAt < PROBE_TTL) {
            return probeResult;
        }
        probedAt = now;
        probeResult = newest() != null;
        return probeResult;
    }

    /** Forgets the cached answer, for when this class has just changed things. */
    static void forgetProbe() {
        probedAt = 0L;
    }

    /**
     * Copies the newest downloaded pack into the resourcepacks folder.
     *
     * <p>Called on the client thread from a button, so the work is bounded: one
     * file copy and one rewrite. A server pack is tens of megabytes at worst,
     * which is a stutter rather than a freeze, and doing it inline means the
     * pack list can be refreshed immediately afterwards.</p>
     */
    public static Report run() {
        Path source = newest();
        if (source == null) {
            return new Report(Outcome.NOTHING_TO_IMPORT, "",
                    "No downloaded pack. Join a server with resource packs enabled first.");
        }
        if (!looksLikeAPack(source)) {
            return new Report(Outcome.FAILED, "",
                    "The downloaded file is not a readable pack.");
        }

        String name = fileNameFor(serverName());
        Path folder = McCompat.gameDir().resolve("resourcepacks");
        Path target = folder.resolve(name);
        try {
            Files.createDirectories(folder);

            // Same name and same size means this exact pack is already kept.
            // Servers reissue their pack often, so a differing size is a new
            // version and should replace what is there.
            if (Files.isRegularFile(target)
                    && Files.size(target) == Files.size(source)) {
                return new Report(Outcome.ALREADY_HERE, name,
                        "Already in your resource packs.");
            }

            Path staging = folder.resolve(name + ".cmvtmp");
            try {
                Files.copy(source, staging, StandardCopyOption.REPLACE_EXISTING);
                Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(staging);
            }

            // The game has never seen this file, so it cannot appear in the
            // refused list the automatic fixer works from. Repair it directly.
            boolean repaired = false;
            try {
                repaired = PackFormatFix.repair(target);
            } catch (IOException | RuntimeException e) {
                CmvMod.LOGGER.warn("Imported {} but could not repair its format", name, e);
            }

            McCompat.rediscoverPacks();
            forgetProbe();
            CmvMod.LOGGER.info("Imported the server pack as {}{}", name,
                    repaired ? " and updated its format" : "");
            return new Report(Outcome.IMPORTED, name,
                    repaired ? "Imported and updated for this version."
                            : "Imported.");
        } catch (IOException | RuntimeException e) {
            CmvMod.LOGGER.warn("Could not import the server pack", e);
            return new Report(Outcome.FAILED, "", "Could not copy it: " + e.getMessage());
        }
    }

    /**
     * The most recently downloaded pack.
     *
     * <p>By modification time rather than by asking the client which pack is
     * applied: the applied one is held open and its identity is not exposed in
     * a form worth reaching for, whereas "the newest file in the folder" is
     * exactly the pack that was just fetched for the server being played.</p>
     */
    private static Path newest() {
        Path cache = McCompat.gameDir().resolve(CACHE);
        if (!Files.isDirectory(cache)) {
            return null;
        }
        Path best = null;
        FileTime bestTime = null;
        try (var entries = Files.list(cache)) {
            for (Path entry : entries.toList()) {
                if (!Files.isRegularFile(entry)) {
                    continue;
                }
                FileTime time = Files.getLastModifiedTime(entry);
                if (bestTime == null || time.compareTo(bestTime) > 0) {
                    best = entry;
                    bestTime = time;
                }
            }
        } catch (IOException e) {
            return null;
        }
        return best;
    }

    private static boolean looksLikeAPack(Path file) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            return zip.getEntry("pack.mcmeta") != null;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * What to call the server.
     *
     * <p>The name from the multiplayer list first, because it is what the
     * player calls it. Failing that the address, minus the port, which is at
     * least recognisable. A pack kept while not connected gets neither.</p>
     */
    private static String serverName() {
        Minecraft client = Minecraft.getInstance();
        ServerData server = client == null ? null : client.getCurrentServer();
        if (server == null) {
            return "";
        }
        if (server.name != null && !server.name.isBlank()
                && !server.name.equalsIgnoreCase("Minecraft Server")) {
            return server.name;
        }
        if (server.ip != null && !server.ip.isBlank()) {
            String ip = server.ip;
            int colon = ip.indexOf(':');
            return colon > 0 ? ip.substring(0, colon) : ip;
        }
        return "";
    }

    /**
     * Turns a server name into something safe to write to disk.
     *
     * <p>Server list names are decorated: colour codes, emoji, punctuation,
     * sometimes nothing but those. And the name reaches this straight from a
     * remote server, so it is treated as hostile — a name is allowed to become
     * a file in the resourcepacks folder and nothing else. Letters, digits,
     * dash and underscore survive; a dot survives only between two
     * alphanumerics, which keeps {@code play.example.com} readable while
     * leaving {@code ../..} nothing to work with. Everything else becomes a
     * single space.</p>
     */
    public static String fileNameFor(String serverName) {
        StringBuilder out = new StringBuilder(32);
        char[] chars = serverName.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            // A colour code is the section sign and the character after it;
            // dropping only the sign leaves a stray letter in the name.
            if (c == '\u00A7') {
                i++;
                continue;
            }
            boolean plain = Character.isLetterOrDigit(c) || c == '-' || c == '_';
            if (plain) {
                out.append(c);
                continue;
            }
            if (c == '.' && !out.isEmpty()
                    && Character.isLetterOrDigit(out.charAt(out.length() - 1))) {
                out.append(c);
                continue;
            }
            // One space stands in for any run of punctuation, and never leads.
            if (!out.isEmpty() && out.charAt(out.length() - 1) != ' ') {
                out.append(' ');
            }
        }

        String cleaned = out.toString().trim();
        while (cleaned.endsWith(".")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }
        if (cleaned.isEmpty()) {
            cleaned = "Server Pack";
        }
        if (cleaned.length() > 48) {
            cleaned = cleaned.substring(0, 48).trim();
        }
        return cleaned.toLowerCase(Locale.ROOT).endsWith(".zip")
                ? cleaned : cleaned + ".zip";
    }

}
