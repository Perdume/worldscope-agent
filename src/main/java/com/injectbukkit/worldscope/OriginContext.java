package com.injectbukkit.worldscope;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Tracks, per thread, the world (NMS {@code Level}/{@code ServerLevel} instance) that the
 * command currently being dispatched on this thread originated from.
 *
 * <p>This class is appended to the bootstrap classloader search path by {@link WorldScopeAgent}
 * so that advice inlined into {@code net.minecraft.*} classes - which are loaded by a
 * classloader that cannot see this agent's own classloader - can still resolve it.
 *
 * <p>A {@link Deque} (not a single field) is used so nested/recursive command dispatch
 * (e.g. a function calling another command) restores the correct outer origin when the
 * inner dispatch finishes, instead of leaking the inner one.
 */
public final class OriginContext {

    private static final ThreadLocal<Deque<Object>> STACK = ThreadLocal.withInitial(ArrayDeque::new);

    private OriginContext() {
    }

    /**
     * @param level the origin world for the command dispatch that is starting, or {@code null}
     *              if it could not be determined (filtering is then skipped for this dispatch).
     */
    public static void push(Object level) {
        STACK.get().push(level);
    }

    public static void pop() {
        Deque<Object> deque = STACK.get();
        if (!deque.isEmpty()) {
            deque.pop();
        }
    }

    /**
     * @return the origin world of the command dispatch currently executing on this thread,
     *         or {@code null} if none is tracked (nothing should be filtered in that case).
     */
    public static Object current() {
        Deque<Object> deque = STACK.get();
        return deque.isEmpty() ? null : deque.peek();
    }
}
