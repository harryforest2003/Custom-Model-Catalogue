import dev.custommodels.cmv.collection.StatedSets;
import dev.custommodels.cmv.core.ItemFacts;
import dev.custommodels.cmv.core.ItemFactsFetch;

import java.util.List;
import java.util.Map;

/**
 * The item-facts document: reading a server's catalogue, and what is built from it.
 *
 * <p>All of this is parsing somebody else's JSON, in a shape nobody here
 * controls and which can change without warning. So the questions worth asking
 * are not "does the happy path work" but "what happens to the odd one" - a
 * field renamed, a colour that is a hex string, a set that arrived a piece
 * short. Every one of those has to cost that one item and nothing else.</p>
 */
public final class FactsTest {

    private static int passed;
    private static int failed;

    private FactsTest() {
    }

    private static void says(String what, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  ok   " + what);
        } else {
            failed++;
            System.out.println("  FAIL " + what);
        }
    }

    /** A catalogue row, in the shape the one this was built against publishes. */
    private static String raw(String item, String cmd, String nameComponent) {
        return "{\"id\":\"" + item + "\",\"count\":1,\"components\":{"
                + (cmd == null ? "" : "\"minecraft:custom_model_data\":{\"floats\":[" + cmd + "]},")
                + "\"minecraft:custom_name\":" + nameComponent + "}}";
    }

    // ------------------------------------------------------- the join key

    private static void theKey() {
        System.out.println();
        System.out.println("the key a catalogue and a pack are joined on");

        says("a whole number is written as one, the way a person would",
                ItemFacts.key("minecraft:diamond_sword", 13.0F)
                        .equals("minecraft:diamond_sword#13"));
        says("and a value that is not whole keeps what it is",
                ItemFacts.key("minecraft:paper", 1.5F).equals("minecraft:paper#1.5"));
        says("a big value does not come back in exponent notation",
                !ItemFacts.key("minecraft:paper", 1.0E7F).contains("E"));
    }

    // -------------------------------------------------- reading the colours

    private static void theColours() {
        System.out.println();
        System.out.println("the colours a server gave a name");

        // The real shape: a component whose own text is empty and whose parts
        // are in extra, arriving as a *string* holding JSON rather than as JSON.
        String real = "\"{\\\"extra\\\":[{\\\"color\\\":\\\"gold\\\",\\\"italic\\\":false,"
                + "\\\"text\\\":\\\"X \\\"},{\\\"color\\\":\\\"aqua\\\",\\\"italic\\\":false,"
                + "\\\"text\\\":\\\"Knockback Fish \\\"},{\\\"color\\\":\\\"gold\\\","
                + "\\\"italic\\\":false,\\\"text\\\":\\\"X\\\"}],\\\"text\\\":\\\"\\\"}\"";
        String styled = ItemFactsFetch.coloured(raw("minecraft:pufferfish", "1", real));
        says("the colours are read out of a name that arrives as a string",
                styled.contains("&6") && styled.contains("&b"));
        says("in the order they are first used",
                styled.indexOf("&6") < styled.indexOf("&b"));
        says("and without any of the words they were used on",
                !styled.contains("Knockback") && !styled.contains("Fish"));

        ItemFacts.Fact fact = new ItemFacts.Fact("n", "c", "s", "r", styled, java.util.List.of());
        says("the codes alone are what is offered to be copied",
                fact.colourCodes().equals("&6&b"));
        says("which is colours only - none of the words",
                !fact.colourCodes().contains("Knockback"));
        says("each colour once, however often it is used",
                fact.colourCodes().indexOf("&6") == fact.colourCodes().lastIndexOf("&6"));

        // The one the live catalogue caught and this did not. These names carry
        // literal ampersands in their own text - a gradient reads "text": "M&" -
        // so anything that flattens codes and words together and takes them
        // apart again reads those as codes. Collected at the source there is no
        // such question, and this is here so it stays that way.
        String ampersands = "{\"extra\":[{\"color\":\"green\",\"text\":\"X &\"},"
                + "{\"color\":\"#55FF55\",\"text\":\"M&\"},"
                + "{\"color\":\"#4FF94F\",\"text\":\"a&\"}],\"text\":\"\"}";
        String fromGradient = ItemFactsFetch.coloured(
                raw("minecraft:diamond_sword", "13", ampersands));
        says("an ampersand in the server's own text is not read as a code",
                fromGradient.equals("&a&#55FF55&#4FF94F"));
        says("and every step of a gradient is kept",
                fromGradient.contains("&#55FF55") && fromGradient.contains("&#4FF94F"));

        // A hex colour is what the places taking these actually want, so it
        // must survive as written rather than being squeezed into a code.
        String hex = "{\"extra\":[{\"color\":\"#357286\",\"text\":\"Teal \"},"
                + "{\"color\":\"white\",\"text\":\"Blade\"}],\"text\":\"\"}";
        ItemFacts.Fact hexed = new ItemFacts.Fact("n", "", "", "",
                ItemFactsFetch.coloured(raw("minecraft:iron_sword", "2", hex)),
                java.util.List.of());
        says("a hex colour is kept exactly as the server wrote it",
                hexed.colourCodes().contains("&#357286"));
        says("and is not mistaken for a one-letter code",
                !hexed.colourCodes().contains("&#3&5"));

        // Nothing to read is an ordinary answer, not a failure.
        says("a name with no colours at all offers no codes",
                new ItemFacts.Fact("n", "", "", "",
                        ItemFactsFetch.coloured(raw("minecraft:stick", "1",
                                "{\"text\":\"Plain\"}")),
                        java.util.List.of()).colourCodes().isEmpty());
        says("an item with no name component is survived",
                ItemFactsFetch.coloured(
                        "{\"id\":\"minecraft:stick\",\"components\":{}}").isEmpty());
        says("and so is something that is not JSON at all",
                ItemFactsFetch.coloured("not json").isEmpty());
        says("and nothing at all",
                ItemFactsFetch.coloured(null).isEmpty());
    }

    // ------------------------------------- the name as the server writes it

    /**
     * The parts a name is drawn from.
     *
     * <p>Separate from the codes because they answer different questions: the
     * codes are for putting these colours on other words, and the parts are
     * for drawing these words in these colours. The parts are what make the
     * card and the tooltip show the name the server shows.</p>
     */
    private static void theParts() {
        System.out.println();
        System.out.println("the name as the server writes it");

        String gradient = "{\"extra\":[{\"color\":\"green\",\"text\":\"X &\"},"
                + "{\"color\":\"#55FF55\",\"text\":\"M&\"},"
                + "{\"color\":\"#4FF94F\",\"text\":\"a\"}],\"text\":\"\"}";
        List<ItemFacts.Part> parts = ItemFactsFetch.parts(
                raw("minecraft:diamond_sword", "13", gradient));
        says("every run of the name is kept", parts.size() == 3);
        says("with the colour it is written in",
                parts.get(1).colour().equals("#55FF55"));
        says("and its text exactly, ampersand and all",
                parts.get(1).text().equals("M&"));
        says("the words put back together are the name",
                parts.stream().map(ItemFacts.Part::text)
                        .reduce("", String::concat).equals("X &M&a"));

        // A name with nothing to draw it from still answers.
        says("no parts is survived rather than thrown",
                ItemFactsFetch.parts("not json").isEmpty());

    }

    // -------------------------------------- a separator the server left in

    /**
     * Names that draw as {@code O&c&e&a&n&i&c}, tidied as the document is read.
     *
     * <p>The runs below are copied from the catalogue, not invented: the real
     * entry for {@code minecraft:bow#1001} arrives as {@code "\u2646 &"},
     * {@code "O&"}, {@code "c&"} and so on, ending {@code "w "} and {@code
     * "\u2646"} with no marker on either. Forty-three names are built that
     * way. Writing the check against made-up runs is how the colour bug got
     * through once already, so these are the shape that is actually there.</p>
     *
     * <p>Checked as the document is read rather than as it is downloaded,
     * because a document already on disk is the normal case: a name written
     * last week has to come out right today without the service being asked
     * anything at all.</p>
     */
    private static void aSeparatorLeftIn() {
        System.out.println();
        System.out.println("a separator the server left in its own names");

        List<ItemFacts.Part> runs = List.of(
                new ItemFacts.Part("\u2646 &", "aqua"),
                new ItemFacts.Part("O&", "#55FFFF"),
                new ItemFacts.Part("c&", "#55EEFF"),
                new ItemFacts.Part("e&", "#55DDFF"),
                new ItemFacts.Part("a&", "#55CCFF"),
                new ItemFacts.Part("n&", "#55BBFF"),
                new ItemFacts.Part("i&", "#55AAFF"),
                new ItemFacts.Part("c&", "#5599FF"),
                new ItemFacts.Part(" &", "#5588FF"),
                new ItemFacts.Part("B&", "#5578FF"),
                new ItemFacts.Part("o&", "#5566FF"),
                new ItemFacts.Part("w ", "#5555FF"),
                new ItemFacts.Part("\u2646", "aqua"));
        String plain = "\u2646 Oceanic Bow \u2646";
        says("the runs as they arrive do spell the ampersands",
                spelled(runs).contains("&"));
        ItemFacts.Fact tidy = ItemFacts.tidied(new ItemFacts.Fact(
                plain, "Oceanic", "", "MYTHIC", "&b&#55FFFF", runs));
        says("tidied, they spell the name the catalogue publishes",
                spelled(tidy.parts()).equals(plain));
        says("and no ampersand is left in them",
                !spelled(tidy.parts()).contains("&"));
        says("every run keeps its own colour",
                tidy.parts().size() == runs.size()
                        && tidy.parts().get(1).colour().equals("#55FFFF")
                        && tidy.parts().get(12).colour().equals("aqua"));
        says("and the colour codes are untouched, markers and all",
                tidy.colourCodes().equals("&b&#55FFFF"));

        // The guess is checked, not trusted. A name that really ends in one is
        // left exactly as it came, because shortening it would be inventing a
        // name the catalogue does not publish.
        List<ItemFacts.Part> genuine = List.of(
                new ItemFacts.Part("Salt &", "white"),
                new ItemFacts.Part("Pepper", "gray"));
        ItemFacts.Fact kept = ItemFacts.tidied(new ItemFacts.Fact(
                "Salt &Pepper", "", "", "", "", genuine));
        says("a marker the name really contains is kept",
                spelled(kept.parts()).equals("Salt &Pepper"));

        // Runs that shorten to something other than the published name are a
        // server doing this differently, and are left alone rather than guessed at.
        List<ItemFacts.Part> other = List.of(
                new ItemFacts.Part("A&", "red"),
                new ItemFacts.Part("B&", "red"));
        says("runs that would not spell the name are left as they came",
                spelled(ItemFacts.tidied(new ItemFacts.Fact(
                        "Something Else", "", "", "", "", other)).parts())
                        .equals("A&B&"));

        // Nothing to check against is a reason to leave it, not to throw.
        says("no plain name to check against leaves the runs alone",
                spelled(ItemFacts.tidied(new ItemFacts.Fact(
                        "", "", "", "", "", other)).parts()).equals("A&B&"));
        says("no runs at all is survived",
                ItemFacts.tidied(new ItemFacts.Fact(
                        "Name", "", "", "", "", List.of())).parts().isEmpty());
        says("and so is no fact at all", ItemFacts.tidied(null) == null);

        // And that it is actually applied to what comes off the disk, which is
        // a different question from whether it works. A document in the shape
        // the file really holds, run through the same reading the file goes
        // through: a check on the tidying alone passes just as happily when
        // nothing ever calls it, and that exact failure has shipped here twice.
        String document = "{\"source\":\"https://example.invalid\","
                + "\"updated\":\"2026-10-28T11:04:19Z\",\"items\":{"
                + "\"minecraft:bow#1001\":{\"name\":\"Oceanic Bow\","
                + "\"crate\":\"Oceanic\",\"set\":\"\",\"rarity\":\"MYTHIC\","
                + "\"colours\":\"&b&#55FFFF\",\"parts\":["
                + "{\"text\":\"O&\",\"colour\":\"#55FFFF\"},"
                + "{\"text\":\"c&\",\"colour\":\"#55EEFF\"},"
                + "{\"text\":\"e&\",\"colour\":\"#55DDFF\"},"
                + "{\"text\":\"a&\",\"colour\":\"#55CCFF\"},"
                + "{\"text\":\"n&\",\"colour\":\"#55BBFF\"},"
                + "{\"text\":\"i&\",\"colour\":\"#55AAFF\"},"
                + "{\"text\":\"c&\",\"colour\":\"#5599FF\"},"
                + "{\"text\":\" &\",\"colour\":\"#5588FF\"},"
                + "{\"text\":\"B&\",\"colour\":\"#5578FF\"},"
                + "{\"text\":\"o&\",\"colour\":\"#5566FF\"},"
                + "{\"text\":\"w\",\"colour\":\"#5555FF\"}]}}}";
        java.util.Map<String, ItemFacts.Fact> read = ItemFacts.itemsIn(document);
        says("a document is read into the item it describes", read.size() == 1);
        ItemFacts.Fact fromDisk = read.get("minecraft:bow#1001");
        says("and the name it draws has been tidied on the way in",
                fromDisk != null && spelled(fromDisk.parts()).equals("Oceanic Bow"));
        says("with no ampersand left in it",
                fromDisk != null && !spelled(fromDisk.parts()).contains("&"));
        says("the rest of the fact is untouched",
                fromDisk != null && "MYTHIC".equals(fromDisk.rarity())
                        && "Oceanic".equals(fromDisk.crate())
                        && "&b&#55FFFF".equals(fromDisk.colourCodes()));
        says("a document that is not json costs the names and nothing else",
                ItemFacts.itemsIn("not json").isEmpty());
        says("and so does no document at all",
                ItemFacts.itemsIn((String) null).isEmpty());
    }

    /** The words a set of runs draws, which is what a person actually sees. */
    private static String spelled(List<ItemFacts.Part> parts) {
        StringBuilder out = new StringBuilder();
        for (ItemFacts.Part part : parts) {
            out.append(part.text() == null ? "" : part.text());
        }
        return out.toString();
    }

    // ------------------------------------------------- sets, only when sure

    private static void onlyDefiniteSets() {
        System.out.println();
        System.out.println("sets built only when the document is sure");

        // A catalogue that publishes no sets builds none. There is nothing to
        // infer them from and inferring them would be inventing facts.
        says("no sets described means no sets planned",
                StatedSets.plan(Map.of(), key -> "m:" + key).isEmpty());
        says("and a null section is the same",
                StatedSets.plan(null, key -> "m:" + key).isEmpty());

        // Whole, and every piece present here.
        Map<String, ItemFacts.SetFacts> whole = Map.of("Cosmic",
                new ItemFacts.SetFacts(2, 2, List.of("i#1", "i#2")));
        List<StatedSets.Plan> made = StatedSets.plan(whole, key -> "m:" + key);
        says("a complete set whose pieces are all here is built",
                made.size() == 1 && made.get(0).definitive());
        says("and holds each of them",
                made.get(0).models().size() == 2);

        // Short at the catalogue, but what is here is still worth having.
        List<StatedSets.Plan> short1 = StatedSets.plan(Map.of("Elderwood",
                new ItemFacts.SetFacts(4, 3, List.of("i#1", "i#2", "i#3"))),
                key -> "m:" + key);
        says("a set the catalogue was short on is still built from what is here",
                short1.get(0).definitive());
        says("and says how many of how many that was",
                short1.get(0).found() == 3 && short1.get(0).expected() == 4);

        // Pieces in a pack that is not installed are simply not in it.
        List<StatedSets.Plan> partial = StatedSets.plan(Map.of("Odyssey",
                new ItemFacts.SetFacts(3, 3, List.of("i#1", "i#2", "i#3"))),
                key -> key.equals("i#3") ? null : "m:" + key);
        says("a set missing a piece is built from the two that are here",
                partial.get(0).definitive() && partial.get(0).models().size() == 2);

        // But one piece is not a set.
        List<StatedSets.Plan> lonely = StatedSets.plan(Map.of("Lonely",
                new ItemFacts.SetFacts(4, 4, List.of("i#1", "i#2"))),
                key -> key.equals("i#1") ? "m:one" : null);
        says("a set down to one piece is not built",
                !lonely.get(0).definitive());
        says("and says how few of how many were found",
                lonely.get(0).why().contains("1") && lonely.get(0).why().contains("4"));

        // One model reached twice is still one piece, not a set of two.
        List<StatedSets.Plan> doubled = StatedSets.plan(Map.of("Twin",
                new ItemFacts.SetFacts(2, 2, List.of("i#1", "i#2"))),
                key -> "m:same");
        says("the same model reached twice is not two pieces",
                doubled.get(0).models().size() == 1);
        says("so a set that collapses to one model is not built",
                !doubled.get(0).definitive());
    }

    public static void main(String[] args) {
        theKey();
        theColours();
        theParts();
        aSeparatorLeftIn();
        onlyDefiniteSets();

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
