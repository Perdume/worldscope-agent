Stops command blocks, console commands, and even players themselves from reaching into worlds they have no business touching.

## Why you need this

In vanilla Minecraft, a selector like `@e` or `@a` **without coordinates** isn't limited to the world it was run from — by design, it searches *every loaded world on the server*. A command block sitting quietly in your survival world running `/kill @e[type=zombie]` will also kill zombies in your creative world, your nether, your minigame arena — anywhere a chunk happens to be loaded at that moment. Same goes for a player just typing `/kill @a` themselves, or a console command.

That's not a bug — it's just how vanilla selectors work. But on a multi-world server it means every world is leaking into every other one unless you're careful with every single command.

WorldScope shuts that door. No config, no setup — every command a player types, every command block, every console command now stays inside the world it started in.

## What it does

- Confines bare selectors (`@e`, `@a`, `@r`) to the world the command actually came from — command blocks, console, and players all covered
- Refuses `execute in <other world>` reaching into a different world outright, instead of quietly redirecting it back to your own world — nothing happens, no surprises
- Fails open: if it can't make sense of what's going on (unsupported server build, weird internals), it gets out of the way instead of breaking your commands
- Zero configuration — install it and forget about it

## Installation — please read, this is not a normal plugin

WorldScope is a **Java agent**, not a plugin. It patches the server's command handling directly, at a level a `.jar` dropped into `/plugins` simply cannot reach. That means installing it is different too — it goes on your server's **start command**, not in your plugins folder:

```
java -javaagent:worldscope-agent-<version>.jar -jar paper.jar nogui
```

The `-javaagent:` flag has to come **before** `-jar paper.jar`.

Using a hosting panel (Pterodactyl and similar)? Add `-javaagent:worldscope-agent-<version>.jar` to the *startup command* or *additional JVM arguments* field instead of editing a script directly.

A successful start prints this in your console:

```
[WorldScope] installed - selectors without coordinates in commands originating from a command block or the console will now be scoped to their origin world
```

## Supported servers

- **Paper 1.20.5 and newer**, and forks built on it (Purpur, etc.)
- Java 17 through 26 (tested directly on 17, 25, and 26)
- Plain Spigot/CraftBukkit and Paper builds older than 1.20.5 aren't supported yet

## Known limitations

- A handful of plugins that select entities through their own internal API path rather than a normal command aren't covered yet
- No config file yet — the behavior is fixed: always scope to the command's own world, always refuse explicit cross-world reach

## Why isn't this a normal plugin?

Because this behavior lives inside the server's own command-parsing code, several layers below anything a plugin's public API is allowed to see or touch. A Java agent is the only way to intervene at that point without replacing server files by hand.

## Found a bug?

Report it on the [issue tracker](https://github.com/Perdume/worldscope-agent/issues) — include your Paper version, Java version, and the `[WorldScope]` lines from your server log.
