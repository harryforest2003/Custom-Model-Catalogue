package dev.custommodels.cmv.collection;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.custommodels.cmv.compat.McCompat;
import dev.custommodels.cmv.core.CmvMod;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The player's own arrangement of the pack: favourites, sets, and the headings
 * sets are filed under.
 *
 * <h2>Why this is the player's and not the pack's</h2>
 *
 * <p>The mod once tried to work out sets from the pack itself and it was wrong
 * nearly every time, because these packs are filed by event and contributor
 * rather than by collection: a "set" in the player's head is the pieces they
 * wear together, which no numbering in the files agrees with. So nothing here
 * is inferred. A set is a list the player wrote, in the order they wrote it,
 * under a heading they named.</p>
 *
 * <h2>What a member is</h2>
 *
 * <p>The model's id — {@code blockbench/2024surv5_poseidon_crown} — rather than
 * the internal identity of a scan. Those identities are built from a hash of
 * the route a model was found by, so they change when a pack changes and a
 * saved list would quietly empty itself after an update. A model id is what the
 * pack itself calls the file, is stable while the file exists, and is legible
 * in the saved JSON, which matters for something a player may want to edit by
 * hand.</p>
 *
 * <p>A member that no longer resolves is kept rather than dropped. A pack being
 * disabled for an evening is not a reason to destroy a list that took a while
 * to build; it shows as missing until the pack comes back.</p>
 */
public final class SkinSets {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int CURRENT_VERSION = 1;

    /** Bounds on what a player can type or accumulate, so a file stays sane. */
    public static final int MAX_NAME_LENGTH = 48;
    private static final int MAX_SETS = 500;
    private static final int MAX_MEMBERS = 2000;

    private static SkinSets instance;

    /** One named list of models. */
    public static final class Set {
        public String name = "";
        public List<String> members = new ArrayList<>();

        public int size() {
            return members == null ? 0 : members.size();
        }
    }

    /**
     * A heading with the sets filed under it.
     *
     * <p>The unnamed group is where a set goes when the player has not filed it
     * anywhere. It is drawn without a heading rather than under one called
     * "Ungrouped", which would be a heading the player did not write.</p>
     */
    public static final class Group {
        public String name = "";
        /**
         * Whether the sets under this heading are hidden.
         *
         * <p>Kept in the file rather than for the session, because a heading
         * folded away is a filing decision like any other: someone who put
         * three seasons of sets under three headings did it so that two of them
         * would be out of the way, and having them all spring open again next
         * time undoes exactly what they arranged.</p>
         */
        public boolean collapsed;
        public List<Set> sets = new ArrayList<>();
    }

    public int version = CURRENT_VERSION;
    /** Model ids, newest first, so the list reads as "what I have been using". */
    public List<String> favourites = new ArrayList<>();
    public List<Group> groups = new ArrayList<>();

    private transient boolean persistent = true;
    /**
     * The favourites again, for asking about one of them.
     *
     * <p>The list is the file's order and the display's; this is for the
     * question the card grid asks, which is whether this one model is in it.
     * That is asked for every card on screen, every frame, and down a list it
     * is a scan of the whole thing — a hundred favourites and forty cards is
     * four thousand comparisons a frame for forty yes-or-no answers. Dropped
     * whenever the list changes, which is when someone clicks a star.</p>
     */
    private transient java.util.Set<String> favouriteIndex;

    public static SkinSets get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    /** An empty store that never writes a file, for tests. */
    public static SkinSets inMemory() {
        SkinSets sets = new SkinSets();
        sets.persistent = false;
        return sets;
    }

    // ----------------------------------------------------------- favourites

    public boolean isFavourite(String modelId) {
        if (modelId == null || modelId.isEmpty()) {
            return false;
        }
        if (favouriteIndex == null) {
            favouriteIndex = new java.util.HashSet<>(favourites);
        }
        return favouriteIndex.contains(modelId);
    }

