package com.injectbukkit.worldscope;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Holds the loaded {@link WorldConfig} and answers the two questions the advice needs, given
 * live {@code ServerLevel} objects: "is a command from this world restricted at all?" and "may a
 * restricted command from this world also reach that one?".
 *
 * <p>A world is identified by every name it goes by, all lowercased: its Bukkit name
 * ({@code level.getWorld().getName()} - what admins know as {@code world_nether}) and its
 * dimension key ({@code minecraft:the_nether}). Either one in the config file matches. Both are
 * read reflectively, like everything else here, so there is no compile-time dependency on the
 * server.
 *
 * <p>Nothing here throws, and every failure resolves to the <i>strict</i> answer - a world that
 * can't be named is treated as restricted and as unable to reach anything - so a version mismatch
 * can't make the agent looser than its defaults. With the default config no world name is ever
 * looked up at all.
 *
 * <p>Part of the bootstrap bridge jar (see {@link WorldScopeAgent}), so it must only be touched
 * for the first time <i>after</i> that jar is on the bootstrap search path.
 */
public final class WorldPolicy {

    /** {@code -Dworldscope.config=<path>} points at a config file other than the default one. */
    public static final String CONFIG_PROPERTY = "worldscope.config";
    /** Looked up in the server's working directory when {@link #CONFIG_PROPERTY} isn't set. */
    public static final String DEFAULT_CONFIG_FILE = "worldscope.properties";

    private static volatile WorldConfig config = WorldConfig.DEFAULT;

    private static final Map<Object, Set<String>> IDS = Collections.synchronizedMap(new WeakHashMap<Object, Set<String>>());
    private static final Map<String, Optional<Method>> METHODS = new ConcurrentHashMap<>();
    private static final AtomicBoolean WARNED = new AtomicBoolean(false);
    private static final ThreadLocal<int[]> RELOAD_DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private WorldPolicy() {
    }

    /** Reads the config file at startup. Anything that goes wrong leaves the strict defaults in place. */
    public static void initialize() {
        try {
            config = load(false);
        } catch (Throwable t) {
            Log.error("could not load the config - every world stays restricted", t);
            config = WorldConfig.DEFAULT;
        }
    }

    /**
     * Called when a server reload starts ({@code CraftServer#reload}, {@code MinecraftServer#reloadResources}).
     * Bukkit's reload calls vanilla's from inside itself, so these nest; only the outermost one
     * re-reads the config, when it finishes (see {@link #reloadExit()}).
     */
    public static void reloadEnter() {
        RELOAD_DEPTH.get()[0]++;
    }

    /** Re-reads the config once the outermost reload that started with {@link #reloadEnter()} ends. */
    public static void reloadExit() {
        int[] depth = RELOAD_DEPTH.get();
        if (depth[0] > 0 && --depth[0] == 0) {
            reload();
        }
    }

    /**
     * Unlike startup, a config that can't be read (unreadable file, bad encoding) keeps the
     * settings that were already running instead of dropping back to the defaults: nobody
     * mid-edit wants a half-saved file to silently change how their worlds behave.
     */
    private static void reload() {
        try {
            config = load(true);
            IDS.clear();
        } catch (Throwable t) {
            Log.error("could not reload the config - keeping the previous settings", t);
        }
    }

    /** @return {@code false} only when the config says commands from this world are not restricted. */
    public static boolean isScoped(Object originLevel) {
        WorldConfig current = config;
        if (current.mode() == WorldConfig.MODE_ALL) {
            return true;
        }
        try {
            Set<String> ids = idsOf(originLevel);
            if (ids.isEmpty()) {
                warnOnce("could not read a world's name, so it is treated as restricted");
                return true;
            }
            return current.isScoped(ids);
        } catch (Throwable t) {
            warnOnce("could not check whether a world is restricted, so it is treated as restricted: " + t);
            return true;
        }
    }

