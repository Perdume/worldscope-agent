package com.injectbukkit.worldscope;

import com.injectbukkit.worldscope.adapter.EraAdapter;
import com.injectbukkit.worldscope.adapter.modern.ModernMojmapAdapter;
import net.bytebuddy.ClassFileVersion;
import net.bytebuddy.agent.builder.AgentBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * Entry point. Attach with {@code -javaagent:worldscope-agent-<version>.jar} on the server's
 * Java command line (before {@code -jar paper.jar}), or load it into an already-running JVM
 * via {@code agentmain} (dynamic attach).
 *
 * <p>System properties:
 * <ul>
 *   <li>{@code -Dworldscope.enabled=false} - install nothing, agent becomes a no-op.</li>
 *   <li>{@code -Dworldscope.verbose=true} - log every class patched and full stack traces on error.</li>
 * </ul>
 */
public final class WorldScopeAgent {

    private static final List<EraAdapter> ADAPTERS = Arrays.asList(
            new ModernMojmapAdapter()
            // Add a Spigot-mapped (net.minecraft.server.vX_Y_RZ) EraAdapter here once its exact
            // class/method names have been verified against a real server jar for that version.
    );

    private WorldScopeAgent() {
    }

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        install(instrumentation);
    }

    public static void agentmain(String agentArgs, Instrumentation instrumentation) {
        install(instrumentation);
    }

    private static void install(Instrumentation instrumentation) {
        if (!Boolean.parseBoolean(System.getProperty("worldscope.enabled", "true"))) {
            Log.info("disabled via -Dworldscope.enabled=false, installing nothing");
            return;
        }

        // Byte Buddy picks its class-file reader/writer per net.bytebuddy.utility.AsmClassReader
        // .Factory.Default#ASM_FIRST: if the running JDK's class file version is newer than what
        // Byte Buddy's bundled ASM release recognizes as "latest", it switches to an experimental
        // bridge to the JDK's own java.lang.classfile API instead. That bridge is shipped as a
        // multi-release jar entry, which shading does not preserve as such, so on a sufficiently
        // new JDK every single class read fails with "Type not available on current VM" - not
        // just our target classes, literally every class the JVM loads. Forcing ASM_ONLY here,
        // before any net.bytebuddy class is touched, makes it always use the bundled ASM
        // implementation directly and skips that bridge entirely. This must run before the first
        // reference to any net.bytebuddy class below, since the choice is cached in a static
        // field the first time it's read. Left alone if the JVM was already launched with this
        // property set, so an explicit operator choice isn't silently overridden.
        if (System.getProperty("net.bytebuddy.processor") == null) {
            System.setProperty("net.bytebuddy.processor", "ASM_ONLY");
        }

        // Separately, Byte Buddy 1.17.5 hard-rejects any class file version newer than the
        // newest JDK it was built against (currently Java 25) with a plain
        // IllegalArgumentException, unless a "<root package>.experimental" property is set -
        // needed on Paper builds that outrun Byte Buddy's own release cadence (e.g. Java 26).
        // The property is looked up under Byte Buddy's *relocated* package name (shading moves
        // net.bytebuddy to com.injectbukkit.worldscope.libs.bytebuddy - see pom.xml), so the
        // key is derived from a class actually in that package instead of hardcoding the
        // relocated string, which would silently go stale if the relocation target ever
        // changes.
        String experimentalProperty = ClassFileVersion.class.getPackage().getName() + ".experimental";
        if (System.getProperty(experimentalProperty) == null) {
            System.setProperty(experimentalProperty, "true");
        }

        try {
            instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(createBridgeJar()));
        } catch (Throwable t) {
            Log.error("could not add the bridge classes to the bootstrap classloader search path; aborting install", t);
            return;
        }

        AgentBuilder builder = new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(new LoggingListener());

        for (EraAdapter adapter : ADAPTERS) {
            Log.debug("registering era adapter: " + adapter.id());
            builder = adapter.apply(builder);
        }

        builder.installOn(instrumentation);
        Log.info("installed - selectors without coordinates in commands originating from a "
                + "command block or the console will now be scoped to their origin world");
    }

    /**
     * Only {@link Log}, {@link OriginContext} and {@link EntityWorldFilter} are ever called
     * from <i>inside</i> advice bodies that end up inlined into {@code net.minecraft.*}
     * bytecode, so only those three classes need to be resolvable from whatever classloader
     * loaded that bytecode. Everything else in this agent (this class included) stays on the
     * normal application classloader.
     *
     * <p>Appending this agent's whole jar to the bootstrap search path instead would make
     * classes like {@link WorldScopeAgent} itself reachable through <em>two</em> classloaders
     * (app, because it's already loaded from the -javaagent classpath by the time this runs;
     * bootstrap, because parent-delegation now finds it there too) which splits them into two
     * distinct runtime packages and throws {@link IllegalAccessError} the moment one touches
     * a package-private member of the other. Building a small standalone jar with only the
     * three classes that actually need it avoids that split entirely.
     */
    private static File createBridgeJar() throws Exception {
        String[] bridgeClasses = {
                "com/injectbukkit/worldscope/Log.class",
                "com/injectbukkit/worldscope/OriginContext.class",
                "com/injectbukkit/worldscope/EntityWorldFilter.class",
        };
        File bridgeJar = File.createTempFile("worldscope-bridge-", ".jar");
        bridgeJar.deleteOnExit();
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(bridgeJar))) {
            for (String resourcePath : bridgeClasses) {
                try (InputStream in = WorldScopeAgent.class.getClassLoader().getResourceAsStream(resourcePath)) {
                    if (in == null) {
                        throw new IllegalStateException("could not find " + resourcePath + " on this agent's own classpath");
                    }
                    out.putNextEntry(new JarEntry(resourcePath));
                    in.transferTo(out);
                    out.closeEntry();
                }
            }
        }
        return bridgeJar;
    }
}
