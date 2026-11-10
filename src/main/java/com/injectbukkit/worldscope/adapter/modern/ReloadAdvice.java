package com.injectbukkit.worldscope.adapter.modern;

import com.injectbukkit.worldscope.WorldPolicy;
import net.bytebuddy.asm.Advice;

/**
 * Inlined into the server's two reload entry points so that reloading the server also re-reads
 * {@code worldscope.properties}:
 *
 * <ul>
 *   <li>{@code org.bukkit.craftbukkit.CraftServer#reload()} - what Bukkit's {@code /reload confirm}
 *       (and {@code Bukkit.reload()} from a plugin) ends up calling.</li>
 *   <li>{@code net.minecraft.server.MinecraftServer#reloadResources(...)} - what vanilla's
 *       {@code /minecraft:reload} and datapack enable/disable go through.</li>
 * </ul>
 *
 * <p>Bukkit's reload runs vanilla's from inside itself, so the two can nest. {@link WorldPolicy}
 * counts nesting per thread and re-reads the file once, when the outermost one finishes, instead
 * of once per hook. The exit runs even if the reload throws, so the count can't get stuck above
 * zero and silently disable reloading for the rest of the run.
 *
 * <p>Both bodies swallow every {@code Throwable}: a problem in this agent must never be able to
 * fail the server's own reload.
 */
public final class ReloadAdvice {

    private ReloadAdvice() {
    }

    @Advice.OnMethodEnter
    public static void enter() {
        try {
            WorldPolicy.reloadEnter();
        } catch (Throwable ignored) {
            // fail-open: the reload itself carries on untouched
        }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit() {
        try {
            WorldPolicy.reloadExit();
        } catch (Throwable ignored) {
            // fail-open
        }
    }
}
