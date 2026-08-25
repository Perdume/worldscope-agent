# WorldScope Agent

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

Java agent that bytecode-instruments Paper's command pipeline so that command-block and
console commands can't reach into worlds they didn't originate from.

## The behavior this prevents

Vanilla Minecraft's target selectors (`@e`, `@a`, `@r`) only restrict themselves to the
current dimension when the selector carries a positional argument (`x`, `y`, `z`, `dx`,
`dy`, `dz`, or `distance`). A bare `@e[type=zombie]` in a command block or typed at the
console searches **every loaded world on the server**, not just the one the command block
sits in. This isn't a bug - it's documented vanilla behavior, confirmed against Paper's
Mojang-mapped source (`EntitySelector#isWorldLimited()`) - but it's exactly the cross-world
command interference this project exists to prevent.

## How it works

Two hooks, installed via [Byte Buddy](https://bytebuddy.net/) `Advice` at class-load time:

1. **`net.minecraft.commands.Commands#performPrefixedCommand`** - the single funnel every
   console command and (on Paper) every command-block command passes through. On entry, the
   dispatching source's world is pushed onto a per-thread stack; popped on exit (including on
   exception, so the stack never leaks).
2. **`net.minecraft.commands.Commands#performCommand`** (2- and 3-argument overloads) - the
   separate entry point a command a *player* types goes through
   (`ServerGamePacketListenerImpl` routes there, not through `performPrefixedCommand`). The
   source has to be pulled out of the `ParseResults` argument
   (`getContext().getSource()`) instead of being handed directly, but otherwise does the same
   push/pop as step 1.
3. **`net.minecraft.commands.arguments.selector.EntitySelector#findEntities` /
   `#findPlayers`** - where a selector actually turns into a list of entities. On exit, any
   entity not in the world captured in step 1/2 is dropped from the result, regardless of
   whether the selector itself was position-limited.

The result is scoped by construction - not by rewriting the command text - so it applies
uniformly to `execute`, `function`, nested command chains, and any Bukkit command that
ultimately routes through the vanilla dispatcher.

**Explicit cross-world reach is refused, not redirected.** If a command has already been
routed into a different world by the time the selector runs (`execute in <other-world> run
kill @a`, run by someone whose own world isn't `<other-world>`), step 3 compares the
selector's *current* world against the *origin* world from step 1/2 and, if they differ,
returns nothing at all - it does not fall back to silently re-scoping the selector to the
origin world instead. A player in the nether running `execute in world run kill @a` kills
no one, not the nether's own players either.

No NMS/Paper type is referenced at compile time: everything in step 1/2 above is invoked
through cached reflection (`EntityWorldFilter`), so this module builds without the server
jar as a dependency. See "Version coverage" below for what that buys you.

### Safety

Every hook is fail-open: if reflection can't find the method it expects (wrong version),
it logs one warning and passes the original, unfiltered value through - it never blocks,
empties, or corrupts a command result as a side effect of a bug in this agent. Set
`-Dworldscope.enabled=false` to disable the agent entirely without removing the `-javaagent`
flag.

## Version coverage

| Era | Status |
|---|---|
| Paper 1.20.5+ (Mojang-mapped internals) | Implemented, verified against Paper's published source |
| Paper/Spigot < 1.20.5 (`net.minecraft.server.vX_Y_RZ`-style packages) | Not implemented yet |
| Plain Spigot/CraftBukkit (any version) | Console-only even if an adapter existed - CraftBukkit doesn't call `ServerCommandEvent`-equivalent internals for command blocks; out of scope here since this agent hooks NMS directly, not that event |

Adding an older version: implement `EraAdapter` (see `ModernMojmapAdapter` for the pattern),
register it in `WorldScopeAgent.ADAPTERS`. Each adapter is independent and inert on servers
where its target classes don't exist - registering one for a version that isn't running is
safe, not an error.

### Known gap

`Bukkit.selectEntities(...)` (used by some plugins) builds its own `CommandSourceStack`
outside of `performPrefixedCommand` and bypasses the origin-tracking hook, so it isn't
filtered. Extending `ModernMojmapAdapter` to also hook
`CraftServer#selectEntities`/`VanillaCommandWrapper#getListener` would close this; not done
yet to keep the initial surface area small.

## Build

```
mvn package
```

Produces `target/worldscope-agent-<version>.jar` (Byte Buddy is shaded and relocated inside
it - no runtime dependency to ship alongside).

## Run

Add the agent flag *before* the server jar in your start script:

```
java -javaagent:/path/to/worldscope-agent-0.1.0.jar -Xms4G -Xmx4G -jar paper.jar nogui
```

On startup, watch the console for:

```
[WorldScope] registering era adapter: paper-mojmap-1.20.5+
[WorldScope] patched net.minecraft.commands.Commands
[WorldScope] patched net.minecraft.commands.arguments.selector.EntitySelector
[WorldScope] installed - selectors without coordinates in commands originating from a command block or the console will now be scoped to their origin world
```

If you only see `registering era adapter` with no `patched` lines, the running server's
internals didn't match `ModernMojmapAdapter` (i.e. it's an unsupported version) - the agent
is loaded but doing nothing, safely.

Add `-Dworldscope.verbose=true` for per-class patch confirmation and full stack traces on
error.

### If you see `[WorldScope] [error] failed to patch ...: Could not invoke proxy: Type not available on current VM: ...JdkClassReader` for classes unrelated to this agent (log4j, Paperclip, etc.)

Byte Buddy auto-selects between its bundled ASM implementation and an experimental bridge to
the JDK's own class-file API depending on how new the running JDK is
(`AsmClassReader.Factory.Default#ASM_FIRST`). That bridge ships as a multi-release jar entry,
which doesn't survive shading intact, so on a sufficiently new JDK every class read fails -
not just this agent's targets, literally every class the JVM loads, which is what produces a
huge wall of unrelated errors like this.

The agent forces Byte Buddy to always use its classic ASM implementation
(`-Dnet.bytebuddy.processor=ASM_ONLY`, set programmatically on startup, before touching any
Byte Buddy class) specifically to avoid this. If you still see these errors, something on
your classpath is setting `net.bytebuddy.processor` to something else first (the agent
intentionally does not override an already-set value) - check your JVM args for it.

### If you see `IllegalArgumentException: Java 26 (70) is not supported by the current version of Byte Buddy which officially supports Java 25 (69)`

Byte Buddy 1.17.5 hard-rejects any class file version newer than the newest JDK it was built
against - currently Java 25 - unless a `<byte buddy's root package>.experimental` property is
set. The agent sets this too, deriving the property key from Byte Buddy's actual (relocated)
package name via reflection at startup instead of hardcoding the shaded string, so it keeps
working even if the relocation target in `pom.xml` ever changes. Verified against a real
Paper 26.2 build running on OpenJDK 26.0.2.1.

If a future Byte Buddy release moves its official support past your JDK version, this
property stops being necessary; if your JDK gets newer than *that* ceiling, bump
`byte-buddy.version` in `pom.xml` to a release that supports it.

## Issues

Found a bug, hit an unsupported server version, or have a feature request? Open one on the
[issue tracker](https://github.com/Perdume/worldscope-agent/issues). Include your Paper
version, Java version, and the `[WorldScope]` lines from your server log - see the
`-Dworldscope.verbose=true` flag above for a more detailed log if needed.
