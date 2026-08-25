package com.injectbukkit.worldscope.adapter.modern;

import com.injectbukkit.worldscope.EntityWorldFilter;
import com.injectbukkit.worldscope.OriginContext;
import net.bytebuddy.asm.Advice;

/**
 * Inlined into {@code net.minecraft.commands.Commands#performPrefixedCommand(CommandSourceStack, String)},
 * the single funnel console commands, command-block commands, and (transitively) everything
 * they call passes through. Records which world the dispatch originated from for the
 * duration of the call, so {@link EntitySelectorAdvice} can constrain selector results to it.
 *
 * <p>Advice methods are inlined by Byte Buddy directly into the target method's bytecode -
 * they must stay static and side-effect-free beyond what's written here.
 */
public final class CommandDispatchAdvice {

    private CommandDispatchAdvice() {
    }

    @Advice.OnMethodEnter
    public static void enter(@Advice.Argument(0) Object commandSourceStack) {
        Object originLevel;
        try {
            originLevel = EntityWorldFilter.levelOfSource(commandSourceStack);
        } catch (Throwable ignored) {
            originLevel = null;
        }
        OriginContext.push(originLevel);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit() {
        OriginContext.pop();
    }
}