    /** @return true if it is a favourite afterwards */
    public boolean toggleFavourite(String modelId) {
        if (modelId == null || modelId.isEmpty()) {
            return false;
        }
        boolean added;
        if (favourites.remove(modelId)) {
            added = false;
        } else {
            if (favourites.size() >= MAX_MEMBERS) {
                return false;
            }
            favourites.add(0, modelId);
            added = true;
        }
        favouriteIndex = null;
        save();
        return added;
    }

    public List<String> favourites() {
        return List.copyOf(favourites);
    }

    // ----------------------------------------------------------------- sets

    public List<Group> groups() {
        return groups;
    }

    /** Every set, in the order shown, ignoring which group it is in. */
    public List<Set> allSets() {
        List<Set> out = new ArrayList<>();
        for (Group group : groups) {
            out.addAll(group.sets);
        }
        return out;
    }

    public int setCount() {
        int total = 0;
        for (Group group : groups) {
            total += group.sets.size();
        }
        return total;
    }

    /**
     * Adds a set under a heading, creating the heading if it is new.
     *
     * @return the set, or null when there are already too many
     */
    public Set createSet(String name, String groupName) {
        if (setCount() >= MAX_SETS) {
            return null;
        }
        Set set = new Set();
        set.name = trim(name == null || name.isBlank() ? "New set" : name);
        group(groupName).sets.add(set);
        save();
        return set;
    }

    public Group createGroup(String name) {
        Group group = group(name);
        save();
        return group;
    }

    /** The group with this name, made if it does not exist yet. */
    public Group group(String name) {
        String wanted = trim(name);
        for (Group group : groups) {
            if (group.name.equals(wanted)) {
                return group;
            }
        }
        Group group = new Group();
        group.name = wanted;
        groups.add(group);
        return group;
    }

    public Group groupOf(Set set) {
        for (Group group : groups) {
            if (group.sets.contains(set)) {
                return group;
            }
        }
        return null;
    }

    public void renameSet(Set set, String name) {
        set.name = trim(name);
        save();
    }

    /** Folds a heading's sets away, or brings them back. */
    public void toggleCollapsed(Group group) {
        group.collapsed = !group.collapsed;
        save();
    }

    public void renameGroup(Group group, String name) {
        group.name = trim(name);
        save();
    }

    public void deleteSet(Set set) {
        for (Group group : groups) {
            if (group.sets.remove(set)) {
                break;
            }
        }
        save();
    }

    /**
     * Removes a heading, keeping the sets under it.
     *
     * <p>They move to the unnamed group rather than going with it. Deleting a
     * heading is a filing decision; losing the sets underneath would be a
     * different and much worse one to make on the player's behalf.</p>
     */
    public void deleteGroup(Group group) {
        if (group.name.isEmpty()) {
            return;
        }
        groups.remove(group);
        if (!group.sets.isEmpty()) {
            group("").sets.addAll(group.sets);
        }
        save();
    }

    /** Moves a set one place up or down within its own heading. */
    public boolean moveSet(Set set, int by) {
        Group group = groupOf(set);
        if (group == null) {
            return false;
        }
        boolean moved = move(group.sets, set, by);
        if (moved) {
            save();
        }
        return moved;
    }

    public boolean moveGroup(Group group, int by) {
        boolean moved = move(groups, group, by);
        if (moved) {
            save();
        }
        return moved;
    }

    /** Files a set under another heading, keeping its contents and order. */
    public void moveSetToGroup(Set set, String groupName) {
        Group from = groupOf(set);
        if (from != null) {
            from.sets.remove(set);
        }
        group(groupName).sets.add(set);
        save();
    }

    private static <T> boolean move(List<T> list, T item, int by) {
        int at = list.indexOf(item);
        int to = at + by;
        if (at < 0 || to < 0 || to >= list.size()) {
            return false;
        }
        list.remove(at);
        list.add(to, item);
        return true;
    }

    // ------------------------------------------------------------ membership

