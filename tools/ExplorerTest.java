import dev.custommodels.cmv.explorer.def.DiscoveredModel;
import dev.custommodels.cmv.explorer.def.ItemDefinitionParser;
import dev.custommodels.cmv.explorer.def.Selector;
import dev.custommodels.cmv.explorer.graph.ExplorerQuery;
import dev.custommodels.cmv.explorer.graph.GraphBuilder;
import dev.custommodels.cmv.explorer.graph.GroupReason;
import dev.custommodels.cmv.explorer.graph.ItemType;
import dev.custommodels.cmv.explorer.graph.LogicalModel;
import dev.custommodels.cmv.explorer.graph.ModelState;
import dev.custommodels.cmv.explorer.render.Mesh;
import dev.custommodels.cmv.explorer.render.MeshBuilder;
import dev.custommodels.cmv.explorer.render.Rasterizer;
import dev.custommodels.cmv.explorer.res.ModelJson;
import dev.custommodels.cmv.explorer.res.ModelResolver;
import dev.custommodels.cmv.explorer.res.ResolvedModel;
import dev.custommodels.cmv.explorer.res.ResourceSource;
import dev.custommodels.cmv.wrap.WrapCatalogue;
import dev.custommodels.cmv.wrap.WrapTarget;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;

/**
 * Tests for the model explorer's discovery, resolution, grouping and rendering
 * logic. No JUnit and no Minecraft: everything under test is deliberately free
 * of game imports, and the resource pack is built in memory.
 *
 * <pre>
 *   javac -d /tmp/cmvtest \
 *       $(find src/main/java/dev/custommodels/cmv/explorer/def \
 *              src/main/java/dev/custommodels/cmv/explorer/res \
 *              src/main/java/dev/custommodels/cmv/explorer/graph \
 *              src/main/java/dev/custommodels/cmv/explorer/render -name '*.java') \
 *       tools/ExplorerTest.java
 *   java -cp /tmp/cmvtest ExplorerTest
 * </pre>
 */
public final class ExplorerTest {

    /** Stands in for the registry tags the game supplies at runtime. */
    private static final Map<String, ItemType> TEST_TAGS = Map.of(
            "minecraft:netherite_helmet", ItemType.HELMET,
            "minecraft:diamond_helmet", ItemType.HELMET,
            "minecraft:leather_helmet", ItemType.HELMET,
            "minecraft:diamond_pickaxe", ItemType.PICKAXE,
            "minecraft:diamond_sword", ItemType.SWORD,
            "minecraft:iron_sword", ItemType.SWORD,
            "minecraft:diamond_axe", ItemType.AXE,
            "minecraft:netherite_pickaxe", ItemType.PICKAXE);

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        parserTests();
        resolverTests();
        groupingTests();
        deduplicationTests();
        heuristicTests();
        namingTests();
        wrapTests();
        setsTests();
        nameDispatchTests();
        hostileInputTests();
        transformTests();
        classificationTests();
        heldVersusWornTests();
        searchTests();
        renderTests();
        textureNamespaceTests();
        whatLooksLikeThis();
        whatThePacksAreMissing();

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- parser

    private static void parserTests() {
        header("Item definition parser");

        // The four-state bow, exactly as vanilla 26.2 writes it.
        List<DiscoveredModel> bow = parse("minecraft:bow", """
                {"model":{"type":"minecraft:condition","property":"minecraft:using_item",
                  "on_false":{"type":"minecraft:model","model":"minecraft:item/bow"},
                  "on_true":{"type":"minecraft:range_dispatch",
                    "property":"minecraft:use_duration","scale":0.05,
                    "fallback":{"type":"minecraft:model","model":"minecraft:item/bow_pulling_0"},
                    "entries":[
                      {"threshold":0.65,"model":{"type":"minecraft:model","model":"minecraft:item/bow_pulling_1"}},
                      {"threshold":0.9,"model":{"type":"minecraft:model","model":"minecraft:item/bow_pulling_2"}}]}}}
                """);
        check("bow yields four models", bow.size() == 4);
        check("bow at rest has no active conditions",
                bow.stream().anyMatch(m -> m.modelId().equals("minecraft:item/bow")
                        && m.activationCost() == 0));
        check("bow pull stages record their thresholds",
                bow.stream().anyMatch(m -> m.path().stream().anyMatch(
                        s -> s.threshold() == 0.9F && s.scale() == 0.05F)));

        // "when" as an array, which vanilla's trident.json uses.
        List<DiscoveredModel> array = parse("minecraft:trident", """
                {"model":{"type":"minecraft:select","property":"minecraft:display_context",
                  "cases":[{"when":["gui","ground","fixed"],
                            "model":{"type":"minecraft:model","model":"minecraft:item/trident"}}],
                  "fallback":{"type":"minecraft:model","model":"minecraft:item/trident_in_hand"}}}
                """);
        check("array \"when\" is read", array.size() == 2);
        check("array \"when\" keeps every value",
                array.stream().anyMatch(m -> m.path().stream()
                        .anyMatch(s -> s.values().size() == 3)));

        // Custom model data reachability.
        List<DiscoveredModel> cmd = parse("minecraft:paper", """
                {"model":{"type":"minecraft:select","property":"minecraft:custom_model_data",
                  "cases":[{"when":"hat","model":{"type":"minecraft:model","model":"p:item/hat"}}],
                  "fallback":{"type":"minecraft:model","model":"minecraft:item/paper"}}}
                """);
        check("case branch counts as custom model data",
                cmd.stream().filter(DiscoveredModel::viaCustomModelData).count() == 1);
        check("fallback branch does not count as custom model data",
                cmd.stream().filter(m -> m.modelId().equals("minecraft:item/paper"))
                        .noneMatch(DiscoveredModel::viaCustomModelData));

        // Broken input degrades per node, not per file.
        ItemDefinitionParser.Result broken = ItemDefinitionParser.parse(
                "minecraft:x", "minecraft:items/x.json", "assets/minecraft/items/x.json",
                "file/P.zip", new StringReader("{ \"model\": { \"type\": "));
        check("malformed JSON is a warning, not a crash",
                broken.models().isEmpty() && broken.warnings().size() == 1);

        ItemDefinitionParser.Result partial = ItemDefinitionParser.parse(
                "minecraft:x", "minecraft:items/x.json", "assets/minecraft/items/x.json",
                "file/P.zip", new StringReader("""
                        {"model":{"type":"minecraft:composite","models":[
                          {"type":"minecraft:model","model":"p:item/good"},
                          {"type":"minecraft:nonsense"},
                          {"type":"minecraft:model","model":"p:item/also_good"}]}}
                        """));
        check("one bad node does not lose the good ones",
                partial.models().size() == 2 && partial.warnings().size() == 1);

        List<DiscoveredModel> empty = parse("minecraft:x",
                "{\"model\":{\"type\":\"minecraft:empty\"}}");
        check("empty model is a leaf", empty.size() == 1
                && empty.get(0).leafKind() == DiscoveredModel.LeafKind.EMPTY);

        List<DiscoveredModel> special = parse("minecraft:shield", """
                {"model":{"type":"minecraft:special","base":"minecraft:item/shield",
                          "model":{"type":"minecraft:shield"}}}
                """);
        check("special model records its base",
                special.size() == 1 && special.get(0).modelId().equals("minecraft:item/shield"));

        // Threshold formatting is hand-rolled to keep String.format and its
        // regex out of a hot path, so the cases it has to get right are pinned
        // here — particularly values below 0.1, where zero-padding matters.
        check("whole thresholds lose the decimal point", threshold(1.0F).endsWith("1"));
        check("0.65 formats exactly", threshold(0.65F).endsWith("0.65"));
        check("0.9 keeps one digit", threshold(0.9F).endsWith("0.9"));
        check("0.058 is not truncated to 0.58", threshold(0.058F).endsWith("0.058"));
        check("0.001 keeps its leading zeros", threshold(0.001F).endsWith("0.001"));
        check("12.25 formats exactly", threshold(12.25F).endsWith("12.25"));
        check("100 formats as a whole number", threshold(100.0F).endsWith("100"));
        check("zero formats as zero", threshold(0.0F).endsWith("0"));
    }

    /** The rendered description of a range-dispatch entry at one threshold. */
    private static String threshold(float value) {
        return new Selector(Selector.Kind.RANGE_DISPATCH, "minecraft:pull",
                List.of(), value, 1.0F, Selector.Branch.MATCH, 0).describe();
    }

    // -------------------------------------------------------------- resolver

    private static void resolverTests() {
        header("Model resolution");

        MemorySource source = new MemorySource();
        source.model("p:item/base", """
                {"parent":"minecraft:item/generated","textures":{"layer0":"p:item/tex"},
                 "display":{"gui":{"rotation":[0,0,0],"scale":[1,1,1]}}}""");
        source.model("p:item/child", "{\"parent\":\"p:item/base\"}");
        source.model("p:item/override",
                "{\"parent\":\"p:item/base\",\"textures\":{\"layer0\":\"p:item/other\"}}");
        source.texture("p:item/tex", 16, 16);
        source.texture("p:item/other", 16, 16);

        ModelResolver resolver = new ModelResolver(source);

        ResolvedModel child = resolver.resolve("p:item/child");
        check("child inherits parent textures",
                "p:item/tex".equals(child.textureSlots().get("layer0")));
        check("parent chain is recorded",
                child.parentChain().contains("p:item/base"));
        check("generated root is detected", child.generated());
        check("layer list is built", child.layers().equals(List.of("p:item/tex")));

        ResolvedModel override = resolver.resolve("p:item/override");
        check("child texture wins over parent",
                "p:item/other".equals(override.textureSlots().get("layer0")));

        // Texture variable indirection.
        source.model("p:item/ref", """
                {"parent":"minecraft:item/generated",
                 "textures":{"all":"p:item/tex","layer0":"#all"}}""");
        check("#references resolve",
                "p:item/tex".equals(resolver.resolve("p:item/ref").textureSlots().get("layer0")));

        source.model("p:item/dangling", """
                {"parent":"minecraft:item/generated","textures":{"layer0":"#nope"}}""");
        ResolvedModel dangling = resolver.resolve("p:item/dangling");
        check("dangling #reference is reported",
                dangling.unresolvedSlots().size() == 1 && dangling.layers().isEmpty());

        // Circular parents must terminate and be reported.
        source.model("p:item/a", "{\"parent\":\"p:item/b\"}");
        source.model("p:item/b", "{\"parent\":\"p:item/a\"}");
        ResolvedModel circular = resolver.resolve("p:item/a");
        check("circular parent chain terminates",
                circular.warnings().stream().anyMatch(w -> w.contains("Circular")));

        check("missing model reports as missing",
                !resolver.resolve("p:item/nothing").exists());

        // The 26.x expanded texture slot form.
        source.model("p:item/expanded", """
                {"parent":"minecraft:item/generated",
                 "textures":{"layer0":{"sprite":"p:item/tex","force_translucent":true}}}""");
        ResolvedModel expanded = resolver.resolve("p:item/expanded");
        check("object-form texture slot is read",
                "p:item/tex".equals(expanded.textureSlots().get("layer0")));
        check("force_translucent is recorded",
                expanded.translucentSlots().contains("layer0"));

        // Geometry does not merge; the nearest declaring ancestor supplies all.
        source.model("p:item/geo", """
                {"elements":[{"from":[0,0,0],"to":[16,16,16],
                  "faces":{"up":{"texture":"#all"}}}],"textures":{"all":"p:item/tex"}}""");
        source.model("p:item/geo_child",
                "{\"parent\":\"p:item/geo\",\"textures\":{\"all\":\"p:item/other\"}}");
        ResolvedModel geoChild = resolver.resolve("p:item/geo_child");
        // Building a mesh must never go back to the pack. It used to, on a
        // preview worker thread while the game was drawing, and the run that
        // followed filled the log with ClosedChannelException from a pack whose
        // zip had been closed underneath it.
        source.model("p:item/geo_cached", """
                {"elements":[{"from":[0,0,0],"to":[16,16,16],
                  "faces":{"up":{"texture":"#all"}}}],"textures":{"all":"p:item/tex"}}""");
        ResolvedModel cachedGeo = resolver.resolve("p:item/geo_cached");

        // Textures are the other half. Geometry was fixed first and the error
        // count fell from 9,162 to 15; the fifteen that remained were the
        // vanilla jar being read for textures while the game was drawing.
        check("a mesh will not read a texture off the allowed thread",
                meshWithoutTextureAccess(source, resolver));
        check("a build reports the textures it could not read",
                reportsUnavailableTextures(source, resolver));

        resolver.forgetParsed();
        source.seal();
        try {
            check("geometry survives the parse cache being dropped",
                    resolver.loadElements(cachedGeo).size() == 1);
            check("re-reading geometry does not touch the pack", source.opens() == 0);
        } finally {
            source.unseal();
        }
        check("the geometry cache is small enough to keep",
                resolver.geometryCacheBytes() > 0
                        && resolver.geometryCacheBytes() < 4096);

        check("geometry is inherited whole",
                geoChild.elementCount() == 1);
        check("inherited geometry points back at the ancestor that declared it",
                "p:item/geo".equals(geoChild.geometryModelId()));
        check("inherited geometry can be read back on demand",
                resolver.loadElements(geoChild).size() == 1);
        check("a child that inherits geometry hashes the same as its parent",
                geoChild.geometryHash().equals(resolver.resolve("p:item/geo").geometryHash()));
        check("inherited geometry uses the child's texture",
                "p:item/other".equals(geoChild.textureSlots().get("all")));

        check("missing texture is reported once used",
                resolver.resolve("p:item/base").missingTextures().isEmpty());

        int before = resolver.parsedCount();
        resolver.resolve("p:item/child");
        check("resolution is memoised", resolver.parsedCount() == before);
    }

    // -------------------------------------------------------------- grouping

