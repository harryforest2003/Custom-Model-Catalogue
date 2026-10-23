import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.custommodels.cmv.wrap.DispatchName;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;

/**
 * The one check that cannot be done without Minecraft on the classpath.
 *
 * <p>A pack that summons a model by the item's name matches by component
 * equality, so the name this mod writes has to equal the one the pack's
 * {@code "when"} decodes to — same text is not enough, the style has to match as
 * well. That is a fact about Minecraft's own codec and {@code Component.equals},
 * so it is checked against the real classes rather than a model of them.
 *
 * <p>Run with the game jar on the classpath; see the README.
 */
public final class DispatchNameTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();

        String name = "Pheonix Grace";

        // What a pack's  "when": "Pheonix Grace"  actually becomes.
        Component fromPack = ComponentSerialization.CODEC
                .parse(JsonOps.INSTANCE, JsonParser.parseString("\"" + name + "\""))
                .getOrThrow();
        Component written = DispatchName.of(name);

        check("the name written matches the one a pack switches on",
                fromPack.equals(written));
        check("and hashes the same, which is how the game looks it up",
                fromPack.hashCode() == written.hashCode());
        check("it carries no style at all", written.getStyle().isEmpty());

        // The regression. Same text, one style flag, no match — and nothing on
        // screen to say so, because both render identically.
        Component styled = Component.literal(name).withStyle(s -> s.withItalic(false));
        check("turning italics off would break the match",
                !fromPack.equals(styled));
        check("even though it looks exactly the same",
                styled.getString().equals(written.getString()));

        // A name with formatting codes in it is a different name, not a styled
        // one: packs match the literal text the server sent.
        Component odd = DispatchName.of("§6Gilded");
        check("an odd name is still written verbatim",
                "§6Gilded".equals(odd.getString()) && odd.getStyle().isEmpty());

        packFormatTests();

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /**
     * The repaired metadata, judged by the game's own codec.
     *
     * <p>Not by a model of the rules but by {@code PackMetadataSection}'s codec
     * and {@code PackCompatibility.forVersion}, which are what actually decide
     * whether a pack loads. That is the whole point of the exercise: the rules
     * are fussy — old and new fields may coexist only if their maxima agree —
     * and a restatement of them would be the thing under test rather than the
     * thing itself.</p>
     */
    private static void packFormatTests() {
        System.out.println();
        System.out.println("== Pack format repair");

        var type = net.minecraft.server.packs.PackType.CLIENT_RESOURCES;
        var current = net.minecraft.SharedConstants.getCurrentVersion().packVersion(type);

        String broken = "{\"pack\":{\"description\":\"x\",\"pack_format\":84,"
                + "\"supported_formats\":[32,9999]}}";
        check("the pack the game refuses is refused here too",
                !accepts(broken, type, current));

        String repaired = dev.custommodels.cmv.packs.PackFormatFix.updated(broken);
        check("it is repaired", repaired != null);
        check("and the game accepts what comes back",
                repaired != null && accepts(repaired, type, current));

        // Running twice must not keep rewriting: the second pass sees a file
        // that already speaks the current dialect and leaves it alone.
        check("a repaired pack is not repaired again",
                repaired == null
                        || dev.custommodels.cmv.packs.PackFormatFix.updated(repaired) == null);

        // A pack that was already fine is left exactly as it was.
        String fine = "{\"pack\":{\"description\":\"x\",\"min_format\":32,"
                + "\"max_format\":88}}";
        check("a pack that already says enough is untouched",
                dev.custommodels.cmv.packs.PackFormatFix.updated(fine) == null);

        check("something that is not a pack.mcmeta is left alone",
                dev.custommodels.cmv.packs.PackFormatFix.updated("[]") == null
                        && dev.custommodels.cmv.packs.PackFormatFix.updated("{") == null);
    }

    private static boolean accepts(String mcmeta,
                                   net.minecraft.server.packs.PackType type,
                                   net.minecraft.server.packs.metadata.pack.PackFormat current) {
        try {
            var section = JsonParser.parseString(mcmeta).getAsJsonObject().get("pack");
            var result = net.minecraft.server.packs.metadata.pack.PackMetadataSection
                    .forPackType(type).codec().parse(JsonOps.INSTANCE, section);
            if (result.error().isPresent()) {
                return false;
            }
            return net.minecraft.server.packs.repository.PackCompatibility
                    .forVersion(result.getOrThrow().supportedFormats(), current).isCompatible();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void check(String what, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  ok   " + what);
        } else {
            failed++;
            System.out.println("  FAIL " + what);
        }
    }
}