    public boolean contains(Set set, String modelId) {
        return set != null && modelId != null && set.members.contains(modelId);
    }

    /** @return true if the model is in the set afterwards */
    public boolean toggleMember(Set set, String modelId) {
        if (set == null || modelId == null || modelId.isEmpty()) {
            return false;
        }
        boolean added;
        if (set.members.remove(modelId)) {
            added = false;
        } else if (set.members.size() >= MAX_MEMBERS) {
            return true;
        } else {
            set.members.add(modelId);
            added = true;
        }
        save();
        return added;
    }

    public boolean moveMember(Set set, String modelId, int by) {
        boolean moved = set != null && move(set.members, modelId, by);
        if (moved) {
            save();
        }
        return moved;
    }

    /** Which sets a model is in, for the card's own menu. */
    public List<Set> setsContaining(String modelId) {
        List<Set> out = new ArrayList<>();
        for (Group group : groups) {
            for (Set set : group.sets) {
                if (set.members.contains(modelId)) {
                    out.add(set);
                }
            }
        }
        return out;
    }

    private static String trim(String name) {
        String value = name == null ? "" : name.strip();
        return value.length() > MAX_NAME_LENGTH ? value.substring(0, MAX_NAME_LENGTH) : value;
    }

    // ----------------------------------------------------------- persistence

    private static Path file() {
        return McCompat.configDir().resolve("sets.json");
    }

    private static SkinSets load() {
        Path path;
        try {
            path = file();
        } catch (RuntimeException e) {
            return new SkinSets();
        }
        if (!Files.exists(path)) {
            return new SkinSets();
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            SkinSets loaded = GSON.fromJson(reader, SkinSets.class);
            if (loaded == null) {
                return new SkinSets();
            }
            loaded.repair();
            return loaded;
        } catch (IOException | RuntimeException e) {
            // A hand-edited file that will not parse must not stop the game.
            CmvMod.LOGGER.warn("Could not read sets.json, starting empty", e);
            return new SkinSets();
        }
    }

    /**
     * Fills in anything an older or hand-written file is missing, and holds
     * what it contains to the same limits the interface does.
     *
     * <p>This file is meant to be edited by hand, so it will be — and a name a
     * megabyte long or a list of a million ids is not an attack so much as a
     * slip with a text editor or a broken script. Either would be measured on
     * every frame the grid draws, so the limits are applied on the way in as
     * well as on the way out, where until now they were only applied on the
     * way out.</p>
     */
    private void repair() {
        if (favourites == null) {
            favourites = new ArrayList<>();
        }
        favourites.removeIf(id -> id == null || id.isEmpty());
        favourites = new ArrayList<>(new LinkedHashSet<>(favourites));
        truncate(favourites, MAX_MEMBERS);
        favouriteIndex = null;
        if (groups == null) {
            groups = new ArrayList<>();
        }
        groups.removeIf(java.util.Objects::isNull);
        truncate(groups, MAX_SETS);
        int total = 0;
        for (Group group : groups) {
            group.name = trim(group.name);
            if (group.sets == null) {
                group.sets = new ArrayList<>();
            }
            group.sets.removeIf(java.util.Objects::isNull);
            // Across every heading, not within each, so a file cannot carry
            // more sets than the interface would ever have made.
            truncate(group.sets, Math.max(0, MAX_SETS - total));
            total += group.sets.size();
            for (Set set : group.sets) {
                set.name = trim(set.name);
                if (set.members == null) {
                    set.members = new ArrayList<>();
                }
                set.members.removeIf(id -> id == null || id.isEmpty());
                truncate(set.members, MAX_MEMBERS);
            }
        }
    }

    private static void truncate(List<?> list, int most) {
        while (list.size() > most) {
            list.remove(list.size() - 1);
        }
    }

    public void save() {
        if (!persistent) {
            return;
        }
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException | RuntimeException e) {
            CmvMod.LOGGER.error("Could not write sets.json", e);
        }
    }
}