    private static void groupingTests() {
        header("State grouping");

        MemorySource source = new MemorySource();
        String bowTree = """
                {"model":{"type":"minecraft:select","property":"minecraft:custom_model_data",
                  "cases":[
                    {"when":"dragon_bow","model":%s},
                    {"when":"frost_bow","model":%s}],
                  "fallback":{"type":"minecraft:model","model":"minecraft:item/bow"}}}
                """.formatted(pullTree("p:item/dragon"), pullTree("p:item/frost"));
        // Each state gets its own texture, as a real pack would have. Sharing
        // one texture across both bows would make them genuine duplicates, and
        // the deduplicator would be right to merge them.
        for (String name : List.of("dragon", "frost")) {
            for (String suffix : List.of("", "-1", "-2", "-3")) {
                source.model("p:item/" + name + suffix,
                        "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"p:t/"
                                + name + suffix + "\"}}");
                source.texture("p:t/" + name + suffix, 16, 16);
            }
        }
        source.model("minecraft:item/bow",
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"p:t/vanilla\"}}");
        source.texture("p:t/vanilla", 16, 16);
        source.vanilla("minecraft:item/bow");

        List<LogicalModel> models = build(source, "minecraft:bow", bowTree);

        check("two custom bows become two cards", models.size() == 2);
        check("each bow keeps its four states",
                models.stream().allMatch(m -> m.states().size() == 4));
        check("grouping is structural, not a guess",
                models.stream().allMatch(m -> m.groupReason().isStructural()));
        check("grouping is high confidence",
                models.stream().allMatch(m ->
                        m.groupReason().confidence() == GroupReason.Confidence.HIGH));

        LogicalModel dragon = models.stream()
                .filter(m -> m.displayName().equals("Dragon Bow")).findFirst().orElseThrow();
        List<String> labels = dragon.states().stream().map(ModelState::label).toList();
        check("state labels read as pull stages",
                labels.equals(List.of("Default", "Pull 1", "Pull 2", "Pull 3")));
        check("resting state is the card's default",
                dragon.defaultState().modelId().equals("p:item/dragon"));
        check("names come from the custom model data value",
                dragon.displayName().equals("Dragon Bow"));

        // The frames the detail view plays, and the order it plays them in.
        List<Integer> frames = dragon.animationFrames();
        check("a bow's states can be played as frames", frames.size() == 4);
        check("the animation starts from the item at rest",
                frames.get(0) == dragon.defaultStateIndex());
        check("and runs through the draw in the order the game would",
                frames.stream().map(i -> dragon.states().get(i).label()).toList()
                        .equals(List.of("Default", "Pull 1", "Pull 2", "Pull 3")));

        // A composite's states are parts drawn together. Playing them would
        // take the model apart, so it is not played at all — which is almost
        // every multi-state model there is.
        MemorySource parts = new MemorySource();
        parts.model("p:item/part_a",
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"p:a\"}}");
        parts.model("p:item/part_b",
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"p:b\"}}");
        parts.solidTexture("p:a", 16, 16, 0xFF884422);
        parts.solidTexture("p:b", 16, 16, 0xFF224488);
        List<LogicalModel> composite = build(parts, "minecraft:stick", """
                {"model":{"type":"minecraft:composite","models":[
                  {"type":"minecraft:model","model":"p:item/part_a"},
                  {"type":"minecraft:model","model":"p:item/part_b"}]}}
                """);
        check("a composite is one model with its parts as states",
                composite.size() == 1 && composite.get(0).states().size() == 2);
        check("its parts are never played as frames",
                composite.get(0).animationFrames().isEmpty());
        check("a model with one state has nothing to play",
                build(parts, "minecraft:stick", """
                        {"model":{"type":"minecraft:model","model":"p:item/part_a"}}
                        """).get(0).animationFrames().isEmpty());

        // A vanilla fallback must not become a card of its own.
        check("vanilla fallback is not listed",
                models.stream().noneMatch(m -> m.baseItemId().equals("minecraft:bow")
                        && m.customModelData().isEmpty()));

        // A pack that replaces the vanilla bow outright should be listed.
        MemorySource replaced = new MemorySource();
        replaced.model("minecraft:item/bow",
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"p:t\"}}");
        replaced.texture("p:t", 16, 16);
        // Left unmarked as vanilla: the pack supplies this file, which is what
        // "the pack replaced a vanilla appearance" means.
        List<LogicalModel> overridden = build(replaced, "minecraft:bow",
                "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/bow\"}}");
        check("a replaced vanilla model is listed", overridden.size() == 1);

        // The gui display context wins when a definition names one.
        MemorySource trident = new MemorySource();
        trident.model("p:item/gui_form",
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"p:gui\"}}");
        trident.model("p:item/hand_form",
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"p:hand\"}}");
        trident.texture("p:gui", 16, 16);
        trident.texture("p:hand", 16, 16);
        List<LogicalModel> contexts = build(trident, "minecraft:trident", """
                {"model":{"type":"minecraft:select","property":"minecraft:display_context",
                  "cases":[{"when":["gui","ground"],
                            "model":{"type":"minecraft:model","model":"p:item/gui_form"}}],
                  "fallback":{"type":"minecraft:model","model":"p:item/hand_form"}}}
                """);
        check("the gui display context is preferred for the card",
                contexts.size() == 1
                        && contexts.get(0).defaultState().modelId().equals("p:item/gui_form"));
    }

    private static String pullTree(String base) {
        return """
                {"type":"minecraft:condition","property":"minecraft:using_item",
                 "on_false":{"type":"minecraft:model","model":"%s"},
                 "on_true":{"type":"minecraft:range_dispatch",
                   "property":"minecraft:use_duration","scale":0.05,
                   "fallback":{"type":"minecraft:model","model":"%s-1"},
                   "entries":[
                     {"threshold":0.65,"model":{"type":"minecraft:model","model":"%s-2"}},
                     {"threshold":0.9,"model":{"type":"minecraft:model","model":"%s-3"}}]}}
                """.formatted(base, base, base, base);
    }

    // --------------------------------------------------------- deduplication

    private static void deduplicationTests() {
        header("Duplicate detection");

        MemorySource source = new MemorySource();
        source.model("p:item/blade",
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"p:t\"}}");
        // A different file that resolves to exactly the same thing.
        source.model("p:item/blade_copy",
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"p:t\"}}");
        // Same shape, different texture: not a duplicate.
        source.model("p:item/blade_red",
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"p:red\"}}");
        source.texture("p:t", 16, 16);
        source.texture("p:red", 16, 16);

        List<LogicalModel> models = build(source, "minecraft:diamond_sword", """
                {"model":{"type":"minecraft:select","property":"minecraft:custom_model_data",
                  "cases":[
                    {"when":"blade","model":{"type":"minecraft:model","model":"p:item/blade"}},
                    {"when":"blade_again","model":{"type":"minecraft:model","model":"p:item/blade"}},
                    {"when":"blade_copy","model":{"type":"minecraft:model","model":"p:item/blade_copy"}},
                    {"when":"blade_red","model":{"type":"minecraft:model","model":"p:item/blade_red"}}]}}
                """);

        check("identical resolutions collapse to one card",
                models.stream().filter(m -> m.geometryHash().equals(
                        models.get(0).geometryHash())
                        && m.textureHash().equals(models.get(0).textureHash())).count() == 1);
        check("a different texture is not a duplicate", models.size() == 2);

        LogicalModel survivor = models.stream()
                .filter(m -> !m.mergedReferences().isEmpty()).findFirst().orElseThrow();
        check("merged references are preserved, not discarded",
                survivor.mergedReferences().size() == 2);
        check("every alias is still findable",
                survivor.searchBlob().contains("blade_again")
                        && survivor.searchBlob().contains("blade_copy"));
    }

    // -------------------------------------------------------------- heuristic

    private static void heuristicTests() {
        header("Filename heuristic");

        check("bow-1 is a state of bow", "bow".equals(GraphBuilder.stateStem("bow-1")));

        // ItemsAdder's render variants: one appearance, five files, differing
        // only in whose camera they are drawn for.
        check("a cosmetic variant is a state of its base",
                "wings_v2_1".equals(GraphBuilder.stateStem("wings_v2_1_cosmetic")));
        check("so is the self view",
                "wings_v2_1".equals(GraphBuilder.stateStem("wings_v2_1_cosmetic_self")));
        check("and the numbered self view",
                "wings_v2_1".equals(GraphBuilder.stateStem("wings_v2_1_cosmetic_self_2")));
        check("and the numbered normal view",
                "wings_v2_1".equals(GraphBuilder.stateStem("wings_v2_1_cosmetic_normal_2")));
        check("a name that merely contains the word is not split",
                GraphBuilder.stateStem("cosmetic_crown") == null);

        // The variant tier on its own: only a render suffix answers here, so
        // an ordinary state suffix must not.
        check("a render variant is recognised as one",
                "wings_v2_1".equals(GraphBuilder.variantStem("wings_v2_1_cosmetic_self")));
        check("a draw stage is not a render variant",
                GraphBuilder.variantStem("bow_pulling_2") == null);
        check("nor is a plain number",
                GraphBuilder.variantStem("bow_1") == null);
        check("and a leading occurrence is not a suffix",
                GraphBuilder.variantStem("cosmetic_crown") == null);

        // The other two variant families in the same packs.
        check("a self view is a variant",
                "back".equals(GraphBuilder.variantStem("back_self")));
        check("so is a numbered self view",
                "back".equals(GraphBuilder.variantStem("back_self_2")));
        check("and a normal view",
                "back".equals(GraphBuilder.variantStem("back_normal_2")));

        // Stacked suffixes must cut at the first one, or the family splits:
        // "x_cosmetic" is a stem no base model has.
        // A marker has to end a word. Without that, "cosmeticscore" reads as
        // "cosmetic" and folds one set into another that shares a prefix.
        check("a marker inside a longer word is not a marker",
                GraphBuilder.variantStem("wings_cosmeticscore") == null);
        check("but the same name's real marker still counts",
                "wings_cosmeticscore".equals(
                        GraphBuilder.variantStem("wings_cosmeticscore_self_2")));
        check("a plural marker still ends a word before its layer number",
                "backpack_cosmetics".equals(
                        GraphBuilder.variantStem("backpack_cosmetics_normal_2")));

        check("stacked suffixes cut at the earliest",
                "wings_v2_1".equals(
                        GraphBuilder.variantStem("wings_v2_1_cosmetic_self_2")));
        check("bow_pulling_2 is a state of bow",
                "bow".equals(GraphBuilder.stateStem("bow_pulling_2")));
        check("shield_blocking is a state of shield",
                "shield".equals(GraphBuilder.stateStem("shield_blocking")));
        check("crossbow_pulling_0 is a state of crossbow",
                "crossbow".equals(GraphBuilder.stateStem("crossbow_pulling_0")));

        // These must NOT be treated as states, or a catalogue of similarly
        // named models collapses into one card. This is the case that a naive
        // implementation gets wrong.
        check("sword_0000 is a catalogue index, not a state",
                GraphBuilder.stateStem("sword_0000") == null);
        check("item_0123 is a catalogue index, not a state",
                GraphBuilder.stateStem("item_0123") == null);
        check("a three-digit suffix is not a state",
                GraphBuilder.stateStem("thing_100") == null);
        check("a bare name has no state suffix",
                GraphBuilder.stateStem("dragon_sword") == null);
        check("a number with no separator is not a suffix",
                GraphBuilder.stateStem("model3") == null);
        check("a stem ending in a digit is rejected",
                GraphBuilder.stateStem("sword_2_3") == null);
        check("a word stem before a short index is accepted",
                "model_3d".equals(GraphBuilder.stateStem("model_3d_2")));

        // End to end: two models related only by name.
        MemorySource source = new MemorySource();
        source.model("p:item/staff",
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"p:a\"}}");
        source.model("p:item/staff_active",
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"p:b\"}}");
        source.texture("p:a", 16, 16);
        source.texture("p:b", 16, 16);
        String definition = """
                {"model":{"type":"minecraft:select","property":"minecraft:custom_model_data",
                  "cases":[
                    {"when":"staff","model":{"type":"minecraft:model","model":"p:item/staff"}},
                    {"when":"staff_active","model":{"type":"minecraft:model","model":"p:item/staff_active"}}]}}
                """;

        List<LogicalModel> grouped = build(source, "minecraft:stick", definition, true);
        check("filename relationship groups when enabled", grouped.size() == 1);
        check("a filename grouping is marked low confidence",
                grouped.get(0).groupReason() == GroupReason.FILENAME_HEURISTIC
                        && grouped.get(0).groupReason().confidence()
                        == GroupReason.Confidence.LOW);

        List<LogicalModel> separate = build(source, "minecraft:stick", definition, false);
        check("filename grouping can be turned off", separate.size() == 2);

        // One model, reached from three different items. A pack does this on a
        // scale that turned 10,732 skins into 55,642 things to scroll past.
        MemorySource shared = new MemorySource();
        shared.model("p:item/gem",
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"p:g\"}}");
        shared.texture("p:g", 16, 16);
        // Different thresholds, so these are genuinely different wraps of the
        // same skin rather than duplicates the builder already removes.
        String points = """
                {"model":{"type":"minecraft:range_dispatch",
                  "property":"minecraft:custom_model_data",
                  "entries":[{"threshold":%d,
                    "model":{"type":"minecraft:model","model":"p:item/gem"}}],
                  "fallback":{"type":"minecraft:model","model":"minecraft:item/stick"}}}
                """;
        List<DiscoveredModel> many = new ArrayList<>();
        many.addAll(parse("minecraft:stick", points.formatted(1)));
        many.addAll(parse("minecraft:diamond_sword", points.formatted(2)));
        many.addAll(parse("p:not_an_item", points.formatted(3)));
        List<LogicalModel> routes = newBuilder(shared, true).build(many);
        List<LogicalModel> distinct = GraphBuilder.collapseToDistinctModels(routes);
        check("every route to a model is kept for the catalogue",
                routes.stream().filter(m -> m.defaultState().modelId().equals("p:item/gem"))
                        .count() == 3);
        check("the browse list holds one entry per model",
                distinct.stream().filter(m -> m.defaultState().modelId().equals("p:item/gem"))
                        .count() == 1);
        LogicalModel gem = distinct.stream()
                .filter(m -> m.defaultState().modelId().equals("p:item/gem"))
                .findFirst().orElseThrow();
        check("the kept entry is one a real item carries", gem.baseItemIsRegistered());
        check("the merged carriers are recorded, not discarded",
                gem.alsoCarriedBy().size() == 1
                        && gem.alsoCarriedBy().contains("minecraft:diamond_sword"));
        check("a model with one route gains no carrier list",
                distinct.stream()
                        .filter(m -> !m.defaultState().modelId().equals("p:item/gem"))
                        .allMatch(m -> m.alsoCarriedBy().isEmpty()));

    }

    // ------------------------------------------------------------- naming

