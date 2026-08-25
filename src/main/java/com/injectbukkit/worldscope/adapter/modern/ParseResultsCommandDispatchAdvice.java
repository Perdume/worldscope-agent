package com.injectbukkit.worldscope.adapter.modern;

import com.injectbukkit.worldscope.EntityWorldFilter;
import com.injectbukkit.worldscope.OriginContext;
import net.bytebuddy.asm.Advice;

/**
 * Inlined into {@code Commands#performCommand(ParseResults, String)} and its 3-argument Paper
 * overload - the entry point used when a <i>player</i> types a command (routed there from
 * {@code ServerGamePacketListenerImpl}), as opposed to {@link CommandDispatchAdvice}'s
 * {@code performPrefixedCommand}, used for console and command-block commands.
 *
 * <p>Unlike that method, the command source isn't passed directly here - it has to be pulled
 * out of the parse results via {@code ParseResults#getContext()#getSource()}.
 */
public final class ParseResultsCommandDispatchAdvice {

    private ParseResultsCommandDispatchAdvice() {
    }

    @Advice.OnMethodEnter
    public static void enter(@Advice.Argument(0) Object parseResults) {
        Object originLevel;
        try {
            Object source = EntityWorldFilter.sourceFromParseResults(parseResults);
            originLevel = EntityWorldFilter.levelOfSource(source);
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
