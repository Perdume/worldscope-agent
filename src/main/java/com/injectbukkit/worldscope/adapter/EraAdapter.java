package com.injectbukkit.worldscope.adapter;

import net.bytebuddy.agent.builder.AgentBuilder;

/**
 * One version-era's worth of hooks (a set of target class/method names plus the Advice that
 * patches them). Each Minecraft "mapping era" - e.g. Paper's Mojang-mapped classes from 1.20.5
 * onward, versus the per-version Spigot-remapped {@code net.minecraft.server.vX_Y_RZ} packages
 * used before that - needs its own adapter, since the class and method names differ.
 *
 * <p>Adapters never assume they are the only one installed and never assume their target
 * classes exist: {@link net.bytebuddy.matcher.ElementMatchers#named(String)} simply never
 * matches on a server where the class doesn't exist, so registering an adapter for a version
 * that isn't running is inert, not an error.
 */
public interface EraAdapter {

    /** Short identifier for logging, e.g. {@code "paper-mojmap-1.20.5+"}. */
    String id();

    /** Registers this era's type/method transformations on {@code builder} and returns the result. */
    AgentBuilder apply(AgentBuilder builder);
}