    private static void namingTests() {
        header("Naming and item identity");

        // A range dispatch's case value is a threshold. Naming a card "7" is
        // useless, so the model path is preferred; and where the file name is
        // itself a number, the folder carries the meaning.
        check("a plain number is recognised as numeric", GraphBuilder.isNumeric("7"));
        check("a decimal threshold is numeric", GraphBuilder.isNumeric("0.65"));
        check("a word is not numeric", !GraphBuilder.isNumeric("dragon_bow"));
        check("a name with digits in it is not numeric",
                !GraphBuilder.isNumeric("bow_2_variant"));
        check("empty is not numeric", !GraphBuilder.isNumeric(""));

        check("a meaningful folder is kept",
                "lr_healthbar".equals(GraphBuilder.lastFolderOf("ns:mob/lr_healthbar/12")));
        check("a generic folder is dropped",
                GraphBuilder.lastFolderOf("ns:item/3").isEmpty());
        check("a block folder is dropped",
                GraphBuilder.lastFolderOf("ns:block/7").isEmpty());

        MemorySource source = new MemorySource();
        source.model("p:mob/healthbar/12",
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"p:t\"}}");
        source.texture("p:t", 16, 16);
        List<LogicalModel> numbered = build(source, "minecraft:paper", """
                {"model":{"type":"minecraft:range_dispatch",
                  "property":"minecraft:custom_model_data",
                  "entries":[{"threshold":12,"model":{"type":"minecraft:model",
                              "model":"p:mob/healthbar/12"}}]}}
                """);
        check("a numeric threshold does not become the card's name",
                numbered.size() == 1 && !numbered.get(0).displayName().equals("12"));
        check("the folder identifies a numerically named model",
                numbered.get(0).displayName().equals("Healthbar 12"));

        // An item definition outside the minecraft namespace is an item_model
        // target, not an item. Claiming a base item for it would be a fiction.
        List<LogicalModel> real = build(source, "minecraft:paper", """
                {"model":{"type":"minecraft:select","property":"minecraft:custom_model_data",
                  "cases":[{"when":"x","model":{"type":"minecraft:model",
                            "model":"p:mob/healthbar/12"}}]}}
                """);
        // Item tags, against the ids Minecraft actually uses. The nested
        // enchantable tags are the ones that were previously spelled wrong.
        check("a fishing rod is found by its enchantable tag",
                ItemType.fromTags(java.util.Set.of("minecraft:enchantable/fishing",
                        "minecraft:enchantable/durability")) == ItemType.FISHING_ROD);
        check("the fishing tag beats the durability tag it also carries",
                ItemType.fromTags(java.util.Set.of("minecraft:enchantable/durability",
                        "minecraft:enchantable/fishing")) == ItemType.FISHING_ROD);
        check("a fishing rod is found by its item id",
                ItemType.fromItemId("minecraft:fishing_rod") == ItemType.FISHING_ROD);
        check("shears stay a tool", ItemType.fromItemId("minecraft:shears") == ItemType.TOOL);
        check("a bow is found by its enchantable tag",
                ItemType.fromTags(java.util.Set.of("minecraft:enchantable/bow")) == ItemType.BOW);
        check("a crossbow is found by its enchantable tag",
                ItemType.fromTags(java.util.Set.of("minecraft:enchantable/crossbow")) == ItemType.CROSSBOW);
        check("a sword tag still wins",
                ItemType.fromTags(java.util.Set.of("minecraft:swords",
                        "minecraft:enchantable/durability")) == ItemType.SWORD);
        check("durability alone is no longer answered by the specific tags",
                ItemType.fromTags(java.util.Set.of("minecraft:enchantable/durability")) == null);
        check("it is answered last, by the generic ones",
                ItemType.fromGenericTags(
                        java.util.Set.of("minecraft:enchantable/durability")) == ItemType.TOOL);
        check("so a shield is a shield",
                ItemType.fromItemId("minecraft:shield") == ItemType.SHIELD
                        && ItemType.fromItemId("someone:obsidian_shield") == ItemType.SHIELD);

        // Compound names. This pack writes "strawhat" and "tribalhelmet1", and
        // whole-token matching saw neither, so 49 pieces of headwear were
        // reaching their category by tiebreak rather than by being read.
        check("a hat inside a longer word is still a hat",
                GraphBuilder.fromModelPath("p:item/farm_strawhat") == ItemType.HELMET);
        check("a helmet inside a longer word is still a helmet",
                GraphBuilder.fromModelPath("p:item/s5_tribalhelmet1") == ItemType.HELMET);
        check("a circlet is headwear",
                GraphBuilder.fromModelPath("p:item/magic_circlet") == ItemType.HELMET);
        check("a headpiece is headwear",
                GraphBuilder.fromModelPath("p:item/zodiac_headpiece") == ItemType.HELMET);
        // The traps that keep compound matching to a curated list.
        check("a rainbow is not a bow",
                GraphBuilder.fromModelPath("p:item/rainbow_hat") == ItemType.HELMET);
        check("a phoenix is not a hoe",
                GraphBuilder.fromModelPath("p:item/phoenix_wings") == null);

        // "Held" words. The pack does not call its pickaxes pickaxes.
        check("a breaker is held", GraphBuilder.namesHeldTool("p:item/vault_breaker"));
        check("a crusher is held", GraphBuilder.namesHeldTool("p:item/permafrost_crusher"));
        check("a ripper is held", GraphBuilder.namesHeldTool("p:item/reindeer_ripper"));
        check("a burrower is held", GraphBuilder.namesHeldTool("p:item/appleburrower"));
        check("a sunpiercer is held", GraphBuilder.namesHeldTool("p:item/egypt_sunpiercer"));
        check("a mace is held", GraphBuilder.namesHeldTool("p:item/acorn_mace"));
        check("a hat is not held", !GraphBuilder.namesHeldTool("p:item/farm_strawhat"));
        check("a bear is not held", !GraphBuilder.namesHeldTool("p:item/purgebear"));

        // Names. "rod" on its own is a wand far more often than it is tackle.
        check("a fishing rod is read from its name",
                GraphBuilder.fromModelPath("p:item/emerald_fishing_rod") == ItemType.FISHING_ROD);
        check("a fishing pole is read from its name",
                GraphBuilder.fromModelPath("p:item/fishing_pole_1") == ItemType.FISHING_ROD);
        check("a bare rod is still a tool",
                GraphBuilder.fromModelPath("p:item/oak_rod") == ItemType.TOOL);
        check("a wand is still a tool",
                GraphBuilder.fromModelPath("p:item/frost_wand") == ItemType.TOOL);

        check("a minecraft item is a registered base item",
                real.get(0).baseItemIsRegistered());

        List<LogicalModel> target = build(source, "lf:count_paper", """
                {"model":{"type":"minecraft:select","property":"minecraft:custom_model_data",
                  "cases":[{"when":"x","model":{"type":"minecraft:model",
                            "model":"p:mob/healthbar/12"}}]}}
                """);
        check("a pack-namespace definition is not a base item",
                !target.get(0).baseItemIsRegistered());
        check("the label says so rather than inventing an item",
                target.get(0).baseItemLabel().contains("item_model"));

        ExplorerQuery realOnly = ExplorerQuery.EMPTY.withOnlyRealItems(true);
        check("the real-items filter keeps registered base items",
                realOnly.apply(real).size() == 1);
        check("the real-items filter drops item_model targets",
                realOnly.apply(target).isEmpty());
    }

    /** Favourites, sets and the headings they are filed under. */
    private static void setsTests() {
        header("Sets and favourites");

        dev.custommodels.cmv.collection.SkinSets sets =
                dev.custommodels.cmv.collection.SkinSets.inMemory();

        check("nothing is a favourite to begin with",
                !sets.isFavourite("p:item/crown") && sets.favourites().isEmpty());
        check("starring adds it", sets.toggleFavourite("p:item/crown"));
        check("and it is remembered", sets.isFavourite("p:item/crown"));
        sets.toggleFavourite("p:item/sword");
        check("the newest is first",
                sets.favourites().get(0).equals("p:item/sword"));
        check("starring again takes it off", !sets.toggleFavourite("p:item/crown"));
        check("and it is gone", !sets.isFavourite("p:item/crown"));

        // A set under a heading the player names.
        dev.custommodels.cmv.collection.SkinSets.Set poseidon =
                sets.createSet("Poseidon", "Elite sets");
        dev.custommodels.cmv.collection.SkinSets.Set rogue =
                sets.createSet("Rogue", "Elite sets");
        check("a set is created", poseidon != null && rogue != null);
        check("the heading is made with it",
                sets.groupOf(poseidon) != null
                        && sets.groupOf(poseidon).name.equals("Elite sets"));
        check("both are filed under it", sets.groupOf(rogue) == sets.groupOf(poseidon));
        check("and counted once", sets.setCount() == 2);

        check("a model can be put in a set", sets.toggleMember(poseidon, "p:item/crown"));
        sets.toggleMember(poseidon, "p:item/trident");
        check("membership is remembered", sets.contains(poseidon, "p:item/crown"));
        check("in the order it was added",
                poseidon.members.equals(List.of("p:item/crown", "p:item/trident")));
        check("a model knows which sets it is in",
                sets.setsContaining("p:item/crown").equals(List.of(poseidon)));
        check("adding again removes it", !sets.toggleMember(poseidon, "p:item/crown"));

        // Order is the player's, so it can be changed.
        check("a set moves down its heading", sets.moveSet(poseidon, 1));
        check("and the order changes",
                sets.groupOf(poseidon).sets.equals(List.of(rogue, poseidon)));
        check("it will not move off the end", !sets.moveSet(poseidon, 1));
        check("a member can be reordered",
                sets.toggleMember(poseidon, "p:item/crown")
                        && sets.moveMember(poseidon, "p:item/crown", -1)
                        && poseidon.members.get(0).equals("p:item/crown"));

        // Deleting a heading is a filing decision, not a way to lose sets.
        sets.deleteGroup(sets.groupOf(poseidon));
        check("deleting a heading keeps its sets", sets.setCount() == 2);
        check("which move to the unnamed group",
                sets.groupOf(poseidon) != null && sets.groupOf(poseidon).name.isEmpty());

        sets.deleteSet(rogue);
        check("deleting a set removes it", sets.setCount() == 1);

        // Filing a set under a heading that already exists, which is the point
        // of headings: they are made once and used afterwards.
        dev.custommodels.cmv.collection.SkinSets.Group season =
                sets.createGroup("Season 3");
        sets.moveSetToGroup(poseidon, "Season 3");
        check("a set moves into an existing heading",
                sets.groupOf(poseidon) == season && season.sets.contains(poseidon));
        check("and leaves the one it came from",
                sets.group("").sets.isEmpty());
        check("its contents come with it", !poseidon.members.isEmpty());
        sets.moveSetToGroup(poseidon, "");
        check("and it can be filed under none of them",
                sets.groupOf(poseidon).name.isEmpty());

        check("a heading starts open", !season.collapsed);
        sets.toggleCollapsed(season);
        check("folding it is remembered", season.collapsed);
        sets.toggleCollapsed(season);
        check("and so is unfolding it", !season.collapsed);

        StringBuilder tooLong = new StringBuilder();
        while (tooLong.length() < 200) {
            tooLong.append("name ");
        }
        sets.renameSet(poseidon, tooLong.toString());
        check("a name is held to a sane length",
                poseidon.name.length()
                        <= dev.custommodels.cmv.collection.SkinSets.MAX_NAME_LENGTH);
        check("an empty id is never stored",
                !sets.toggleFavourite("") && sets.favourites().size() == 1);

        favouriteIndexTests();
        menuLayoutTests();
    }

    /**
     * The favourites are asked about per card per frame, so the answer comes
     * from a set rather than a walk down the list. The two must not drift.
     */
    private static void favouriteIndexTests() {
        dev.custommodels.cmv.collection.SkinSets sets =
                dev.custommodels.cmv.collection.SkinSets.inMemory();
        sets.toggleFavourite("a");
        sets.toggleFavourite("b");
        check("both are found after adding", sets.isFavourite("a") && sets.isFavourite("b"));
        sets.toggleFavourite("a");
        check("one removed is not found", !sets.isFavourite("a"));
        check("and the other still is", sets.isFavourite("b"));
        sets.toggleFavourite("a");
        check("adding it back finds it again", sets.isFavourite("a"));
        check("the list and the answer agree",
                sets.favourites().size() == 2
                        && sets.favourites().contains("a") && sets.favourites().contains("b"));

        // A file is allowed to be hand-edited, so the limit has to hold when it
        // is reached from the interface too.
        int added = 0;
        for (int i = 0; i < 2600; i++) {
            if (sets.toggleFavourite("model/" + i)) {
                added++;
            }
        }
        check("favourites stop at their limit", sets.favourites().size() <= 2000);
        check("and the refusal is reported rather than silent",
                added < 2600 && !sets.isFavourite("model/2599"));
    }

    /**
     * The menu's box has to be tall enough for what is in it.
     *
     * <p>The create row and the height of the box were once worked out
     * separately, and the row ended up three pixels over the last set in the
     * list. The rule is the same at every length, so it is checked at every
     * length.</p>
     */
    private static void menuLayoutTests() {
        final int rowHeight = 16;
        final int headerHeight = 33;
        boolean overlaps = false;
        boolean overflows = false;
        boolean tooTall = false;
        for (int rows = 1; rows <= 9; rows++) {
            dev.custommodels.cmv.wrap.CatalogueScreen.MenuLayout layout = dev.custommodels.cmv.wrap.CatalogueScreen.menuLayout(rows, true);
            int lastRowBottom = headerHeight + rows * rowHeight;
            if (layout.createY() < lastRowBottom) {
                overlaps = true;
            }
            if (layout.createY() + rowHeight > layout.height()) {
                overflows = true;
            }
            // A gap is wanted, but a chasm means something is being counted
            // twice.
            if (layout.createY() > lastRowBottom + rowHeight) {
                tooTall = true;
            }
        }
        check("the create row never covers the last set", !overlaps);
        check("and never hangs out of the box", !overflows);
        check("and is not floated off on its own", !tooTall);

        dev.custommodels.cmv.wrap.CatalogueScreen.MenuLayout none = dev.custommodels.cmv.wrap.CatalogueScreen.menuLayout(4, false);
        check("a menu with nothing to create is shorter for it",
                none.height() < dev.custommodels.cmv.wrap.CatalogueScreen.menuLayout(4, true).height());
        check("and still holds its rows",
                none.height() >= headerHeight + 4 * rowHeight);
    }

