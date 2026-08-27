package com.injectbukkit.worldscope.adapter.modern;

import com.injectbukkit.worldscope.EntityWorldFilter;
import com.injectbukkit.worldscope.OriginContext;
import net.bytebuddy.asm.Advice;

/**
 * Inlined into {@code net.minecraft.commands.Commands#executeCommandInContext(CommandSourceStack, Consumer)} -
 * confirmed by decompiling a real Paper 26.2 build to be the single funnel every top-level
 * command dispatch passes through, console and command-block commands
 * ({@code performPrefixedCommand}) and player-typed ones ({@code performCommand}) alike, since
 * both call this internally. It's also the same method {@code ServerFunctionManager#execute}
 * calls for {@code #minecraft:tick}/{@code #minecraft:load} auto-run functions and for
 * {@code /schedule function} callbacks once they fire - those don't carry the world of
 * whoever scheduled them (vanilla itself discards that, replacing it with a generic
 * server-wide sender before calling this), so they end up scoped to that generic sender's
 * world rather than left unscoped.
 *
 * <p>A nested command/function call within an already-running chain (e.g. {@code execute ...
 * run function foo}) does not call this method again - it queues directly onto the already
 * running {@code ExecutionContext} instead - so this only fires once per genuinely independent
 * top-level dispatch, not once per command in a chain.
 *
 * <p>Advice methods are inlined by Byte Buddy directly into the target method's bytecode -
 * they must stay static and side-effect-free beyond what's written here.
 */
public final class ExecuteCommandInContextAdvice {

    private ExecuteCommandInContextAdvice() {
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
