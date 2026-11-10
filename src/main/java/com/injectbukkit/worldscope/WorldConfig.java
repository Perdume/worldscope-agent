package com.injectbukkit.worldscope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Immutable, parsed form of {@code worldscope.properties}: which worlds a command is restricted
 * by (decided from the world the command <i>originates</i> in) and which extra worlds a
 * restricted command may still reach.
 *
 * <p>This class knows nothing about Minecraft: it only compares sets of already-lowercased world
 * identifiers, so it has no reflection and nothing to go wrong on an unfamiliar server version.
 * Turning a live {@code ServerLevel} into those identifiers is {@link WorldPolicy}'s job.
 *
 * <p>Like {@link Log}, {@link OriginContext} and {@link EntityWorldFilter} this class is reachable
 * from advice inlined into {@code net.minecraft.*}, so it is part of the bootstrap bridge jar
 * (see {@link WorldScopeAgent}). That is why it avoids nested classes and enums - every extra
 * {@code .class} file would also have to be listed there.
 *
 * <p>File format: {@code key = value} lines, split on the <i>first</i> {@code =} (so keys may
 * contain {@code :}, as dimension keys like {@code minecraft:the_nether} do). Lines starting with
 * {@code #} or {@code ;} are comments. See {@link #defaultFileContents()} for the keys.
 */
public final class WorldConfig {

    /** Every world is restricted. Also what you get with no config file at all. */
    public static final int MODE_ALL = 0;
    /** Only commands originating in the listed worlds are restricted. */
    public static final int MODE_INCLUDE = 1;
    /** Every world is restricted except the listed ones. */
    public static final int MODE_EXCLUDE = 2;

    /** In {@code reach.*}: matches any origin world. As a reach target: matches every world. */
    public static final String WILDCARD = "*";

    private static final String REACH_PREFIX = "reach.";

    /** Behaves exactly like the agent did before this config existed. */
    public static final WorldConfig DEFAULT =
            new WorldConfig(MODE_ALL, Collections.<String>emptySet(), Collections.<String, Set<String>>emptyMap());

    private final int mode;
    private final Set<String> worlds;
    private final Map<String, Set<String>> reach;

    private WorldConfig(int mode, Set<String> worlds, Map<String, Set<String>> reach) {
        this.mode = mode;
        this.worlds = worlds;
        this.reach = reach;
    }

    public int mode() {
        return mode;
    }

    public boolean hasReachRules() {
        return !reach.isEmpty();
    }

    /**
     * @param originIds every lowercased identifier (Bukkit name, dimension key) of the world a
     *                  command originates in
     * @return whether commands from that world are restricted at all
     */
    public boolean isScoped(Set<String> originIds) {
        switch (mode) {
            case MODE_INCLUDE:
                return intersects(worlds, originIds);
            case MODE_EXCLUDE:
                return !intersects(worlds, originIds);
            default:
                return true;
        }
    }

    /**
     * @return whether a restricted command originating in {@code originIds}' world may also
     *         reach the world described by {@code targetIds}
     */
    public boolean mayReach(Set<String> originIds, Set<String> targetIds) {
        for (Map.Entry<String, Set<String>> rule : reach.entrySet()) {
            if (!rule.getKey().equals(WILDCARD) && !originIds.contains(rule.getKey())) {
                continue;
            }
            Set<String> targets = rule.getValue();
            if (targets.contains(WILDCARD) || intersects(targets, targetIds)) {
                return true;
            }
        }
        return false;
    }

    /** One-line summary for the startup log. */
    public String describe() {
        String modeName = mode == MODE_INCLUDE ? "include" : mode == MODE_EXCLUDE ? "exclude" : "all";
        StringBuilder sb = new StringBuilder("mode=").append(modeName);
        if (mode != MODE_ALL) {
            sb.append(", worlds=").append(worlds);
        }
        sb.append(", reach rules=").append(reach.size());
        return sb.toString();
    }

    /**
     * Parses config lines. Never throws: a bad line is skipped and described in {@code problems},
     * and an unknown mode falls back to {@link #MODE_ALL}, so a <i>syntax</i> mistake can only make
     * the agent stricter than intended. A misspelled world <i>name</i> is not a syntax error and is
     * not detected here: in an {@code include} list it leaves that world unrestricted.
     */
    public static WorldConfig parse(List<String> lines, List<String> problems) {
        int mode = MODE_ALL;
        Set<String> worlds = new LinkedHashSet<>();
        Map<String, Set<String>> reach = new LinkedHashMap<>();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                continue;
            }
            String where = "line " + (i + 1) + ": ";
            int eq = line.indexOf('=');
            if (eq < 0) {
                problems.add(where + "expected 'key = value', ignored: " + line);
                continue;
            }
            String key = line.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(eq + 1).trim();

            if (key.equals("mode")) {
                String m = value.toLowerCase(Locale.ROOT);
                if (m.equals("all")) {
                    mode = MODE_ALL;
                } else if (m.equals("include")) {
                    mode = MODE_INCLUDE;
                } else if (m.equals("exclude")) {
                    mode = MODE_EXCLUDE;
                } else {
                    problems.add(where + "mode must be all, include or exclude (got '" + value
                            + "'), keeping the previous value");
                }
            } else if (key.equals("worlds")) {
                worlds.addAll(splitList(value));
            } else if (key.startsWith(REACH_PREFIX)) {
                String origin = key.substring(REACH_PREFIX.length()).trim();
                if (origin.isEmpty()) {
                    problems.add(where + "'reach.' needs an origin world, e.g. reach.lobby = spawn");
                } else {
                    Set<String> targets = reach.get(origin);
                    if (targets == null) {
                        targets = new LinkedHashSet<>();
                        reach.put(origin, targets);
                    }
                    targets.addAll(splitList(value));
                }
            } else {
                problems.add(where + "unknown key '" + key + "', ignored");
            }
        }

        // "reach.lobby =" with nothing after it is a rule that grants nothing; don't count it.
        for (Iterator<Map.Entry<String, Set<String>>> it = reach.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue().isEmpty()) {
                it.remove();
            }
        }
        if (mode == MODE_INCLUDE && worlds.isEmpty()) {
            problems.add("mode = include but no worlds are listed: no world is restricted, so the agent does nothing");
        }

        Map<String, Set<String>> frozenReach = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> rule : reach.entrySet()) {
            frozenReach.put(rule.getKey(), Collections.unmodifiableSet(rule.getValue()));
        }
        return new WorldConfig(mode, Collections.unmodifiableSet(worlds), Collections.unmodifiableMap(frozenReach));
    }

    /** What gets written when no config file exists. Every value in it equals the built-in default. */
    public static String defaultFileContents() {
        return String.join("\n",
                "# WorldScope configuration.",
                "# Read at startup and again on every server reload (/reload confirm, /minecraft:reload).",
                "# Lines starting with # are comments. Without this file the agent restricts every world.",
                "#",
                "# World names are matched case-insensitively against EITHER the Bukkit world name",
                "# (world, world_nether, world_the_end, or a custom name) OR the dimension key",
                "# (minecraft:overworld, minecraft:the_nether, minecraft:the_end, ...).",
                "#",
                "# --- Which worlds are restricted ---------------------------------------------------",
                "# The world a command ORIGINATES in (the command block's world, or the console's",
                "# default world) decides whether that command is restricted.",
                "#   mode = all      every world is restricted (default)",
                "#   mode = include  only commands originating in the worlds below are restricted;",
                "#                   commands from every other world behave like vanilla",
                "#   mode = exclude  every world is restricted except the worlds below",
                "mode = all",
                "# Comma-separated list. Only used when mode is include or exclude.",
                "worlds =",
                "#",
                "# --- Extra worlds a restricted command may also reach ------------------------------",
                "# reach.<origin world> = <world>, <world>, ...",
                "# A command originating in <origin world> may ALSO select entities in, and use",
                "# `execute in/at/as` to move into, the listed worlds (on top of its own world).",
                "# Reach is one-way: reach.lobby = spawn does not let spawn reach lobby.",
                "# Use * as the origin for \"any world\" and * as a target for \"every world\".",
                "# Examples:",
                "#   reach.lobby = spawn",
                "#   reach.* = lobby",
                "#   reach.minecraft:the_nether = world, world_the_end",
                "");
    }

    private static List<String> splitList(String value) {
        List<String> out = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static boolean intersects(Set<String> a, Set<String> b) {
        for (String s : b) {
            if (a.contains(s)) {
                return true;
            }
        }
        return false;
    }
}
