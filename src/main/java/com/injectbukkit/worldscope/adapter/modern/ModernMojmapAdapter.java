package com.injectbukkit.worldscope.adapter.modern;

import com.injectbukkit.worldscope.adapter.EraAdapter;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

/**
 * Targets Paper builds using Mojang-mapped internals (Paper 1.20.5 and newer). Class and
 * method names in this era are stable across Minecraft versions - verified against Paper's
 * own patch sources - which is why one adapter can cover the whole era instead of one per
 * Minecraft version.
 *
 * <p>Does nothing (safely) on servers where these classes don't exist, e.g. plain Spigot/
 * CraftBukkit, Paper &lt; 1.20.5, or non-Paper platforms.
 */
public final class ModernMojmapAdapter implements EraAdapter {

    private static final String COMMANDS_CLASS = "net.minecraft.commands.Commands";
    private static final String ENTITY_SELECTOR_CLASS = "net.minecraft.commands.arguments.selector.EntitySelector";

    @Override
    public String id() {
        return "paper-mojmap-1.20.5+";
    }

    @Override
    public AgentBuilder apply(AgentBuilder builder) {
        // Confirmed by decompiling a real Paper 26.2 build: performPrefixedCommand (console,
        // command blocks) and performCommand (players) both funnel into this one static method,
        // and it's also what ServerFunctionManager#execute calls for tick/load auto-run
        // functions and fired /schedule callbacks - one hook covers all of it.
        builder = builder
                .type(ElementMatchers.named(COMMANDS_CLASS))
                .transform((b, typeDescription, classLoader, module, protectionDomain) -> b.visit(
                        Advice.to(ExecuteCommandInContextAdvice.class).on(
                                ElementMatchers.named("executeCommandInContext")
                                        .and(ElementMatchers.takesArguments(2)))));

        builder = builder
                .type(ElementMatchers.named(ENTITY_SELECTOR_CLASS))
                .transform((b, typeDescription, classLoader, module, protectionDomain) -> b.visit(
                        Advice.to(EntitySelectorAdvice.class).on(
                                ElementMatchers.namedOneOf("findEntities", "findPlayers")
                                        .and(ElementMatchers.takesArguments(1)))));

        return builder;
    }
}
