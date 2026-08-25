package com.injectbukkit.worldscope;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;

/**
 * Surfaces which classes actually got patched (or failed to) so it's obvious from the
 * server log whether a given server build matches the installed adapters - important since
 * the whole point of the adapter split is that some versions are expected to not match.
 */
final class LoggingListener extends AgentBuilder.Listener.Adapter {

    @Override
    public void onTransformation(TypeDescription typeDescription, ClassLoader classLoader, JavaModule module,
                                  boolean loaded, DynamicType dynamicType) {
        Log.info("patched " + typeDescription.getName());
    }

    @Override
    public void onError(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded,
                         Throwable throwable) {
        Log.error("failed to patch " + typeName, throwable);
    }

    @Override
    public void onIgnored(TypeDescription typeDescription, ClassLoader classLoader, JavaModule module,
                           boolean loaded) {
        // Every class that isn't one of our named targets is "ignored" - way too noisy to log.
    }
}
