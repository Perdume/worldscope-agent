# WorldScope Agent

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE) [![Ko-Fi](https://img.shields.io/badge/Ko--fi-Support%20me-72a4f2?style=for-the-badge&logo=ko-fi&logoColor=white)](https://ko-fi.com/N8Y025JF6H)

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

Three hooks, installed via [Byte Buddy](https://bytebuddy.net/) `Advice`:

1. **`Commands#executeCommandInContext`** - the single point every command and function
   dispatch passes through. Confirmed by decompiling a real Paper 26.2 build:
   - Console and command-block commands reach it via `performPrefixedCommand`; player-typed
     commands via `performCommand`; scheduled/tick functions (`#minecraft:tick`,
     `#minecraft:load`, fired `/schedule` callbacks) via `ServerFunctionManager#execute`.
   - A nested call within an already-running chain (`execute ... run function foo`) doesn't
     call this again - it queues onto the chain that's already running. So it fires once per
     independent dispatch, not once per command in a chain.
   - That queue drains in a plain synchronous loop with no suspension across ticks, so even a
     long function chain stays inside one continuous dispatch.

   On entry it records which world the dispatch came from; the record is cleared when the
   command finishes, even if it threw.
2. **`EntitySelector#findEntities` / `#findPlayers`** - where a selector resolves to actual
   entities. On exit, anything outside the recorded world is dropped from the result -
   regardless of whether the selector itself used coordinates.
3. **`CommandSourceStack#withLevel`** - the single method behind every dimension redirect
   (`execute in <dimension>`, and `execute at`/`as` copying a target's own dimension). If a
   redirect would move execution into a world other than the one recorded in step 1, this
   throws instead of letting the redirect happen, aborting that command chain. Paper's own
   top-level handlers already catch and report whatever a command throws, so this just
   surfaces as an ordinary command failure.

Step 2 only ever sees commands shaped around a selector (`kill @a`, `tp @s`, and so on).
Plenty of commands aren't - `setblock`, `fill`, `clone`, `weather`, `time`, `gamerule` - and
none of those have anything like "return an empty list" to fall back on if you want to refuse
them. Step 3 is what actually closes that: it catches a cross-world `execute in` before *any*
command runs, selector-shaped or not.

Because the checks happen at this level rather than by rewriting command text, they apply
uniformly everywhere - `execute`, `function`, nested command chains, anything that routes
through the vanilla dispatcher.

No NMS/Paper type is referenced at compile time - everything above goes through cached
reflection (`EntityWorldFilter`), so this module builds without the server jar as a
dependency. See "Version coverage" below for what that buys you.

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
outside of `executeCommandInContext` and bypasses the origin-tracking hook, so it isn't
filtered. Extending `ModernMojmapAdapter` to also hook
`CraftServer#selectEntities`/`VanillaCommandWrapper#getListener` would close this; not done
yet to keep the initial surface area small.

## Security considerations

Things that look like they could go wrong here, and what actually happens:

- **A hook silently stops matching (unsupported server build).** This is fail-open by
  design - no error, no blocked commands, the agent just does nothing. That's the right
  default so a version mismatch can't break command handling, but it also means the
  protection can go quiet without telling you. Check for the `patched ...` log lines on
  startup if you're relying on this rather than just wanting the common case fixed.
- **A bug in the hook itself breaks command handling server-wide.** Both advice classes wrap
  their entire body in `catch (Throwable)` and always fall back to vanilla's own unfiltered
  result. A crash inside this agent's filtering logic can't propagate out and take commands
  down with it - worst case, that one selector call goes unfiltered.
- **An exception between push and pop leaks a stale origin into later commands.** Handled by
  running the pop in `Advice.OnMethodExit(onThrowable = Throwable.class)`, so it runs whether
  the hooked method returns normally or throws.
- **Concurrent commands on different threads corrupting the origin stack.** Origin is tracked
  per-thread (`ThreadLocal`), and Paper's own `AsyncCatcher` already rejects command
  dispatch off the main thread, so this isn't actually reachable in practice.
- **A malicious plugin defeating or abusing this.** It isn't a defense against that. This
  agent stops *accidental* cross-world leakage from vanilla's own selector behavior; a
  plugin with server-side code already has full JVM access; it can bypass this agent, or for
  that matter do anything else it wants, with or without WorldScope installed. The bridge
  classes (`OriginContext`, `EntityWorldFilter`) are on the bootstrap classloader and
  therefore technically callable from any plugin, but there's nothing there a plugin
  couldn't already do more directly through reflection on Minecraft's own internals.
- **The agent jar itself being tampered with.** A `-javaagent` can rewrite arbitrary server
  behavior, same as any Java agent - only run a build you compiled yourself or downloaded
  from this project's own releases, and treat it with the same trust level as the server
  jar.

## What a player can and can't get away with

The section above is about this agent misbehaving. This one is about someone actively trying
to reach into a world that isn't theirs. Things worth trying, and what actually happens:

| Attempt | Result |
|---|---|
| Bare `@e`/`@a` with no coordinates | Confined to their own world - this is the core leak the agent exists to close |
| `execute in <other-world> run kill @a` | Refused - the redirect itself throws before `kill` ever runs |
| `execute in <other-world> run setblock`/`fill`/`weather`/`gamerule`/anything else with no selector | Refused the same way - the redirect is what gets caught, not the command that follows it |
| `execute at`/`as <selector>` reaching into another world | Can't - the selector is already confined to the origin world before `at`/`as` ever sees it, so there's no out-of-world target to redirect to |
| Targeting an exact player name or UUID instead of `@a`/`@e` (`/tp Steve`, `/data get entity <uuid>`) | Still confined - the single-target resolution methods call the same hooked ones internally, there's no separate path around them |
| Chaining several `execute in`/`at`/`as` redirects to obscure the jump | Doesn't help - every redirect is checked against the *original* origin, not whatever the level currently is, so the first hop that leaves it already throws |
| Triggering a `#minecraft:tick`/`#minecraft:load` function, or waiting out a `/schedule` callback | Scoped to the generic "game loop sender"'s world like any other dispatch - not a way to reach a specific other world on demand |
| A plugin calling `Bukkit.selectEntities(...)` on a player's behalf | Not covered - bypasses the hook entirely (see "Known gap"). This one needs a plugin; it isn't something reachable through chat or commands alone |

All of the above assumes the player already has permission to run the commands in question -
`execute` and `function` are gamemaster-level by default in vanilla. This agent narrows what
those commands can reach; it doesn't hand out any permission they didn't already have.

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
[WorldScope] patched net.minecraft.commands.CommandSourceStack
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