    /** @return whether commands originating in {@code originLevel} may also reach {@code targetLevel}. */
    public static boolean mayReach(Object originLevel, Object targetLevel) {
        WorldConfig current = config;
        if (!current.hasReachRules() || originLevel == null || targetLevel == null) {
            return false;
        }
        try {
            return current.mayReach(idsOf(originLevel), idsOf(targetLevel));
        } catch (Throwable t) {
            warnOnce("could not check a reach rule, so the world is treated as out of reach: " + t);
            return false;
        }
    }

    private static WorldConfig load(boolean reloading) throws IOException {
        String explicit = System.getProperty(CONFIG_PROPERTY);
        Path path = Paths.get(explicit != null ? explicit : DEFAULT_CONFIG_FILE).toAbsolutePath();

        if (!Files.isRegularFile(path)) {
            if (explicit == null) {
                writeDefaultFile(path);
            } else {
                Log.warn("config file " + path + " (from -D" + CONFIG_PROPERTY + ") not found - every world is restricted");
            }
            return WorldConfig.DEFAULT;
        }

        List<String> problems = new ArrayList<>();
        WorldConfig parsed = WorldConfig.parse(Files.readAllLines(path, StandardCharsets.UTF_8), problems);
        for (String problem : problems) {
            Log.warn("config " + path + ": " + problem);
        }
        Log.info((reloading ? "config reloaded from " : "config ") + path + ": " + parsed.describe());
        return parsed;
    }

    private static void writeDefaultFile(Path path) {
        try {
            Files.write(path, WorldConfig.defaultFileContents().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
            Log.info("no config found, wrote the defaults to " + path + " (every world restricted, same as before)");
        } catch (IOException e) {
            Log.warn("no config found and could not write one to " + path + " (" + e + ") - every world is restricted");
        }
    }

    private static Set<String> idsOf(Object level) throws ReflectiveOperationException {
        Set<String> cached = IDS.get(level);
        if (cached != null) {
            return cached;
        }
        Set<String> ids = new HashSet<>();

        Object world = invokeNoArg(level, "getWorld");
        if (world != null) {
            addId(ids, invokeNoArg(world, "getName"));
        }

        // ResourceKey#toString is "ResourceKey[minecraft:dimension / minecraft:the_nether]". Parsing that
        // instead of calling location()/identifier() keeps working across Mojang's renames of both.
        Object dimension = invokeNoArg(level, "dimension");
        if (dimension != null) {
            String text = dimension.toString();
            int separator = text.indexOf(" / ");
            if (separator >= 0) {
                String key = text.substring(separator + 3).trim();
                if (key.endsWith("]")) {
                    key = key.substring(0, key.length() - 1);
                }
                addId(ids, key);
            }
        }

        Set<String> result = Collections.unmodifiableSet(ids);
        if (!ids.isEmpty()) {
            IDS.put(level, result);
        }
        return result;
    }

    private static void addId(Set<String> ids, Object name) {
        if (name != null) {
            String id = name.toString().trim().toLowerCase(Locale.ROOT);
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
    }

    /** Calls a public-or-not zero-arg method by name, walking up the class hierarchy; {@code null} if there is none. */
    private static Object invokeNoArg(Object target, final String name) throws ReflectiveOperationException {
        final Class<?> type = target.getClass();
        Optional<Method> method = METHODS.computeIfAbsent(type.getName() + '#' + name, k -> Optional.ofNullable(findNoArg(type, name)));
        return method.isPresent() ? method.get().invoke(target) : null;
    }

    private static Method findNoArg(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0 && m.getReturnType() != void.class) {
                    try {
                        m.setAccessible(true);
                        return m;
                    } catch (RuntimeException e) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private static void warnOnce(String message) {
        if (WARNED.compareAndSet(false, true)) {
            Log.warn("WorldScope " + message + " (this version's internals may not match what the config expects)");
        }
    }
}