    private static void wrapTests() {
        header("Catalogue");

        MemorySource source = new MemorySource();
        source.model("p:item/weapons/void_blade",
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"p:t\"}}");
        source.model("p:cosmetic/hat",
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"p:t\"}}");
        source.texture("p:t", 16, 16);

        java.util.function.Predicate<String> exists = id -> id.startsWith("minecraft:");

        // A range dispatch on custom model data: numeric, read from floats.
        List<LogicalModel> numeric = build(source, "minecraft:diamond_sword", """
                {"model":{"type":"minecraft:range_dispatch",
                  "property":"minecraft:custom_model_data","index":2,
                  "entries":[{"threshold":469,"model":{"type":"minecraft:model",
                              "model":"p:item/weapons/void_blade"}}]}}
                """);
        WrapCatalogue numericCatalogue =
                WrapCatalogue.build(numeric, exists, "a");
        check("a range dispatch produces a wrap", numericCatalogue.all().size() == 1);

        WrapTarget floatWrap = numericCatalogue.all().get(0);
        check("a numeric dispatch writes custom model data",
                floatWrap.kind() == WrapTarget.Kind.CUSTOM_MODEL_DATA);
        check("a numeric dispatch writes exactly one value",
                floatWrap.customModelData().size() == 1);

        WrapTarget.CustomModelDataEntry entry = floatWrap.customModelData().get(0);
        // The bug this pins down: writing 469 into the string list instead of
        // the float list applies cleanly and changes nothing on screen.
        check("a range dispatch value goes in the float list", entry.numeric());
        check("the threshold is written exactly", entry.number() == 469.0F);
        check("the node's index is honoured, not assumed to be zero",
                entry.index() == 2);
        check("the summary names the list and index",
                floatWrap.componentSummary().contains("floats[2] = 469"));

        // A select on custom model data: textual, read from strings.
        List<LogicalModel> textual = build(source, "minecraft:diamond_sword", """
                {"model":{"type":"minecraft:select",
                  "property":"minecraft:custom_model_data","index":1,
                  "cases":[{"when":"void_blade","model":{"type":"minecraft:model",
                            "model":"p:item/weapons/void_blade"}}]}}
                """);
        WrapTarget stringWrap =
                WrapCatalogue.build(textual, exists, "b").all().get(0);
        WrapTarget.CustomModelDataEntry text = stringWrap.customModelData().get(0);
        check("a select value goes in the string list", !text.numeric());
        check("the case value is written exactly", "void_blade".equals(text.text()));
        check("the select index is honoured", text.index() == 1);

        // Compatibility comes from the definitions that reference the model.
        check("a wrap is compatible with the item that references it",
                floatWrap.isCompatibleWith("minecraft:diamond_sword"));
        check("a custom model data wrap is not compatible with other items",
                !floatWrap.isCompatibleWith("minecraft:bow"));
        check("the reason explains the compatibility",
                floatWrap.compatibilityReason("minecraft:diamond_sword")
                        .contains("diamond_sword"));

        // Four models sharing one number write the same component, so they
        // are one wrap, not four. This is what a bow's draw stages look like
        // when the grouping has not folded them into one another.
        MemorySource stages = new MemorySource();
        for (String name : List.of("bow", "bow_0", "bow_1", "bow_2")) {
            stages.model("p:item/" + name,
                    "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"p:t\"}}");
        }
        stages.texture("p:t", 16, 16);
        List<LogicalModel> shared = build(stages, "minecraft:bow", """
                {"model":{"type":"minecraft:range_dispatch",
                  "property":"minecraft:custom_model_data",
                  "entries":[
                    {"threshold":5,"model":{"type":"minecraft:model","model":"p:item/bow"}},
                    {"threshold":5,"model":{"type":"minecraft:model","model":"p:item/bow_0"}},
                    {"threshold":5,"model":{"type":"minecraft:model","model":"p:item/bow_1"}},
                    {"threshold":5,"model":{"type":"minecraft:model","model":"p:item/bow_2"}}]}}
                """);
        List<WrapTarget> folded = WrapCatalogue.build(shared, exists, "stages").all();
        check("models writing the same component become one tile",
                folded.size() == 1);
        check("and the base model is the one kept",
                folded.isEmpty() || folded.get(0).model().defaultState().modelId()
                        .equals("p:item/bow"));

        // Different values are different wraps and must survive. Unrelated
        // names, so the grouping does not fold them into one another first --
        // which it legitimately does for a family like bow/bow_0, and which is
        // a different mechanism from the one under test here.
        // Distinct textures as well as distinct names: identical geometry is
        // deduplicated earlier, which would hide what this is checking.
        stages.texture("p:t2", 16, 16);
        stages.texture("p:t3", 16, 16);
        stages.model("p:item/oaken_lash",
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"p:t2\"}}");
        stages.model("p:item/tidecaller",
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"p:t3\"}}");
        List<LogicalModel> distinct = build(stages, "minecraft:bow", """
                {"model":{"type":"minecraft:range_dispatch",
                  "property":"minecraft:custom_model_data",
                  "entries":[
                    {"threshold":5,"model":{"type":"minecraft:model","model":"p:item/oaken_lash"}},
                    {"threshold":6,"model":{"type":"minecraft:model","model":"p:item/tidecaller"}}]}}
                """);
        check("wraps writing different numbers are left alone",
                WrapCatalogue.build(distinct, exists, "distinct").all().size() == 2);

        // Same number, unrelated names: still one write, so still one tile.
        List<LogicalModel> sameValue = build(stages, "minecraft:bow", """
                {"model":{"type":"minecraft:range_dispatch",
                  "property":"minecraft:custom_model_data",
                  "entries":[
                    {"threshold":9,"model":{"type":"minecraft:model","model":"p:item/oaken_lash"}},
                    {"threshold":9,"model":{"type":"minecraft:model","model":"p:item/tidecaller"}}]}}
                """);
        check("unrelated models sharing one number are still one tile",
                WrapCatalogue.build(sameValue, exists, "samevalue").all().size() == 1);

        // An appearance a pack offers only on a carrier item, and the
        // definition the mod generates so it can go somewhere useful.
        MemorySource stranded = new MemorySource();
        stranded.model("p:cosmetic/halo",
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"p:t\"}}");
        stranded.texture("p:t", 16, 16);

        List<DiscoveredModel> onPaper = ItemDefinitionParser.parse(
                "minecraft:paper", "minecraft:items/paper.json",
                "assets/minecraft/items/paper.json", "file/Test.zip",
                new StringReader("""
                {"model":{"type":"minecraft:range_dispatch",
                  "property":"minecraft:custom_model_data",
                  "entries":[{"threshold":7,"model":{"type":"minecraft:model",
                              "model":"p:cosmetic/halo"}}]}}
                """)).models();

        WrapTarget paperOnly = WrapCatalogue.build(
                newBuilder(stranded, true).build(new ArrayList<>(onPaper)),
                exists, "carrier").all().get(0);
        check("without a generated route it is paper and nothing else",
                paperOnly.isCompatibleWith("minecraft:paper")
                        && !paperOnly.isCompatibleWith("minecraft:diamond_helmet"));

        // An item_model wrap: the pack does not say which item it belongs to.
        List<LogicalModel> universal = build(source, "p:cosmetic_hat", """
                {"model":{"type":"minecraft:model","model":"p:cosmetic/hat"}}
                """);
        WrapCatalogue universalCatalogue =
                WrapCatalogue.build(universal, exists, "c");
        check("a pack-namespace definition produces an item_model wrap",
                universalCatalogue.all().size() == 1
                        && universalCatalogue.all().get(0).kind()
                        == WrapTarget.Kind.ITEM_MODEL);
        WrapTarget universalWrap = universalCatalogue.all().get(0);
        // The harness files every definition as minecraft:items/x.json, so that
        // is what the item_model id is derived from. The point being checked is
        // that it comes from the definition rather than from the model.
        check("an item_model wrap points at the definition id, not the model",
                "minecraft:x".equals(universalWrap.itemModelId()));
        check("an item_model wrap with no stated item is universal",
                universalWrap.isUniversal());
        check("a universal wrap fits anything", universalWrap.isCompatibleWith("minecraft:bow"));
        check("and says the pack did not state an item",
                universalWrap.compatibilityReason("minecraft:bow").contains("does not say"));

        check("definition ids become item_model ids",
                "lf:count_paper".equals(
                        WrapCatalogue.definitionAsItemModelId("lf:items/count_paper.json")));
        check("a nested definition id keeps its path",
                "minecraft:poseidon_armor/chestplate".equals(
                        WrapCatalogue.definitionAsItemModelId(
                                "minecraft:items/poseidon_armor/chestplate.json")));

        // Strict compatibility must not offer universal wraps.
        List<LogicalModel> mixed = new ArrayList<>(numeric);
        mixed.addAll(universal);
        WrapCatalogue mixedCatalogue = WrapCatalogue.build(mixed, exists, "d");
        check("strict compatibility lists only stated matches",
                mixedCatalogue.compatibleWith("minecraft:diamond_sword", true).size() == 1);
        check("relaxed compatibility also lists universal wraps",
                mixedCatalogue.compatibleWith("minecraft:diamond_sword", false).size() == 2);
        check("the stated match is listed first",
                mixedCatalogue.compatibleWith("minecraft:diamond_sword", false)
                        .get(0).kind() == WrapTarget.Kind.CUSTOM_MODEL_DATA);

        check("search finds a wrap by name",
                mixedCatalogue.search(mixedCatalogue.all(), "void").size() == 1);
        check("search finds a wrap by base item",
                !mixedCatalogue.search(mixedCatalogue.all(), "diamond_sword").isEmpty());
        check("search excludes non-matches",
                mixedCatalogue.search(mixedCatalogue.all(), "nothinghere").isEmpty());

        perItemValueTests(source, exists);
        contestedValueTests(source, exists);
        packChosenIndexTests(source, exists);
        belongsOnTests(source, exists);
    }

    /**
     * Packs reach a model from every item it might ever be wanted on, so a
     * pickaxe's list of appearances fills up with hats.
     */
    private static void belongsOnTests(MemorySource source,
                                       java.util.function.Predicate<String> exists) {
        for (String name : List.of("royal_crown", "sturdy_pickaxe", "widget", "hammer_head")) {
            source.model("p:item/" + name, "{\"parent\":\"minecraft:item/generated\","
                    + "\"textures\":{\"layer0\":\"p:" + name + "\"}}");
        }
        source.solidTexture("p:royal_crown", 16, 16, 0xFF10FF20);
        source.solidTexture("p:sturdy_pickaxe", 16, 16, 0xFF20FF30);
        source.solidTexture("p:widget", 16, 16, 0xFF30FF40);
        source.solidTexture("p:hammer_head", 16, 16, 0xFF40FF50);

        // The crown and the widget are reached from both items, exactly as the
        // real pack does it; the hammer head only from the pickaxe.
        List<DiscoveredModel> discovered = new ArrayList<>(
                parseAs("minecraft:diamond_pickaxe", dispatch(
                        "1", "p:item/royal_crown",
                        "2", "p:item/sturdy_pickaxe",
                        "3", "p:item/widget",
                        "4", "p:item/hammer_head")));
        discovered.addAll(parseAs("minecraft:diamond_helmet", dispatch(
                "1", "p:item/royal_crown",
                "3", "p:item/widget")));

        java.util.function.Function<String, ItemType> kinds = id ->
                id.endsWith("_helmet") ? ItemType.HELMET
                        : id.endsWith("_pickaxe") ? ItemType.PICKAXE : ItemType.MISC;
        WrapCatalogue catalogue = WrapCatalogue.build(
                newBuilder(source, true).build(discovered), exists, kinds, "belongs");

        check("a hat is not offered on a pickaxe",
                !names(catalogue.compatibleWith("minecraft:diamond_pickaxe", true))
                        .contains("Royal Crown"));
        check("it is still offered where it belongs",
                names(catalogue.compatibleWith("minecraft:diamond_helmet", true))
                        .contains("Royal Crown"));
        check("and comes back when the compatibility filter is off",
                names(catalogue.compatibleWith("minecraft:diamond_pickaxe", false))
                        .contains("Royal Crown"));
        check("a pickaxe model stays on a pickaxe",
                names(catalogue.compatibleWith("minecraft:diamond_pickaxe", true))
                        .contains("Sturdy Pickaxe"));
        // A set-per-namespace layout names every file for what it is, so the
        // namespace is the only thing telling twenty-one pickaxes apart.
        check("a bare name at a namespace root takes the set's name",
                "Malachite Pickaxe".equals(
                        GraphBuilder.prettify("malachite") + " "
                                + GraphBuilder.prettify("pickaxe")));

        // Changed deliberately. This used to assert that a name saying nothing
        // meant no filtering, which left a pickaxe listing two hundred and
        // twenty-one hats: the packs offer wings and crowns on pickaxes so they
        // can be held, and none of those file names say "hat". The sidebar had
        // always classified them correctly and the filter ignored it. Now both
        // readings are consulted, so a wrap classified as headwear is not
        // offered on a pickaxe whatever its file is called.
        check("a wrap classified elsewhere is filtered even when its name is silent",
                !names(catalogue.compatibleWith("minecraft:diamond_pickaxe", true))
                        .contains("Widget"));
        check("and it is still offered where it does belong",
                names(catalogue.compatibleWith("minecraft:diamond_helmet", true))
                        .contains("Widget"));
        check("the relaxed list still has everything",
                names(catalogue.compatibleWith("minecraft:diamond_pickaxe", false))
                        .contains("Widget"));
        // "Hammer head" reads as headwear and is nothing of the sort. Nothing
        // is hidden unless the model is offered somewhere it does belong.
        check("a model with nowhere better to go is never hidden",
                names(catalogue.compatibleWith("minecraft:diamond_pickaxe", true))
                        .contains("Hammer Head"));
        check("hiding is off unless the game says what the item is",
                names(WrapCatalogue.build(newBuilder(source, true).build(discovered),
                                exists, "no-classifier")
                        .compatibleWith("minecraft:diamond_pickaxe", true))
                        .contains("Royal Crown"));
    }

    private static Set<String> names(List<WrapTarget> targets) {
        Set<String> out = new java.util.LinkedHashSet<>();
        for (WrapTarget target : targets) {
            out.add(target.displayName());
        }
        return out;
    }

    /**
     * The index a pack asks for is a number in a file, and writing at it means
     * filling every position below it first.
     */
    private static void packChosenIndexTests(MemorySource source,
                                             java.util.function.Predicate<String> exists) {
        source.model("p:item/huge_index",
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"p:huge\"}}");
        source.solidTexture("p:huge", 16, 16, 0xFF123456);

        for (String index : List.of("2000000000", "-4")) {
            List<LogicalModel> models = build(source, "minecraft:diamond_hoe", """
                    {"model":{"type":"minecraft:range_dispatch",
                      "property":"minecraft:custom_model_data","index":INDEX,
                      "entries":[{"threshold":7,"model":{"type":"minecraft:model",
                                  "model":"p:item/huge_index"}}]}}
                    """.replace("INDEX", index));
            WrapCatalogue catalogue = WrapCatalogue.build(models, exists, "index" + index);
            boolean offered = false;
            for (WrapTarget wrap : catalogue.all()) {
                if (!wrap.entriesFor("minecraft:diamond_hoe").isEmpty()) {
                    offered = true;
                }
            }
            check("an index of " + index + " is never written",
                    !offered);
        }

        // The ordinary case still works, so the guard has not shut the door.
        List<LogicalModel> fine = build(source, "minecraft:diamond_hoe", """
                {"model":{"type":"minecraft:range_dispatch",
                  "property":"minecraft:custom_model_data","index":3,
                  "entries":[{"threshold":7,"model":{"type":"minecraft:model",
                              "model":"p:item/huge_index"}}]}}
                """);
        WrapCatalogue ok = WrapCatalogue.build(fine, exists, "index-ok");
        check("an index a component could really carry is written",
                !ok.all().isEmpty()
                        && ok.all().get(0).entriesFor("minecraft:diamond_hoe").size() == 1
                        && ok.all().get(0).entriesFor("minecraft:diamond_hoe")
                                .get(0).index() == 3);
    }

