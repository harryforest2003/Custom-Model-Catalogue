package dev.custommodels.cmv.collection;

import dev.custommodels.cmv.core.ItemFacts;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Sets built from what a server said its sets are, and only then.
 *
 * <h2>Only from the document, and only when it is complete</h2>
 *
 * <p>Nothing here guesses. A set is made when the fetched document describes
 * one and every piece of it can be found, and in no other case. A catalogue
 * that publishes no sets produces no sets - there is nothing to infer them
 * from and inferring them from names or folders would be making things up
 * about somebody's server.</p>
 *
 * <p>A set is made from the pieces that are actually here, and it says how
 * many that is out of how many the catalogue named. It was stricter once -
 * every piece present or nothing built - on the reasoning that half a set is a
 * different claim from the set. In practice that meant almost nothing was ever
 * built, because the armour these sets are mostly made of is drawn from
 * equipment assets this mod does not index, so three-quarters of a set being
 * right and present counted for nothing. A set of the pieces you have, which
 * says it is a set of the pieces you have, is the more useful of the two and
 * not less honest.</p>
 *
 * <p>What has not changed is where the pieces come from. Each is a model found
 * by the key the document gave, so a set never contains an entry pointing at
 * nothing, and a set with fewer than two of them is not a set and is not
 * made.</p>
 *
 * <p>Pure, so this can be checked without a server, a catalogue or a game: it
 * is handed the document and a way of turning a key into a model, and returns
 * what it would do. Doing it is a separate step.</p>
 */
public final class StatedSets {

    /** A set fewer than this many pieces is not a set worth a heading. */
    private static final int LEAST = 2;

    private StatedSets() {
    }

    /**
     * What would be made for one of the document's sets.
     *
     * @param name       the server's own name for it
     * @param models     the models it would hold, in catalogue order
     * @param expected   how many pieces the catalogue said it has
     * @param found      how many of them are present here
     * @param definitive whether it would actually be made - two pieces or more
     * @param why        why not, when it would not
     */
    public record Plan(String name, List<String> models, int expected, int found,
                       boolean definitive, String why) {
    }

    /**
     * Works out what could be made, without making anything.
     *
     * @param sets     the document's set section; empty means no sets are built
     * @param modelFor turns an item-and-model-data key into a model id present
     *                 here, or null when nothing here matches
     */
    public static List<Plan> plan(Map<String, ItemFacts.SetFacts> sets,
                                  Function<String, String> modelFor) {
        List<Plan> plans = new ArrayList<>();
        if (sets == null || sets.isEmpty()) {
            return plans;
        }
        sets.forEach((name, facts) -> {
            if (name == null || name.isBlank() || facts == null) {
                return;
            }
            // Order kept and repeats dropped: one model can be reached by more
            // than one value, and a set holding it twice is a worse set.
            LinkedHashSet<String> models = new LinkedHashSet<>();
            int missing = 0;
            for (String key : facts.keys() == null ? List.<String>of() : facts.keys()) {
                String model = modelFor.apply(key);
                if (model == null || model.isEmpty()) {
                    missing++;
                } else {
                    models.add(model);
                }
            }
            List<String> found = List.copyOf(models);
            // Enough to be a set, and that is the whole test now. What is
            // missing is said rather than used to refuse.
            String why = found.size() < LEAST
                    ? "only " + found.size() + " of its "
                            + facts.expected() + " pieces are in the packs here"
                    : "";
            plans.add(new Plan(name, found, facts.expected(), found.size(),
                    why.isEmpty(), why));
        });
        return plans;
    }

    /**
     * Makes the ones that are definitive, and says how many.
     *
     * <p>A set of the same name under the same heading is left alone rather
     * than made twice or emptied and refilled, so running this again after a
     * fetch adds what is new and does not disturb what somebody has since
     * rearranged.</p>
     */
    public static int apply(SkinSets into, List<Plan> plans, String groupName) {
        int made = 0;
        for (Plan plan : plans) {
            if (!plan.definitive()) {
                continue;
            }
            if (existing(into, plan.name(), groupName) != null) {
                continue;
            }
            SkinSets.Set set = into.createSet(plan.name(), groupName);
            if (set == null) {
                continue;
            }
            for (String model : plan.models()) {
                into.toggleMember(set, model);
            }
            made++;
        }
        return made;
    }

    /** A set of this name already under this heading, or null. */
    private static SkinSets.Set existing(SkinSets sets, String name, String groupName) {
        for (SkinSets.Set set : sets.allSets()) {
            SkinSets.Group group = sets.groupOf(set);
            if (set.name != null && set.name.equals(name)
                    && group != null && groupName.equals(group.name)) {
                return set;
            }
        }
        return null;
    }
}
