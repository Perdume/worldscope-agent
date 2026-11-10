package com.injectbukkit.worldscope;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reflective bridge between the agent's own classloader and whatever classloader the target
 * server's {@code net.minecraft.*} classes live on. Deliberately has zero compile-time
 * dependency on any NMS/Paper type, so this module builds without the target server jar and
 * without pinning to one Minecraft version.
 *
 * <p>Every public method here is fail-open by design: if reflection fails for any reason
 * (wrong method name for the running version, unexpected type, etc.) the original,
 * unfiltered data is returned and a single warning is logged - commands are never dropped,
 * blocked, or corrupted as a side effect of this class misbehaving.
 */
public final class EntityWorldFilter {

    private static final Map<Class<?>, Method> SOURCE_LEVEL_METHOD = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Method> ENTITY_LEVEL_METHOD = new ConcurrentHashMap<>();
    private static final AtomicBoolean WARNED = new AtomicBoolean(false);

    private EntityWorldFilter() {
    }

    /** Reflectively reads {@code CommandSourceStack#getLevel()} on the given source. */
    public static Object levelOfSource(Object commandSourceStack) {
        if (commandSourceStack == null) {
            return null;
        }
        try {
            Method method = SOURCE_LEVEL_METHOD.computeIfAbsent(commandSourceStack.getClass(),
                    cls -> resolveNoArgMethod(cls, "getLevel"));
            return method.invoke(commandSourceStack);
        } catch (Throwable t) {
            warnOnce("could not read the origin world of a dispatching command source", t);
            return null;
        }
    }

    /**
     * The world to record as a dispatch's origin, or {@code null} when the config says commands
     * from that world are not restricted ({@code null} makes every later hook skip its work, the
     * same as when the world couldn't be determined at all).
     */
    public static Object scopedOriginOf(Object commandSourceStack) {
        Object level = levelOfSource(commandSourceStack);
        if (level == null) {
            return null;
        }
        return WorldPolicy.isScoped(level) ? level : null;
    }

    /**
     * Whether a command that originated in {@code originLevel} may touch {@code level}: its own
     * world always, plus whatever the config's {@code reach.*} rules add.
     */
    public static boolean allowed(Object level, Object originLevel) {
        return level == originLevel || WorldPolicy.mayReach(originLevel, level);
    }

    /**
     * Returns {@code entities} unchanged unless every element can be proven to belong to
     * a world {@link #allowed allowed} from {@code originLevel}, in which case a copy containing
     * only the matching elements is returned. Never throws; falls back to returning
     * {@code entities} untouched on any error.
     */
    public static List<?> filterList(List<?> entities, Object originLevel) {
        if (entities == null || entities.isEmpty() || originLevel == null) {
            return entities;
        }
        try {
            boolean allMatch = true;
            for (Object entity : entities) {
                if (!allowed(levelOfEntity(entity), originLevel)) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch) {
                return entities;
            }
            List<Object> filtered = new ArrayList<>(entities.size());
            for (Object entity : entities) {
                if (allowed(levelOfEntity(entity), originLevel)) {
                    filtered.add(entity);
                }
            }
            return filtered;
        } catch (Throwable t) {
            warnOnce("could not filter a selector result by world - leaving it unfiltered", t);
            return entities;
        }
    }

    private static Object levelOfEntity(Object entity) throws ReflectiveOperationException {
        Method method = ENTITY_LEVEL_METHOD.computeIfAbsent(entity.getClass(),
                cls -> resolveNoArgMethod(cls, "level"));
        return method.invoke(entity);
    }

    private static Method resolveNoArgMethod(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0 && m.getReturnType() != void.class) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        throw new IllegalStateException("no " + name + "() accessor found on " + cls);
    }

    private static void warnOnce(String message, Throwable cause) {
        if (WARNED.compareAndSet(false, true)) {
            Log.warn("WorldScope " + message + " (this version's internals may not match the adapter in use): " + cause);
        }
    }
}