    /**
     * One model, two items, two different numbers.
     *
     * <p>Which is not a curiosity: a pack numbers its models inside each item
     * definition, so the same appearance normally has a different number on
     * every item it is offered on. Measured over the packs this was built
     * against, 2,491 of the 4,579 appearances offered on more than one item are
     * like this. Writing one item's number onto another does not fail — it
     * summons whatever that pack put at that number on that item, which is how
     * applying a pickaxe skin drew a crown.</p>
     */
    private static void perItemValueTests(MemorySource source,
                                          java.util.function.Predicate<String> exists) {
        // The real shape, from the pack that showed the bug: the pickaxe model
        // is 473 on a netherite pickaxe, 596 on a diamond one — and 473 on the
        // diamond pickaxe is a different model entirely.
        source.model("p:item/poseidon_pickaxe",
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"p:t\"}}");
        source.model("p:item/poseidon_crown",
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"p:crown\"}}");
        source.solidTexture("p:crown", 16, 16, 0xFF3355FF);

        List<DiscoveredModel> discovered = new ArrayList<>();
        discovered.addAll(parseAs("minecraft:netherite_pickaxe", dispatch(
                "473", "p:item/poseidon_pickaxe")));
        discovered.addAll(parseAs("minecraft:diamond_pickaxe", dispatch(
                "473", "p:item/poseidon_crown", "596", "p:item/poseidon_pickaxe")));

        WrapCatalogue catalogue = WrapCatalogue.build(
                newBuilder(source, true).build(discovered), exists, "per-item");
        WrapTarget pickaxe = null;
        WrapTarget crown = null;
        for (WrapTarget wrap : catalogue.all()) {
            if (wrap.model().defaultState().modelId().endsWith("poseidon_pickaxe")) {
                pickaxe = wrap;
            } else if (wrap.model().defaultState().modelId().endsWith("poseidon_crown")) {
                crown = wrap;
            }
        }
        check("both models become wraps", pickaxe != null && crown != null);
        if (pickaxe == null || crown == null) {
            return;
        }

        check("one appearance on two items is one wrap",
                pickaxe.compatibleItems().size() == 2);
        check("the wrap writes the netherite pickaxe's own number",
                value(pickaxe, "minecraft:netherite_pickaxe") == 473.0F);
        check("the wrap writes the diamond pickaxe's own number",
                value(pickaxe, "minecraft:diamond_pickaxe") == 596.0F);
        check("the two are not the same number",
                value(pickaxe, "minecraft:netherite_pickaxe")
                        != value(pickaxe, "minecraft:diamond_pickaxe"));
        check("the number that draws the crown is never written for the pickaxe",
                value(pickaxe, "minecraft:diamond_pickaxe")
                        != value(crown, "minecraft:diamond_pickaxe"));
        check("the summary shows the value for the item being wrapped",
                pickaxe.componentSummary("minecraft:diamond_pickaxe").contains("596"));
        check("and not another item's value",
                !pickaxe.componentSummary("minecraft:diamond_pickaxe").contains("473"));
        check("an item the pack gives no value for is not compatible",
                !pickaxe.isCompatibleWith("minecraft:golden_pickaxe"));
        check("nothing is written for an item with no value of its own",
                pickaxe.entriesFor("minecraft:golden_pickaxe").isEmpty());
        check("the search text carries every item's value",
                pickaxe.searchBlob().contains("596"));
    }

    /**
     * Two models the pack gives the same number on the same item.
     *
     * <p>Only one can be drawn. The game sorts a range dispatch by threshold —
     * a stable sort — and then takes the last entry not above the value, so the
     * one written later in the file is the one that appears. Writing that value
     * for the other model applies cleanly and shows the winner instead.</p>
     */
    private static void contestedValueTests(MemorySource source,
                                            java.util.function.Predicate<String> exists) {
        source.model("p:item/helm",
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"p:helm\"}}");
        source.model("p:item/crown",
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"p:royal_crown\"}}");
        source.model("p:item/lost",
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"p:lost\"}}");
        source.solidTexture("p:helm", 16, 16, 0xFF884400);
        source.solidTexture("p:royal_crown", 16, 16, 0xFFFFCC00);
        source.solidTexture("p:lost", 16, 16, 0xFF008888);

        // 441 is claimed by the helmet and then by the crown, so the crown
        // wins it; the helmet is also at 443, which nothing contests. The
        // lost model shares 500 with the crown and has nowhere else to go.
        List<DiscoveredModel> discovered = new ArrayList<>(
                parseAs("minecraft:diamond_pickaxe", dispatch(
                        "441", "p:item/helm",
                        "441", "p:item/crown",
                        "443", "p:item/helm",
                        "500", "p:item/lost",
                        "500", "p:item/crown")));
        // The crown is on a helmet too, uncontested, which is what gives it a
        // wrap of its own rather than leaving it a state of the helmet's.
        discovered.addAll(parseAs("minecraft:diamond_helmet",
                dispatch("441", "p:item/crown")));

        WrapCatalogue catalogue = WrapCatalogue.build(
                newBuilder(source, true).build(discovered), exists, "contested");
        WrapTarget helm = null;
        WrapTarget crown = null;
        WrapTarget lost = null;
        for (WrapTarget wrap : catalogue.all()) {
            String id = wrap.model().defaultState().modelId();
            if (id.endsWith("/helm")) {
                helm = wrap;
            } else if (id.endsWith("/crown")) {
                crown = wrap;
            } else if (id.endsWith("/lost")) {
                lost = wrap;
            }
        }
        check("the contested models all become wraps",
                helm != null && crown != null && lost != null);
        if (helm == null || crown == null || lost == null) {
            return;
        }

        check("the model that would lose a shared value uses one of its own instead",
                value(helm, "minecraft:diamond_pickaxe") == 443.0F);
        check("the shared value is not written for the model that loses it",
                value(helm, "minecraft:diamond_pickaxe") != 441.0F);
        check("a model keeps a value nothing else claims",
                value(crown, "minecraft:diamond_helmet") == 441.0F);
        check("a model with no uncontested value is given none",
                lost.entriesFor("minecraft:diamond_pickaxe").isEmpty());
        check("and is not offered on that item",
                !lost.isCompatibleWith("minecraft:diamond_pickaxe"));
        check("but is still recognised as the mechanism it belongs to, not "
                        + "turned into an item_model wrap that would do nothing",
                lost.kind() == WrapTarget.Kind.CUSTOM_MODEL_DATA);
    }

