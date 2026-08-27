package com.injectbukkit.worldscope.adapter.modern;

import com.injectbukkit.worldscope.OriginContext;
import net.bytebuddy.asm.Advice;

/**
 * Inlined into {@code CommandSourceStack#withLevel(ServerLevel)} - the single method behind
 * every dimension redirect in a command chain, confirmed against real Paper 26.2 source. Both
 * {@code execute in <dimension>} (an explicit, arbitrary dimension argument, not derived from
 * any entity) and {@code execute at/as <target>} (copying a target entity's own level) call
 * it. The latter is never a false positive: the target entity there was itself resolved
 * through a selector, which {@link EntitySelectorAdvice} has already confined to the origin
 * world, so its level always matches origin by the time it gets here.
 *
 * <p>{@link EntitySelectorAdvice} only ever sees commands shaped around a selector. Plenty of
 * commands aren't - {@code setblock}, {@code fill}, {@code clone}, {@code weather},
 * {@code time}, {@code gamerule} - and none of those have anything like "return an empty
 * list" to fall back on. The only way to refuse one is to stop the command chain outright,
 * which is what this does: if a redirect would move execution into a world other than the
 * one it started in, this throws instead of letting the original method run, aborting that
 * chain. Paper's own top-level command/function handlers already catch and report any
 * exception a command throws, so this surfaces as an ordinary command failure - it doesn't
 * propagate any further than that.
 */
public final class WithLevelAdvice {

    private WithLevelAdvice() {
    }

    @Advice.OnMethodEnter
    public static void enter(@Advice.Argument(0) Object targetLevel) {
        boolean crossesIntoAnotherWorld;
        try {
            Object origin = OriginContext.current();
            crossesIntoAnotherWorld = origin != null && targetLevel != null && targetLevel != origin;
        } catch (Throwable ignored) {
            crossesIntoAnotherWorld = false;
        }
        if (crossesIntoAnotherWorld) {
            throw new RuntimeException(
                    "WorldScope: refused - this command tried to reach into a different world than it started in");
        }
    }
}
