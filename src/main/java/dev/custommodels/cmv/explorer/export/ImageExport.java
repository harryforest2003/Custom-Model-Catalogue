package dev.custommodels.cmv.explorer.export;

import com.mojang.blaze3d.platform.NativeImage;
import dev.custommodels.cmv.compat.McCompat;
import dev.custommodels.cmv.core.CmvMod;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Writes rendered previews to disk and, where the platform allows it, to the
 * clipboard.
 *
 * <h2>Images come from the renderer, not the screen</h2>
 *
 * <p>Nothing here reads the framebuffer. An exported image is rasterized
 * afresh at whatever size was asked for, so it contains the model and nothing
 * else: no slot borders, no tooltip, no scaling artefacts from the GUI, and no
 * dependence on what the window happened to be showing. It also means an export
 * can be larger than the preview on screen.</p>
 *
 * <h2>Clipboard</h2>
 *
 * <p>Putting an image on the clipboard from Java normally means AWT, and AWT
 * inside a GLFW application is a genuine hazard on macOS: both want to own the
 * first thread, and initialising the toolkit can hang the window. So the
 * clipboard is handled per platform, always after the file has been written, so
 * that a clipboard failure still leaves the player with the image:</p>
 *
 * <ul>
 *   <li><b>macOS</b> — {@code osascript} reads the file into the pasteboard.
 *       No AWT, no risk to the window.</li>
 *   <li><b>Windows</b> — PowerShell's {@code System.Windows.Forms.Clipboard}.</li>
 *   <li><b>Linux</b> — {@code wl-copy} or {@code xclip} if either is present.</li>
 * </ul>
 *
 * <p>When none of those works the path is copied as text instead and the caller
 * is told, which is a useful outcome rather than a silent failure.</p>
 */
public final class ImageExport {

    private ImageExport() {
    }

    /** What happened, so the UI can say something true about it. */
    public record Outcome(boolean fileWritten, boolean clipboard, Path file, String message) {
    }

    /** Where exports are written. */
    public static Path exportDirectory() {
        return McCompat.configDir().resolve("exports");
    }

    /** A file name safe on every platform, derived from a model's name. */
    public static String safeName(String displayName, String suffix) {
        StringBuilder builder = new StringBuilder(displayName.length() + 8);
        for (char c : displayName.toCharArray()) {
            builder.append(Character.isLetterOrDigit(c) ? Character.toLowerCase(c) : '_');
        }
        String base = builder.toString().replaceAll("_+", "_").replaceAll("^_|_$", "");
        if (base.isEmpty()) {
            base = "model";
        }
        return base + "_" + System.currentTimeMillis() % 100000 + suffix;
    }

    // ------------------------------------------------------------------ PNG

    /**
     * Writes ARGB pixels as a PNG.
     *
     * <p>Uses Minecraft's own encoder rather than ImageIO, for the same reason
     * the clipboard avoids AWT.</p>
     */
    public static void writePng(int[] argb, int width, int height, Path file)
            throws IOException {
        Files.createDirectories(file.getParent());
        try (NativeImage image = new NativeImage(width, height, false)) {
            for (int y = 0; y < height; y++) {
                int row = y * width;
                for (int x = 0; x < width; x++) {
                    image.setPixel(x, y, argb[row + x]);
                }
            }
            image.writeToFile(file);
        }
    }

    /** Writes a PNG and tries to put it on the clipboard. */
    public static Outcome exportImage(int[] argb, int width, int height, String name) {
        Path file = exportDirectory().resolve(safeName(name, ".png"));
        try {
            writePng(argb, width, height, file);
        } catch (IOException | RuntimeException e) {
            CmvMod.LOGGER.warn("Could not write preview image", e);
            return new Outcome(false, false, null, "Could not write image: " + e.getMessage());
        }
        boolean copied = copyImageToClipboard(file);
        if (copied) {
            return new Outcome(true, true, file, "Image copied to clipboard");
        }
        McCompat.copyToClipboard(file.toString());
        return new Outcome(true, false, file,
                "Saved to " + file.getFileName() + " (path copied to clipboard)");
    }

    // ------------------------------------------------------------------ GIF

    /** Writes an animated GIF and copies its path. */
    public static Outcome exportAnimation(List<int[]> frames, int width, int height,
                                          int delayMillis, int matte, String name) {
        Path file = exportDirectory().resolve(safeName(name, ".gif"));
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                GifEncoder.encode(frames, width, height, delayMillis, matte, out);
            }
        } catch (IOException | RuntimeException e) {
            CmvMod.LOGGER.warn("Could not write animated preview", e);
            return new Outcome(false, false, null, "Could not write GIF: " + e.getMessage());
        }
        // No platform offers a clipboard flavour that carries GIF animation, so
        // the path is the useful thing to hand over.
        McCompat.copyToClipboard(file.toString());
        return new Outcome(true, false, file,
                "Saved " + file.getFileName() + " (path copied to clipboard)");
    }

    // ------------------------------------------------------------ clipboard

    /**
     * Hands a PNG to the system clipboard through whatever helper the platform
     * has.
     *
     * <p>The exported file's name is built by {@link #safeName}, which reduces
     * it to letters and digits, but the directory it sits in is the player's
     * own game folder and nothing sanitises that. A path containing a quote —
     * {@code /home/bob's games/} is an ordinary thing for a path to be — used
     * to escape the quoting these commands were built with. So no shell is
     * used where one can be avoided, and where a scripting host is unavoidable
     * the path is escaped for the language it lands in.</p>
     */
    private static boolean copyImageToClipboard(Path file) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("mac")) {
                // AppleScript string literal: backslash and double quote.
                String escaped = file.toString()
                        .replace("\\", "\\\\").replace("\"", "\\\"");
                return run("osascript", "-e",
                        "set the clipboard to (read (POSIX file \"" + escaped
                                + "\") as «class PNGf»)");
            }
            if (os.contains("win")) {
                // PowerShell single-quoted string: a quote is doubled.
                String escaped = file.toString().replace("'", "''");
                return run("powershell", "-NoProfile", "-STA", "-Command",
                        "Add-Type -AssemblyName System.Windows.Forms,System.Drawing; "
                                + "[System.Windows.Forms.Clipboard]::SetImage("
                                + "[System.Drawing.Image]::FromFile('" + escaped + "'))");
            }
            // Wayland first, then X11; whichever the session actually uses.
            // Both take the file without a shell: wl-copy from standard input,
            // xclip as a plain argument. Nothing is interpolated.
            return runWithInput(file, "wl-copy", "--type", "image/png")
                    || run("xclip", "-selection", "clipboard", "-t", "image/png",
                            "-i", file.toString());
        } catch (RuntimeException e) {
            CmvMod.LOGGER.debug("Clipboard image copy unavailable", e);
            return false;
        }
    }

    /**
     * Runs a helper and reports whether it succeeded.
     *
     * <p>Bounded by a timeout so that a missing or wedged helper cannot leave
     * the caller waiting. Exports run on a worker thread, but a thread stuck
     * forever is still a leak.</p>
     */
    private static boolean run(String... command) {
        return start(new ProcessBuilder(command));
    }

    /** As {@link #run}, with a file fed to the helper's standard input. */
    private static boolean runWithInput(Path input, String... command) {
        return start(new ProcessBuilder(command)
                .redirectInput(input.toFile()));
    }

    private static boolean start(ProcessBuilder builder) {
        try {
            Process process = builder
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException e) {
            // The helper is not installed; that is an expected outcome.
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