    /** A range dispatch with one entry per threshold/model pair given. */
    private static String dispatch(String... thresholdsAndModels) {
        StringBuilder json = new StringBuilder(
                "{\"model\":{\"type\":\"minecraft:range_dispatch\","
                        + "\"property\":\"minecraft:custom_model_data\",\"entries\":[");
        for (int i = 0; i < thresholdsAndModels.length; i += 2) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"threshold\":").append(thresholdsAndModels[i])
                .append(",\"model\":{\"type\":\"minecraft:model\",\"model\":\"")
                .append(thresholdsAndModels[i + 1]).append("\"}}");
        }
        return json.append("]}}").toString();
    }

    /** Parses a definition filed under the item's own path, as a pack files it. */
    private static List<DiscoveredModel> parseAs(String item, String json) {
        String name = item.substring(item.indexOf(':') + 1);
        return ItemDefinitionParser.parse(item, "minecraft:items/" + name + ".json",
                "assets/minecraft/items/" + name + ".json", "file/Test.zip",
                new StringReader(json)).models();
    }

    /** The single numeric value a wrap writes on one item. */
    private static float value(WrapTarget target, String itemId) {
        List<WrapTarget.CustomModelDataEntry> entries = target.entriesFor(itemId);
        return entries.isEmpty() ? Float.NaN : entries.get(0).number();
    }

    // --------------------------------------------------------- classification

    /**
     * Packs that dispatch on the item's name rather than on custom model data.
     * The whole of "Blades Of Majestica" works this way: renaming a sword to
     * "Abominable Blade" is what summons the model.
     */
    private static void nameDispatchTests() {
        header("Name-dispatch packs");

        MemorySource source = new MemorySource();
        source.model("p:item/abominableblade",
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"p:t\"}}");
        source.model("p:item/aciddemon",
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"p:t2\"}}");
        // Distinct textures, or the duplicate remover folds them together and
        // rightly so: two identical pictures are one appearance.
        source.texture("p:t", 16, 16);
        source.solidTexture("p:t2", 16, 16, 0xFF00FF00);

        String definition = """
                {"model":{"type":"minecraft:select","property":"minecraft:component",
                  "component":"minecraft:custom_name","cases":[
                    {"when":"Abominable Blade",
                     "model":{"type":"minecraft:model","model":"p:item/abominableblade"}},
                    {"when":"Acidic Cleaver",
                     "model":{"type":"minecraft:model","model":"p:item/aciddemon"}}]}}
                """;

        List<LogicalModel> models = build(source, "minecraft:diamond_sword", definition);
        // Before this was understood, every case of such a select was treated as
        // a *state* of one model, so a hundred separate weapons collapsed into
        // a single entry.
        check("each name is its own model, not a state of one",
                models.size() == 2);

        WrapCatalogue catalogue = WrapCatalogue.build(models,
                id -> id.startsWith("minecraft:"), "a");
        check("both become wraps", catalogue.all().size() == 2);
        WrapTarget blade = catalogue.all().stream()
                .filter(t -> t.customName().equals("Abominable Blade"))
                .findFirst().orElse(null);
        check("a name-dispatched model is applied by name",
                blade != null && blade.kind() == WrapTarget.Kind.CUSTOM_NAME);
        check("it carries the exact name the pack switches on",
                blade != null && "Abominable Blade".equals(blade.customName()));
        check("it does not also try to write custom model data",
                blade != null && blade.customModelData().isEmpty());
        check("it knows the item it goes on",
                blade != null && blade.compatibleItems().contains("minecraft:diamond_sword"));
        check("the name becomes what the model is called",
                blade != null && blade.displayName().contains("Abominable"));

        // A select on something that really is a state must not be mistaken for
        // one of these.
        String stateful = """
                {"model":{"type":"minecraft:select","property":"minecraft:display_context",
                  "cases":[{"when":"gui",
                    "model":{"type":"minecraft:model","model":"p:item/aciddemon"}}]}}
                """;
        check("a display-context select is still a state, not an identity",
                build(source, "minecraft:diamond_sword", stateful).size() == 1);
    }

    /**
     * Input a pack controls, shaped to break something.
     *
     * <p>A resource pack is a zip a player downloaded from somewhere, and its
     * contents reach a parser, a resolver and — since the unified pack — a file
     * name. None of that should be able to do more than produce a warning.</p>
     */
    private static void hostileInputTests() {
        header("Hostile pack input");

        // Gson builds its tree by recursion, so nesting deep enough overruns the
        // stack before this parser's own depth limit is consulted. One bad file
        // must not end a scan.
        StringBuilder deep = new StringBuilder("{\"model\":");
        int levels = 20000;
        for (int i = 0; i < levels; i++) {
            deep.append("{\"type\":\"minecraft:condition\",\"property\":\"minecraft:using_item\","
                    + "\"on_false\":{\"type\":\"minecraft:model\",\"model\":\"p:x\"},"
                    + "\"on_true\":");
        }
        deep.append("{\"type\":\"minecraft:model\",\"model\":\"p:deep\"}");
        for (int i = 0; i < levels; i++) {
            deep.append('}');
        }
        deep.append('}');
        boolean survived;
        try {
            ItemDefinitionParser.parse("minecraft:diamond_sword", "p:items/x.json",
                    "assets/p/items/x.json", "file/Nasty.zip", new StringReader(deep.toString()));
            survived = true;
        } catch (Throwable t) {
            survived = false;
        }
        check("a definition nested twenty thousand deep is survivable", survived);

        // The ordinary depth limit still holds for trees that do parse.
        StringBuilder nested = new StringBuilder("{\"model\":");
        for (int i = 0; i < 50; i++) {
            nested.append("{\"type\":\"minecraft:condition\",\"property\":\"minecraft:using_item\","
                    + "\"on_false\":{\"type\":\"minecraft:model\",\"model\":\"p:shallow\"},"
                    + "\"on_true\":");
        }
        nested.append("{\"type\":\"minecraft:model\",\"model\":\"p:too_deep\"}");
        for (int i = 0; i < 50; i++) {
            nested.append('}');
        }
        nested.append('}');
        ItemDefinitionParser.Result limited = ItemDefinitionParser.parse(
                "minecraft:diamond_sword", "p:items/x.json", "assets/p/items/x.json",
                "file/Deep.zip", new StringReader(nested.toString()));
        check("a tree past the depth limit is cut off, with a warning",
                !limited.warnings().isEmpty()
                        && limited.models().stream().noneMatch(
                                m -> m.modelId().equals("p:too_deep")));
        check("and what was above the cut is still found",
                limited.models().stream().anyMatch(m -> m.modelId().equals("p:shallow")));

        // Truncated and nonsense files are warnings, not failures.
        for (String broken : new String[]{"", "   ", "null", "[]", "{\"model\":", "\u0000"}) {
            ItemDefinitionParser.Result r = ItemDefinitionParser.parse(
                    "minecraft:diamond_sword", "p:items/x.json", "assets/p/items/x.json",
                    "file/Broken.zip", new StringReader(broken));
            if (r == null) {
                check("a broken definition returns a result", false);
                return;
            }
        }
        check("broken definitions produce results rather than exceptions", true);
    }


    /**
     * Display transforms compare by their numbers.
     *
     * <p>{@code Transform} is a record of {@code float[]}, and a record of
     * arrays does not have the value semantics it appears to: the generated
     * {@code equals} compares the array objects, so two transforms holding the
     * same nine numbers come out unequal. That makes {@code ResolvedModel}
     * quietly wrong to compare, which is the sort of thing that is only noticed
     * once something depends on it.</p>
     */
    private static void transformTests() {
        header("Display transforms");

        ModelJson.Transform one = new ModelJson.Transform(
                new float[]{1, 2, 3}, new float[]{4, 5, 6}, new float[]{7, 8, 9});
        ModelJson.Transform same = new ModelJson.Transform(
                new float[]{1, 2, 3}, new float[]{4, 5, 6}, new float[]{7, 8, 9});
        ModelJson.Transform other = new ModelJson.Transform(
                new float[]{1, 2, 3}, new float[]{4, 5, 6}, new float[]{7, 8, 0});

        check("two transforms with the same numbers are equal", one.equals(same));
        check("and hash the same", one.hashCode() == same.hashCode());
        check("a different number makes a different transform", !one.equals(other));
        check("a transform prints its numbers, not its arrays",
                one.toString().contains("7.0") && !one.toString().contains("[F@"));

        // And therefore so do the models that hold them.
        MemorySource source = new MemorySource();
        source.model("p:item/posed", """
                {"parent":"minecraft:item/generated","textures":{"layer0":"p:item/tex"},
                 "display":{"gui":{"rotation":[30,45,0],"scale":[0.625,0.625,0.625]}}}""");
        source.texture("p:item/tex", 16, 16);
        check("two resolutions of one model are equal",
                new ModelResolver(source).resolve("p:item/posed")
                        .equals(new ModelResolver(source).resolve("p:item/posed")));
    }



    private static void classificationTests() {
        header("Item type classification");

        check("swords tag wins",
                ItemType.fromTags(java.util.Set.of("minecraft:swords")) == ItemType.SWORD);
        check("head armour tag maps to helmets",
                ItemType.fromTags(java.util.Set.of("minecraft:head_armor")) == ItemType.HELMET);
        // The tag is nested: "enchantable/bow". This check used to assert the
        // reversed spelling the code had, which matched nothing in the game.
        check("bow enchantable maps to bows",
                ItemType.fromTags(java.util.Set.of("minecraft:enchantable/bow")) == ItemType.BOW);
        check("unknown tags map to nothing",
                ItemType.fromTags(java.util.Set.of("minecraft:planks")) == null);
        check("bow is recognised by id", ItemType.fromItemId("minecraft:bow") == ItemType.BOW);
        check("shield is recognised by id",
                ItemType.fromItemId("minecraft:shield") == ItemType.SHIELD);
        check("paper is a generic carrier",
                ItemType.isGenericCarrier("minecraft:paper"));
        check("a diamond sword is not a generic carrier",
                !ItemType.isGenericCarrier("minecraft:diamond_sword"));

        check("model path finds a katana",
                GraphBuilder.fromModelPath("p:item/weapons/katana") == ItemType.SWORD);
        check("file name beats folder",
                GraphBuilder.fromModelPath("p:item/swords/ceremonial_axe") == ItemType.AXE);
        check("pickaxe is not an axe",
                GraphBuilder.fromModelPath("p:item/tools/pickaxe") == ItemType.PICKAXE);
        check("axolotl is not an axe",
                GraphBuilder.fromModelPath("p:item/mobs/axolotl") == null);
        check("shoes are not hoes",
                GraphBuilder.fromModelPath("p:item/wear/shoes") == null);

        // Headwear vocabulary. Packs name cosmetics, not equipment slots, so
        // the words that matter are the ones a person would use.
        check("a beanie is headwear",
                GraphBuilder.fromModelPath("p:item/vip_poor_man_beanie") == ItemType.HELMET);
        check("antlers are headwear",
                GraphBuilder.fromModelPath("p:item/dec_naughty_antlers") == ItemType.HELMET);
        check("glasses are headwear",
                GraphBuilder.fromModelPath("p:item/gamer_glasses") == ItemType.HELMET);
        check("a crown is headwear",
                GraphBuilder.fromModelPath("p:item/crown_of_undeath") == ItemType.HELMET);
        check("a helm is still headwear",
                GraphBuilder.fromModelPath("p:item/phoenix_rising_helm") == ItemType.HELMET);

        check("dye is its own category",
                ItemType.fromItemId("minecraft:brown_dye") == ItemType.DYE);
        check("every dye colour lands there",
                ItemType.fromItemId("minecraft:light_gray_dye") == ItemType.DYE);
        check("feather is its own category",
                ItemType.fromItemId("minecraft:feather") == ItemType.FEATHER);
        check("dye is no longer treated as a meaningless carrier",
                !ItemType.isGenericCarrier("minecraft:brown_dye"));

        classificationOrderTests();
        carrierAgreementTests();
    }

    /**
     * The rule that decides between the model's name and the item carrying it.
     *
     * <p>These pin down a real miscategorisation: a server pack hangs 761 models
     * off {@code netherite_helmet}, including 44 with "pickaxe" in the name, and
     * trusting the carrier's head-armour tag filed every one of them under
     * helmets.</p>
     */
    /**
     * A model offered on both a helmet and a pickaxe. The pack declares hats and
     * pickaxes identically — same four items, same shape of definition — so the
     * items alone cannot tell them apart and the name has to.
     */
    private static void heldVersusWornTests() {
        header("Held versus worn");

        MemorySource source = new MemorySource();
        for (String name : new String[]{"vault_breaker", "strawhat", "purgebear"}) {
            source.model("p:item/" + name,
                    "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"p:t\"}}");
        }
        source.texture("p:t", 16, 16);

        String definition = """
                {"model":{"type":"minecraft:range_dispatch",
                  "property":"minecraft:custom_model_data",
                  "entries":[{"threshold":%d,
                    "model":{"type":"minecraft:model","model":"p:item/%s"}}]}}
                """;

        for (String[] each : new String[][]{
                {"vault_breaker", "PICKAXE"},
                {"strawhat", "HELMET"},
                {"purgebear", "HELMET"}}) {
            List<DiscoveredModel> both = new ArrayList<>();
            both.addAll(parse("minecraft:netherite_helmet", definition.formatted(1, each[0])));
            both.addAll(parse("minecraft:diamond_pickaxe", definition.formatted(2, each[0])));
            List<LogicalModel> built = newBuilder(source, false).build(both);
            check(each[0] + " on a helmet and a pickaxe is filed as " + each[1],
                    built.get(0).type().name().equals(each[1]));
        }

        // The name says held; the items say which held thing. An axe carrier
        // must therefore produce an axe, not a pickaxe.
        List<DiscoveredModel> axed = new ArrayList<>();
        axed.addAll(parse("minecraft:netherite_helmet", definition.formatted(1, "vault_breaker")));
        axed.addAll(parse("minecraft:diamond_axe", definition.formatted(2, "vault_breaker")));
        check("the items decide which held thing it is",
                newBuilder(source, false).build(axed).get(0).type() == ItemType.AXE);
    }

    private static void classificationOrderTests() {
        MemorySource source = new MemorySource();
        source.texture("p:t", 16, 16);

        // A model offered on exactly one kind of item takes that kind, even
        // when its name suggests otherwise. If the only place a thing can go is
        // your head then it is headwear, whatever it is shaped like — and the
        // name only gets a say when the carriers cannot agree, which
        // carrierAgreementTests covers.
        check("a single carrier decides, over a conflicting name",
                classifyOne(source, "minecraft:netherite_helmet",
                        "p:item/fairyset/pickaxe") == ItemType.HELMET);
        check("a sword-named model on a helmet-only carrier is headwear",
                classifyOne(source, "minecraft:netherite_helmet",
                        "p:item/jancoll_space_sword") == ItemType.HELMET);
        check("a hat-named model on a pickaxe-only carrier is a pickaxe",
                classifyOne(source, "minecraft:diamond_pickaxe",
                        "p:item/party_hat") == ItemType.PICKAXE);

        // A carrier that says nothing leaves the name in charge.
        check("a mask model on a gold nugget is headwear",
                classifyOne(source, "minecraft:gold_nugget",
                        "p:item/tiki_mask") == ItemType.HELMET);

        // Where the name says nothing, the carrier is the answer.
        check("a nameless model takes its base item's category",
                classifyOne(source, "minecraft:netherite_helmet",
                        "p:item/vip_s7_aurora") == ItemType.HELMET);
        check("a nameless model on a dye is a dye",
                classifyOne(source, "minecraft:brown_dye",
                        "p:item/vip_s7_aurora") == ItemType.DYE);
        check("a nameless model on a feather is a feather",
                classifyOne(source, "minecraft:feather",
                        "p:item/vip_s7_aurora") == ItemType.FEATHER);
    }

    /**
     * The rule that weighs the carrying items against the model's name.
     *
     * <p>Pinned against real data: in the pack these were written for, 2,110
     * models are offered on more than one item and 758 of those disagree about
     * what kind of item it is.</p>
     */
    private static void carrierAgreementTests() {
        MemorySource source = new MemorySource();
        source.texture("p:t", 16, 16);
        source.model("p:item/fairyset/pickaxe",
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"p:t\"}}");
        source.model("p:item/llama",
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"p:t\"}}");
        source.model("p:item/plain_thing",
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"p:t\"}}");

        // Every carrier agrees: the item decides, whatever the name suggests.
        check("agreeing carriers decide the category",
                acrossItems(source, "p:item/fairyset/pickaxe",
                        List.of("minecraft:netherite_helmet", "minecraft:diamond_helmet"))
                        == ItemType.HELMET);

        // Carriers disagree: the name decides. This is the case the player
        // described — a model offered on both helmets and pickaxes.
        check("disagreeing carriers defer to the name",
                acrossItems(source, "p:item/fairyset/pickaxe",
                        List.of("minecraft:netherite_helmet", "minecraft:diamond_pickaxe"))
                        == ItemType.PICKAXE);

        // Carriers disagree and the name says nothing: the tiebreak favours the
        // wearable slot rather than whichever route happened to be read first.
        check("a silent name falls to the tiebreak, not to iteration order",
                acrossItems(source, "p:item/llama",
                        List.of("minecraft:diamond_pickaxe", "minecraft:netherite_helmet"))
                        == ItemType.HELMET);
        check("the tiebreak does not depend on carrier order",
                acrossItems(source, "p:item/llama",
                        List.of("minecraft:netherite_helmet", "minecraft:diamond_pickaxe"))
                        == ItemType.HELMET);

        // A clear majority wins before the tiebreak is reached.
        check("the best-supported category wins a majority",
                acrossItems(source, "p:item/llama",
                        List.of("minecraft:diamond_sword", "minecraft:iron_sword",
                                "minecraft:diamond_pickaxe")) == ItemType.SWORD);

        // No carrier says anything useful: the name is all there is.
        check("a meaningless carrier leaves it to the name",
                acrossItems(source, "p:item/fairyset/pickaxe",
                        List.of("minecraft:paper")) == ItemType.PICKAXE);
        check("no carrier and no name is Misc",
                acrossItems(source, "p:item/plain_thing",
                        List.of("minecraft:paper")) == ItemType.MISC);
    }

    /**
     * Builds the same model reached from several base items, as a pack that
     * offers one cosmetic on several carriers does, and reports its category.
     */
    private static ItemType acrossItems(MemorySource source, String modelId,
                                        List<String> items) {
        List<DiscoveredModel> discovered = new ArrayList<>();
        String json = """
                {"model":{"type":"minecraft:select","property":"minecraft:custom_model_data",
                  "cases":[{"when":"x","model":{"type":"minecraft:model","model":"MODEL"}}]}}
                """.replace("MODEL", modelId);
        for (String item : items) {
            discovered.addAll(ItemDefinitionParser.parse(item,
                    item.replace(":", ":items/") + ".json",
                    "assets/minecraft/items/x.json", "file/Test.zip",
                    new StringReader(json)).models());
        }
        GraphBuilder builder = new GraphBuilder(
                new ModelResolver(source),
                id -> {
                    ItemType byId = ItemType.fromItemId(id);
                    if (byId == null) {
                        byId = TEST_TAGS.get(id);
                    }
                    return byId == null
                            ? new ItemType.Classification(ItemType.MISC, ItemType.Source.NONE, id)
                            : new ItemType.Classification(byId, ItemType.Source.ITEM_TAG, id);
                },
                pack -> 1,
                pack -> pack.startsWith("file/"),
                id -> id.startsWith("minecraft:"),
                false);
        List<LogicalModel> models = builder.build(discovered);
        return models.isEmpty() ? null : models.get(0).type();
    }

    /** Builds one model on one base item and reports the category chosen. */
    private static ItemType classifyOne(MemorySource source, String item, String modelId) {
        source.model(modelId,
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"p:t\"}}");
        List<LogicalModel> models = build(source, item, """
                {"model":{"type":"minecraft:select","property":"minecraft:custom_model_data",
                  "cases":[{"when":"x","model":{"type":"minecraft:model","model":"MODEL"}}]}}
                """.replace("MODEL", modelId));
        return models.isEmpty() ? null : models.get(0).type();
    }

    // ---------------------------------------------------------------- search

    private static void searchTests() {
        header("Search and filters");

        MemorySource source = new MemorySource();
        source.model("example:item/weapons/katana",
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"example:item/weapons/katana\"}}");
        source.model("example:item/bows/dragon_bow",
                "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"example:item/bows/dragon_bow\"}}");
        source.texture("example:item/weapons/katana", 16, 16);
        source.texture("example:item/bows/dragon_bow", 16, 16);

        List<LogicalModel> models = new ArrayList<>();
        models.addAll(build(source, "minecraft:diamond_sword", """
                {"model":{"type":"minecraft:select","property":"minecraft:custom_model_data",
                  "cases":[{"when":"katana","model":{"type":"minecraft:model",
                            "model":"example:item/weapons/katana"}}]}}
                """));
        models.addAll(build(source, "minecraft:bow", """
                {"model":{"type":"minecraft:select","property":"minecraft:custom_model_data",
                  "cases":[{"when":"dragon_bow","model":{"type":"minecraft:model",
                            "model":"example:item/bows/dragon_bow"}}]}}
                """));

        check("plain word search", find(models, "katana") == 1);
        check("search by base item", find(models, "diamond_sword") == 1);
        check("search by full model id",
                find(models, "example:item/weapons/katana") == 1);
        check("search by texture path", find(models, "bows/dragon") == 1);
        check("search by namespace", find(models, "example") == 2);
        check("terms are combined with AND", find(models, "dragon katana") == 0);
        check("field prefix restricts", find(models, "item:bow") == 1);
        check("field prefix excludes non-matches", find(models, "item:katana") == 0);
        check("model field prefix", find(models, "model:weapons") == 1);
        check("texture field prefix", find(models, "texture:dragon") == 1);
        check("cmd field prefix", find(models, "cmd:dragon_bow") == 1);
        check("search is case insensitive", find(models, "KATANA") == 1);
        check("empty query matches everything", find(models, "") == 2);

        ExplorerQuery byNamespace = ExplorerQuery.EMPTY.withNamespace("example");
        check("namespace filter", byNamespace.apply(models).size() == 2);
        ExplorerQuery multi = ExplorerQuery.EMPTY
                .withStates(ExplorerQuery.StateFilter.MULTIPLE);
        check("multi-state filter excludes single-state models",
                multi.apply(models).isEmpty());
        ExplorerQuery combined = ExplorerQuery.EMPTY.withText("dragon")
                .withNamespace("example");
        check("search and filters combine", combined.apply(models).size() == 1);
        check("facets are discovered",
                ExplorerQuery.namespacesOf(models).equals(List.of("example")));

        // The pack filter is about packs the player installed. Every mod is a
        // resource pack to the game, and a modded profile has hundreds.
        check("a mod's own pack is not offered as a filter",
                !ExplorerQuery.packsOf(models).contains("sodium"));
        check("nor is vanilla",
                !ExplorerQuery.packsOf(models).contains("vanilla"));

        // Ignoring is per namespace as well as per pack, because one pack can
        // hold both the appearances wanted and a plugin's entity parts.
        dev.custommodels.cmv.core.CmvConfig config =
                new dev.custommodels.cmv.core.CmvConfig();
        check("nothing is ignored to begin with",
                !config.ignoresNamespace("modelengine"));
        config.ignoredNamespaces.add("modelengine");
        check("an ignored namespace is reported as such",
                config.ignoresNamespace("modelengine"));
        check("and its neighbours are not",
                !config.ignoresNamespace("modelfoundry"));
        config.ignoredNamespaces.remove("modelengine");
        check("removing it puts it back", !config.ignoresNamespace("modelengine"));
    }

    private static int find(List<LogicalModel> models, String text) {
        return ExplorerQuery.EMPTY.withText(text).apply(models).size();
    }

    // ---------------------------------------------------------------- render

    private static void renderTests() {
        header("Geometry and rasterizing");

        MemorySource source = new MemorySource();
        // A one-block cube with a solid red texture on every face.
        source.model("p:block/red", """
                {"textures":{"all":"p:red"},
                 "elements":[{"from":[0,0,0],"to":[16,16,16],"faces":{
                   "down":{"texture":"#all"},"up":{"texture":"#all"},
                   "north":{"texture":"#all"},"south":{"texture":"#all"},
                   "west":{"texture":"#all"},"east":{"texture":"#all"}}}]}""");
        source.solidTexture("p:red", 16, 16, 0xFFFF0000);

        ModelResolver resolver = new ModelResolver(source);
        ResolvedModel cube = resolver.resolve("p:block/red");
        Mesh mesh = new MeshBuilder(source, new HashMap<>(), resolver).build(cube);

        check("a cube builds six quads", mesh.quadCount() == 6);
        check("cube spans the full block",
                mesh.min()[0] == 0.0F && mesh.max()[0] == 16.0F);
        check("one texture is shared by all faces", mesh.textures().size() == 1);

        int[] pixels = Rasterizer.render(mesh, new Rasterizer.View(
                32, 32, 1, 0, 0, 1.0F, ModelJson.Transform.IDENTITY, false, 0));
        check("render produces the requested pixel count", pixels.length == 32 * 32);
        check("the centre of a cube is opaque", (pixels[32 * 16 + 16] >>> 24) == 255);
        check("the centre of a cube is red",
                ((pixels[32 * 16 + 16] >> 16) & 0xFF) > 100
                        && (pixels[32 * 16 + 16] & 0xFF) < 60);
        check("the corner outside the model stays transparent",
                (pixels[0] >>> 24) == 0);

        // A sprite item extrudes from its alpha silhouette.
        source.model("p:item/flat", """
                {"parent":"minecraft:item/generated","textures":{"layer0":"p:half"}}""");
        // Left half opaque, right half clear.
        source.halfTexture("p:half", 16, 16, 0xFF00FF00);
        Mesh sprite = new MeshBuilder(source, new HashMap<>(), resolver)
                .build(resolver.resolve("p:item/flat"));
        check("a sprite gets front and back faces plus edges",
                sprite.quadCount() > 2);
        check("a sprite is one unit thick",
                sprite.max()[2] - sprite.min()[2] == 1.0F);

        int[] flat = Rasterizer.render(sprite, new Rasterizer.View(
                32, 32, 1, 0, 0, 1.0F, ModelJson.Transform.IDENTITY, true, 0));
        // Rendered face on, the opaque half of the texture must be on the left.
        int left = flat[32 * 16 + 8];
        int right = flat[32 * 16 + 24];
        check("sprite is not mirrored left to right",
                (left >>> 24) == 255 && (right >>> 24) == 0);
        check("front-lit sprite keeps its texture colour",
                ((left >> 8) & 0xFF) > 240);

        // Rotating must not change how much of the frame is filled wildly,
        // which is the symptom of the fit being recomputed per angle.
        int filled0 = opaqueCount(Rasterizer.render(mesh, new Rasterizer.View(
                48, 48, 1, 0, 0, 1.0F, ModelJson.Transform.IDENTITY, false, 0)));
        int filled45 = opaqueCount(Rasterizer.render(mesh, new Rasterizer.View(
                48, 48, 1, 45, 0, 1.0F, ModelJson.Transform.IDENTITY, false, 0)));
        check("model does not resize as it spins",
                Math.abs(filled0 - filled45) < filled0);

        // An animated texture is a vertical strip of frames, and a face's UVs
        // are written against one frame. Mapping them down the whole file made
        // every face sample a sliver of the wrong rows: the pack this was found
        // on has a 64x1024 pickaxe texture and it drew as diagonal streaks.
        source.model("p:block/animated", """
                {"elements":[{"from":[0,0,0],"to":[16,16,16],"faces":{
                   "north":{"uv":[0,0,16,16],"texture":"#all"},
                   "south":{"uv":[0,0,16,16],"texture":"#all"},
                   "east":{"uv":[0,0,16,16],"texture":"#all"},
                   "west":{"uv":[0,0,16,16],"texture":"#all"},
                   "up":{"uv":[0,0,16,16],"texture":"#all"},
                   "down":{"uv":[0,0,16,16],"texture":"#all"}}}],
                 "textures":{"all":"p:strip"}}""");
        source.animatedTexture("p:strip", 16, 16, 0xFF00FF00, 0xFFFF0000);
        Mesh animated = new MeshBuilder(source, new HashMap<>(), resolver)
                .build(resolver.resolve("p:block/animated"));
        int[] strip = Rasterizer.render(animated, new Rasterizer.View(
                32, 32, 1, 0, 0, 1.0F, ModelJson.Transform.IDENTITY, true, 0));
        int centre = strip[16 * 32 + 16];
        check("a face on an animated texture samples the first frame",
                (centre & 0x00FF00) != 0 && (centre & 0xFF0000) == 0);

        // The detail view plays them by rendering the same mesh at a later
        // tick. Nothing about the mesh changes; the face reads a different row.
        int later = Rasterizer.render(animated, new Rasterizer.View(
                32, 32, 1, 0, 0, 1.0F, ModelJson.Transform.IDENTITY, true, 0, 1))
                [16 * 32 + 16];
        check("a later tick samples a later frame",
                (later & 0xFF0000) != 0 && (later & 0x00FF00) == 0);
        int wrapped = Rasterizer.render(animated, new Rasterizer.View(
                32, 32, 1, 0, 0, 1.0F, ModelJson.Transform.IDENTITY, true, 0, 16))
                [16 * 32 + 16];
        check("the animation loops back to the first frame",
                wrapped == centre);

        // And the whole chain the export actually walks: the ticks it decides
        // on, fed to the renderer, one frame of the file per tick. Both halves
        // were already right on their own - the renderer plays animations and
        // the detail view uses it - and the export still produced a file in
        // which nothing moved, because it asked for tick zero every time. So
        // what is asked here is what a person watching the file would see.
        int exportFrames = 72;
        int[] exportTicks = dev.custommodels.cmv.explorer.ui.DetailScreen
                .exportTicks(exportFrames, dev.custommodels.cmv.explorer.ui.DetailScreen
                        .spinTicks(8.0F, 16));
        java.util.Set<Integer> coloursDrawn = new java.util.LinkedHashSet<>();
        for (int i = 0; i < exportFrames; i++) {
            // Straight on, so the rotation cannot be what changes the pixel.
            coloursDrawn.add(Rasterizer.render(animated, new Rasterizer.View(
                    32, 32, 1, 0, 0, 1.0F, ModelJson.Transform.IDENTITY, true, 0,
                    exportTicks[i]))[16 * 32 + 16]);
        }
        check("an exported animation draws more than one texture frame",
                coloursDrawn.size() > 1);
        check("and draws both of the ones this strip has",
                coloursDrawn.size() == 2);

        // The same chain with the bug put back, so the check above is known to
        // be the thing that notices rather than something that would pass
        // either way.
        java.util.Set<Integer> atTickZero = new java.util.LinkedHashSet<>();
        for (int i = 0; i < exportFrames; i++) {
            atTickZero.add(Rasterizer.render(animated, new Rasterizer.View(
                    32, 32, 1, 0, 0, 1.0F, ModelJson.Transform.IDENTITY, true, 0, 0))
                    [16 * 32 + 16]);
        }
        check("where asking for tick zero every frame draws only one",
                atTickZero.size() == 1);

        // A frame time holds each frame for that many ticks.
        source.animatedTexture("p:slow", 16, 4, 0xFF00FF00, 0xFFFF0000, 4);
        source.model("p:block/slow", """
                {"elements":[{"from":[0,0,0],"to":[16,16,16],"faces":{
                   "north":{"uv":[0,0,16,16],"texture":"#all"},
                   "south":{"uv":[0,0,16,16],"texture":"#all"},
                   "east":{"uv":[0,0,16,16],"texture":"#all"},
                   "west":{"uv":[0,0,16,16],"texture":"#all"},
                   "up":{"uv":[0,0,16,16],"texture":"#all"},
                   "down":{"uv":[0,0,16,16],"texture":"#all"}}}],
                 "textures":{"all":"p:slow"}}""");
        Mesh slow = new MeshBuilder(source, new HashMap<>(), resolver)
                .build(resolver.resolve("p:block/slow"));
        int held = Rasterizer.render(slow, new Rasterizer.View(
                32, 32, 1, 0, 0, 1.0F, ModelJson.Transform.IDENTITY, true, 0, 3))
                [16 * 32 + 16];
        check("a frame is held for as long as its frame time says",
                (held & 0x00FF00) != 0 && (held & 0xFF0000) == 0);
        int moved = Rasterizer.render(slow, new Rasterizer.View(
                32, 32, 1, 0, 0, 1.0F, ModelJson.Transform.IDENTITY, true, 0, 4))
                [16 * 32 + 16];
        check("and moves on once it has been held that long",
                (moved & 0xFF0000) != 0 && (moved & 0x00FF00) == 0);

        // A still texture is a strip of one frame, so the clock cannot move it.
        source.solidTexture("p:still", 16, 16, 0xFF0000FF);
        source.model("p:block/still", """
                {"elements":[{"from":[0,0,0],"to":[16,16,16],"faces":{
                   "north":{"uv":[0,0,16,16],"texture":"#all"},
                   "south":{"uv":[0,0,16,16],"texture":"#all"},
                   "east":{"uv":[0,0,16,16],"texture":"#all"},
                   "west":{"uv":[0,0,16,16],"texture":"#all"},
                   "up":{"uv":[0,0,16,16],"texture":"#all"},
                   "down":{"uv":[0,0,16,16],"texture":"#all"}}}],
                 "textures":{"all":"p:still"}}""");
        Mesh stillMesh = new MeshBuilder(source, new HashMap<>(), resolver)
                .build(resolver.resolve("p:block/still"));
        check("a texture with one frame ignores the clock",
                Rasterizer.render(stillMesh, new Rasterizer.View(32, 32, 1, 0, 0, 1.0F,
                        ModelJson.Transform.IDENTITY, true, 0, 0))[16 * 32 + 16]
                        == Rasterizer.render(stillMesh, new Rasterizer.View(32, 32, 1, 0, 0,
                        1.0F, ModelJson.Transform.IDENTITY, true, 0, 7))[16 * 32 + 16]);

        Mesh nothing = new MeshBuilder(source, new HashMap<>(), resolver)
                .build(resolver.resolve("p:item/does_not_exist"));
        check("a missing model yields an empty mesh", nothing.isEmpty());
        int[] blank = Rasterizer.render(nothing, new Rasterizer.View(
                16, 16, 1, 0, 0, 1.0F, ModelJson.Transform.IDENTITY, true, 0));
        check("rendering an empty mesh is safe", blank.length == 256);
    }

    private static int opaqueCount(int[] pixels) {
        int count = 0;
        for (int pixel : pixels) {
            if ((pixel >>> 24) > 128) {
                count++;
            }
        }
        return count;
    }

    // --------------------------------------------------------------- harness


    /** Every model a definition can reach, by id. */
    private static Set<String> modelIds(String json) {
        Set<String> ids = new java.util.LinkedHashSet<>();
        for (DiscoveredModel model : parse("minecraft:diamond_sword", json)) {
            ids.add(model.modelId());
        }
        return ids;
    }

    private static List<DiscoveredModel> parse(String item, String json) {
        return ItemDefinitionParser.parse(item, "minecraft:items/x.json",
                "assets/minecraft/items/x.json", "file/Test.zip",
                new StringReader(json)).models();
    }

    /**
     * Builds a mesh from a source that refuses reads, standing in for a preview
     * worker. The mesh must come back untextured rather than reaching into the
     * pack, and the refusal must not be remembered as "this texture is missing".
     */
    private static boolean meshWithoutTextureAccess(MemorySource source, ModelResolver resolver) {
        source.model("p:item/tex_probe", """
                {"elements":[{"from":[0,0,0],"to":[16,16,16],
                  "faces":{"up":{"texture":"#all"}}}],"textures":{"all":"p:probe_tex"}}""");
        source.texture("p:probe_tex", 16, 16);
        ResolvedModel probe = resolver.resolve("p:item/tex_probe");
        Map<String, ResourceSource.Image> cache = new HashMap<>();

        source.denyLoads();
        try {
            new MeshBuilder(source, cache, resolver).build(probe);
        } finally {
            source.allowLoads();
        }
        if (!cache.isEmpty()) {
            return false;
        }
        // With reads allowed again the same builder must produce a real mesh,
        // proving the refusal was not cached as a permanent miss.
        return !new MeshBuilder(source, cache, resolver).build(probe).isEmpty();
    }

    /**
     * A face may name a texture directly rather than through a {@code #slot},
     * and those ids are not in {@link ResolvedModel#distinctTextures()} — so
     * whatever prefetches textures cannot know the full set in advance. The
     * build has to say what it could not get, or the caller caches a mesh with
     * missing faces and the model never draws again.
     */
    private static boolean reportsUnavailableTextures(MemorySource source, ModelResolver resolver) {
        source.model("p:item/direct_ref", """
                {"elements":[{"from":[0,0,0],"to":[16,16,16],
                  "faces":{"up":{"texture":"p:straight_at_it"}}}]}""");
        source.texture("p:straight_at_it", 16, 16);
        ResolvedModel direct = resolver.resolve("p:item/direct_ref");
        if (direct.distinctTextures().contains("p:straight_at_it")) {
            return false;   // if this ever changes, the premise is gone
        }

        source.denyLoads();
        MeshBuilder builder = new MeshBuilder(source, new HashMap<>(), resolver);
        Mesh incomplete = builder.build(direct);
        source.allowLoads();

        return incomplete.isEmpty()
                && builder.unavailableTextures().contains("p:straight_at_it")
                && !new MeshBuilder(source, new HashMap<>(), resolver).build(direct).isEmpty();
    }

    /**
     * Which models the card offers as looking like the open one.
     *
     * <p>Sharing either hash counts, and the two matches mean different
     * things: same geometry with another texture is a recolour of one thing,
     * same texture over other geometry is a family cut from one sheet. The
     * case that matters most is the one that would be silent — an empty hash
     * matching an empty hash would have every model with no geometry claim to
     * look like every other.</p>
     */
    /**
     * What the pack-health page is built on: the broken ones, always noted.
     *
     * <p>The display setting decides whether a broken state reaches the grid.
     * It must not decide whether the scan noticed, because the page that
     * reports on packs would then say different things depending on a
     * preference about cards — and would say nothing at all in the
     * configuration where somebody has turned the clutter off, which is
     * exactly when they want the report.</p>
     */
    private static void whatThePacksAreMissing() {
        header("Pack health");

        MemorySource source = new MemorySource();
        source.model("p:item/there", """
                {"elements":[{"from":[0,0,0],"to":[16,16,16],"faces":{
                   "north":{"uv":[0,0,16,16],"texture":"#all"}}}],
                 "textures":{"all":"p:t"}}""");
        source.texture("p:t", 16, 16);
        // p:item/gone is never defined, and p:item/hollow resolves to a file
        // with no geometry in it.
        source.model("p:item/hollow", "{}");

        String definition = """
                {"model":{"type":"minecraft:select","property":"minecraft:custom_model_data",
                  "cases":[
                    {"when":"a","model":{"type":"minecraft:model","model":"p:item/there"}},
                    {"when":"b","model":{"type":"minecraft:model","model":"p:item/gone"}},
                    {"when":"c","model":{"type":"minecraft:model","model":"p:item/hollow"}}]}}
                """;

        for (boolean listing : new boolean[]{true, false}) {
            GraphBuilder builder = newBuilder(source, true).includeBroken(listing);
            builder.build(parse("minecraft:diamond_sword", definition));
            String when = listing ? " while listing them" : " while leaving them out";
            check("a model no pack ships is noted" + when,
                    builder.missingModels().stream().anyMatch(id -> id.endsWith("gone")));
            check("a model that draws nothing is noted" + when,
                    builder.emptyModels().stream().anyMatch(id -> id.endsWith("hollow")));
            check("and the one that is fine is in neither list" + when,
                    builder.missingModels().stream().noneMatch(id -> id.endsWith("there"))
                            && builder.emptyModels().stream()
                                    .noneMatch(id -> id.endsWith("there")));
        }

        GraphBuilder dropping = newBuilder(source, true).includeBroken(false);
        dropping.build(parse("minecraft:diamond_sword", definition));
        check("only the run that drops them counts them as skipped",
                dropping.skippedBroken() == 2);
        GraphBuilder keeping = newBuilder(source, true).includeBroken(true);
        keeping.build(parse("minecraft:diamond_sword", definition));
        check("and the run that lists them skips nothing",
                keeping.skippedBroken() == 0);
    }

    private static void whatLooksLikeThis() {
        header("Look-alikes");

        MemorySource source = new MemorySource();
        // Two shapes and two textures, every combination of the two. The
        // geometry is written out rather than inherited from a vanilla parent,
        // so the hashes being compared are the ones this sets up.
        String cube = "[{\"from\":[0,0,0],\"to\":[16,16,16],\"faces\":{"
                + "\"north\":{\"uv\":[0,0,16,16],\"texture\":\"#all\"}}}]";
        String slab = "[{\"from\":[0,0,0],\"to\":[16,8,16],\"faces\":{"
                + "\"north\":{\"uv\":[0,0,16,16],\"texture\":\"#all\"}}}]";
        source.model("p:item/sword_blue",
                "{\"elements\":" + cube + ",\"textures\":{\"all\":\"p:blue\"}}");
        source.model("p:item/sword_red",
                "{\"elements\":" + cube + ",\"textures\":{\"all\":\"p:red\"}}");
        source.model("p:item/hat_blue",
                "{\"elements\":" + slab + ",\"textures\":{\"all\":\"p:blue\"}}");
        source.model("p:item/hat_red",
                "{\"elements\":" + slab + ",\"textures\":{\"all\":\"p:red\"}}");
        source.texture("p:blue", 16, 16);
        source.texture("p:red", 16, 16);

        List<LogicalModel> models = build(source, "minecraft:diamond_sword", """
                {"model":{"type":"minecraft:select","property":"minecraft:custom_model_data",
                  "cases":[
                    {"when":"sb","model":{"type":"minecraft:model","model":"p:item/sword_blue"}},
                    {"when":"sr","model":{"type":"minecraft:model","model":"p:item/sword_red"}},
                    {"when":"hb","model":{"type":"minecraft:model","model":"p:item/hat_blue"}},
                    {"when":"hr","model":{"type":"minecraft:model","model":"p:item/hat_red"}}]}}
                """);
        check("the four are distinct cards", models.size() == 4);

        LogicalModel swordBlue = models.stream()
                .filter(m -> m.defaultState().modelId().endsWith("sword_blue"))
                .findFirst().orElseThrow();
        java.util.Set<String> like = ExplorerQuery.alike(models, swordBlue);

        check("the model asked about leads its own list",
                like.iterator().next().equals(swordBlue.defaultState().modelId()));
        check("the same shape in another colour is a look-alike",
                like.stream().anyMatch(id -> id.endsWith("sword_red")));
        check("so is the same colour on another shape",
                like.stream().anyMatch(id -> id.endsWith("hat_blue")));
        check("but not the one sharing neither",
                like.stream().noneMatch(id -> id.endsWith("hat_red")));
        check("which is three of the four", like.size() == 3);

        // The failure that would be silent: nothing to compare is not a match.
        MemorySource bare = new MemorySource();
        bare.model("p:item/nothing_a", "{}");
        bare.model("p:item/nothing_b", "{}");
        List<LogicalModel> empties = build(bare, "minecraft:paper", """
                {"model":{"type":"minecraft:select","property":"minecraft:custom_model_data",
                  "cases":[
                    {"when":"a","model":{"type":"minecraft:model","model":"p:item/nothing_a"}},
                    {"when":"b","model":{"type":"minecraft:model","model":"p:item/nothing_b"}}]}}
                """);
        check("a model with nothing to compare matches only itself",
                empties.isEmpty()
                        || ExplorerQuery.alike(empties, empties.get(0)).size() == 1);

        check("nothing asked about is an empty answer",
                ExplorerQuery.alike(models, null).isEmpty());
        check("and no index to search leaves just the subject",
                ExplorerQuery.alike(null, swordBlue).size() == 1);
    }

    private static List<LogicalModel> build(MemorySource source, String item, String json) {
        return build(source, item, json, true);
    }

    private static List<LogicalModel> build(MemorySource source, String item, String json,
                                            boolean heuristic) {
        return newBuilder(source, heuristic).build(parse(item, json));
    }

    private static GraphBuilder newBuilder(MemorySource source, boolean heuristic) {
        GraphBuilder builder = new GraphBuilder(
                new ModelResolver(source),
                id -> {
                    ItemType byId = ItemType.fromItemId(id);
                    if (byId == null) {
                        byId = TEST_TAGS.get(id);
                    }
                    return byId == null
                            ? new ItemType.Classification(ItemType.MISC, ItemType.Source.NONE, id)
                            : new ItemType.Classification(byId, ItemType.Source.ITEM_TAG, id);
                },
                pack -> 1,
                pack -> pack.startsWith("file/"),
                // Only ids in the minecraft namespace stand for real items here;
                // a pack's own namespace holds item_model targets, not items.
                id -> id.startsWith("minecraft:"),
                heuristic);
        return builder;
    }

    /** A resource pack held entirely in memory. */
    private static final class MemorySource implements ResourceSource {

        private final Map<String, String> models = new LinkedHashMap<>();
        private final Map<String, Image> textures = new LinkedHashMap<>();
        private final java.util.Set<String> vanillaModels = new java.util.HashSet<>();
        private boolean sealed;
        private int opens;
        private boolean denyLoads;

        @Override
        public boolean canLoadHere() {
            return !denyLoads;
        }

        void denyLoads() {
            denyLoads = true;
        }

        void allowLoads() {
            denyLoads = false;
        }

        MemorySource() {
            // The vanilla models every item pack inherits from. Resolution
            // really does walk these, so leaving them out would test a chain
            // that does not exist in the game.
            models.put("minecraft:item/generated", "{\"parent\":\"builtin/generated\"}");
            models.put("minecraft:item/handheld", "{\"parent\":\"minecraft:item/generated\"}");
            models.put("minecraft:block/block", "{\"gui_light\":\"side\"}");
            vanillaModels.addAll(models.keySet());
        }

        void model(String id, String json) {
            models.put(id, json);
        }

        /** Marks a model as coming from the game rather than a pack. */
        void vanilla(String id) {
            vanillaModels.add(id);
        }

        void texture(String id, int width, int height) {
            solidTexture(id, width, height, 0xFFFFFFFF);
        }

        void solidTexture(String id, int width, int height, int argb) {
            int[] pixels = new int[width * height];
            java.util.Arrays.fill(pixels, argb);
            textures.put(id, new Image(width, height, pixels));
        }

        /**
         * A vertical animation strip: the first frame one colour, every frame
         * after it another. A model's UVs are written against one frame, so a
         * face covering the whole UV square must come out entirely the first
         * colour.
         */
        void animatedTexture(String id, int size, int frames, int first, int rest) {
            animatedTexture(id, size, frames, first, rest, 1);
        }

        void animatedTexture(String id, int size, int frames, int first, int rest,
                             int frameTime) {
            int height = size * frames;
            int[] pixels = new int[size * height];
            for (int y = 0; y < height; y++) {
                java.util.Arrays.fill(pixels, y * size, y * size + size,
                        y < size ? first : rest);
            }
            textures.put(id, new Image(size, height, pixels, 1, size, frameTime));
        }

        /** Opaque on the left half, transparent on the right. */
        void halfTexture(String id, int width, int height, int argb) {
            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    pixels[y * width + x] = x < width / 2 ? argb : 0;
                }
            }
            textures.put(id, new Image(width, height, pixels));
        }

        @Override
        public Reader openModel(String modelId) {
            if (sealed) {
                throw new IllegalStateException(
                        "the pack was read after the scan: " + modelId);
            }
            opens++;
            String json = models.get(modelId);
            return json == null ? null : new StringReader(json);
        }

        /**
         * Refuses further reads, standing in for a pack whose file handle the
         * game has closed. Anything that reads here would be reaching into a
         * closed zip in the real game.
         */
        void seal() {
            sealed = true;
            opens = 0;
        }

        void unseal() {
            sealed = false;
        }

        int opens() {
            return opens;
        }

        @Override
        public String modelSourcePack(String modelId) {
            if (!models.containsKey(modelId)) {
                return "";
            }
            return vanillaModels.contains(modelId) ? "vanilla" : "file/Test.zip";
        }

        @Override
        public Image loadTexture(String textureId) {
            return textures.get(textureId);
        }

        @Override
        public String textureSourcePack(String textureId) {
            return textures.containsKey(textureId) ? "file/Test.zip" : "";
        }

        @Override
        public Reader openTextureMeta(String textureId) throws IOException {
            return null;
        }
    }


    /**
     * The prefixed-namespace fallback, and where it must not fire.
     *
     * <p>Measured against the pack it exists for: 9,414 texture references
     * resolved to nothing, 9,331 of them present under the same name with the
     * prefix removed.</p>
     */
    private static void textureNamespaceTests() {
        header("Texture namespace fallback");

        check("an ordinary id has no alternative",
                ResourceSource.alternativeTextureIds("pack:item/sword").isEmpty());
        check("a bare id has no alternative",
                ResourceSource.alternativeTextureIds("item/sword").isEmpty());
        check("minecraft is left alone",
                ResourceSource.alternativeTextureIds("minecraft:item/bow").isEmpty());

        check("one underscore is taken off",
                ResourceSource.alternativeTextureIds("_monarch:custom/armor/helmet")
                        .equals(List.of("monarch:custom/armor/helmet")));

        // "_b_x" is ambiguous, so both readings are offered, shortest first.
        check("_b_ offers both readings",
                ResourceSource.alternativeTextureIds("_b_elitecreatures:roman/gladius")
                        .equals(List.of("elitecreatures:roman/gladius",
                                "b_elitecreatures:roman/gladius")));

        // A namespace that is nothing but the prefix would strip to an empty
        // namespace, which is not an id at all.
        check("a bare underscore is not stripped to nothing",
                ResourceSource.alternativeTextureIds("_:thing").isEmpty());
        check("a bare _b_ is not stripped to nothing",
                ResourceSource.alternativeTextureIds("_b_:thing")
                        .equals(List.of("b_:thing")));

        // The path must survive untouched — only the namespace is rewritten.
        check("the path is left exactly as it was",
                ResourceSource.alternativeTextureIds("_a:deep/path/with_underscores")
                        .equals(List.of("a:deep/path/with_underscores")));
    }


    private static void header(String name) {
        System.out.println();
        System.out.println("== " + name);
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  ok   " + name);
        } else {
            failed++;
            System.out.println("  FAIL " + name);
        }
    }
}
