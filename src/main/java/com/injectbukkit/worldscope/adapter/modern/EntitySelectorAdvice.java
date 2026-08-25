package com.injectbukkit.worldscope.adapter.modern;

import com.injectbukkit.worldscope.EntityWorldFilter;
import com.injectbukkit.worldscope.OriginContext;
import net.bytebuddy.asm.Advice;

import java.util.Collections;
import java.util.List;

/**
 * Inlined into {@code EntitySelector#findEntities(CommandSourceStack)} and
 * {@code EntitySelector#findPlayers(CommandSourceStack)}. Vanilla only restricts a selector to
 * the current dimension when it carries a positional argument (x/y/z/dx/dy/dz/distance); a bare
 * {@code @e} or {@code @a} otherwise searches every loaded world. This advice always narrows the
 * result to the world the command originated from (tracked by {@link CommandDispatchAdvice} /
 * {@link ParseResultsCommandDispatchAdvice}), regardless of whether the selector was itself
 * position-limited.
 *
 * <p>If the command has already been redirected into a <i>different</i> world by the time the
 * selector runs (e.g. {@code execute in <other-world> run kill @a}, executed by someone whose
 * own world is not {@code <other-world>}), this yields nothing rather than silently re-scoping
 * the selector back to the origin world. Falling back to the origin there would mean the
 * command still does something - just not the something anyone asked for, and not obviously
 * connected to the world the command explicitly named either. Refusing outright is the
 * unsurprising behavior for "you cannot reach into a world that isn't yours."
 */
public final class EntitySelectorAdvice {

    private EntitySelectorAdvice() {
    }

    @Advice.OnMethodExit
    public static void exit(@Advice.Argument(0) Object source, @Advice.Return(readOnly = false) List<?> result) {
        try {
            Object origin = OriginContext.current();
            if (origin != null) {
                Object currentLevel = EntityWorldFilter.levelOfSource(source);
                if (currentLevel != null && currentLevel != origin) {
                    result = Collections.emptyList();
                } else {
                    result = EntityWorldFilter.filterList(result, origin);
                }
            }
        } catch (Throwable ignored) {
            // fail-open: leave the vanilla result untouched
        }
    }
}
