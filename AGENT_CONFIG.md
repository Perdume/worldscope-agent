# AGENT_CONFIG.md

Contract for an AI assistant that writes, edits, explains or debugs WorldScope's
`worldscope.properties`. Read it before touching that file. (Humans: "Configuration" in
`README.md` is the short version.)

## Model

WorldScope restricts a command by the world it **originates** in.

- A **restricted** command's selectors resolve only in its *allowed worlds*, and `execute in|at|as`
  into any other world is refused (the chain aborts with an exception whose message starts
  `WorldScope: refused`; how the server shows it to the player is not confirmed).
- **Allowed worlds** = the origin itself + every world its `reach.<origin>` rules name. Reach is
  one-way and not transitive: `reach.a = b` with `reach.b = c` lets `a` touch `b`, never `c`.
- **Origin** = the command block's world; a player's own world; for console, `#minecraft:tick`,
  `#minecraft:load` and `/schedule` functions, the server's default world (normally the overworld -
  assumed from vanilla, not yet confirmed on a real Paper build).
- Two independent switches. `mode` + `worlds` decide **whether an origin is restricted at all**.
  `reach.<origin>` decides **what a restricted origin may also touch**; an unrestricted origin's
  reach rules are never consulted.
- No file, or the generated default file: every world restricted, no reach.

## File contract

```
file    = *( blank | comment | entry )
comment = ( "#" | ";" ) text               ; whole line only, no trailing comments
entry   = key "=" value                    ; split at the FIRST "=", both sides trimmed
key     = "mode" | "worlds" | "reach." origin          ; keys are case-insensitive
value   = mode:           "all" | "include" | "exclude"    ; case-insensitive
          worlds, reach:  [ name *( "," name ) ]           ; empty list allowed
origin  = name | "*"                       ; "*" = any origin
name    = Bukkit world name | dimension key  ; case-insensitive
          ; "*" as a reach TARGET = every world. "*" inside `worlds` is a literal name that matches nothing.
```

- UTF-8, LF or CRLF. Repeated `worlds` lines, and repeated `reach.<origin>` lines for one origin,
  merge (union). For `mode`, the last valid line wins.
- A line that doesn't parse is skipped with `[warn] config <path>: line N: ...`. An invalid `mode`
  value keeps the previous one (default `all`). Unknown key: warned and ignored (`world =` instead
  of `worlds =` is the usual cause). `mode = all # note` is an invalid mode: no inline comments.
- `:` is fine inside names (`reach.minecraft:the_nether = world`). A name can't contain `,`, and a
  reach origin can't contain `=`.

| `mode` | Origins that are restricted |
|---|---|
| `all` (default) | every world; `worlds` is ignored |
| `include` | only the worlds in `worlds` (empty list: none, and a warning) |
| `exclude` | every world except those in `worlds` |

## Names

A world matches by its **Bukkit name** (`world`, `world_nether`, `world_the_end`, or a custom
name) **or** its **dimension key** (`minecraft:overworld`, `minecraft:the_nether`,
`minecraft:the_end`). Write Bukkit names; fall back to the dimension key only when a Bukkit name
doesn't match. A world whose name can't be read at all stays restricted under `include`/`exclude`.

WorldScope doesn't know which worlds exist, so it never warns about a name. What a misspelling does:

| Misspelled name in | Effect |
|---|---|
| `exclude` list | that world stays restricted (safe) |
| a `reach` origin or target | no reach is granted (safe) |
| `include` list | that world is **not** restricted (unsafe) |

Choose `exclude` unless the user asks for an allow-list, and read every `include` name back to them.

## Procedure: write a config for the user

1. **Pin the names.** Take each world name from the server (a world-list command, the world
   folders, or the user), never from memory. Done when every world in the plan is spelled exactly
   as the server reports it; ask the user about any you can't confirm.
2. **Pick the shape** from the table below. Done when each sentence of the user's intent maps to a
   `mode`/`worlds` line, a `reach` line, or a launch flag.
3. **Write the file.** Path: `-Dworldscope.config=<path>` when the start script sets it, else
   `worldscope.properties` in the server's working directory (the startup log prints the absolute
   path). Edit the existing file in place and keep its comments. Done when every line is blank, a
   comment, or `key = value` with a valid key.
4. **Apply** with `/reload confirm`, `/minecraft:reload`, or a restart. Done when the log shows
   `config reloaded from <path>: ...` (or `config <path>: ...` after a restart) whose summary equals
   the plan and no `[warn] config ...` line follows it. Summary format:
   `mode=<m>[, worlds=[...]], reach rules=<N>` - `worlds` appears only for include/exclude, and N is
   the number of distinct `reach.<origin>` keys with at least one target.
5. **Check behavior** (always after `include`). As an op standing in a restricted origin world run
   `/execute in <dimension key of another world> run say test` (`execute in` takes the dimension
   key, e.g. `minecraft:the_nether`, not the Bukkit name). Done when it fails without printing
   `test` for a world that is out of reach, and prints `test` for one that is allowed.

| The user wants | Write |
|---|---|
| Commands in `lobby` unrestricted, all else restricted | `mode = exclude` / `worlds = lobby` |
| Only `arena1` and `arena2` restricted | `mode = include` / `worlds = arena1, arena2` |
| Nether command blocks may also hit the overworld | `reach.world_nether = world` |
| Every world may reach `lobby` | `reach.* = lobby` |
| Restrictions off for every world | `reach.* = *`, or launch with `-Dworldscope.enabled=false` |
| Back to defaults | delete the file (a reload rewrites the default one) |

```properties
# lobby is free; the nether may also touch the overworld; everything else stays confined
mode = exclude
worlds = lobby
reach.world_nether = world
```

## Reload and logs

- Config is read at startup and on every server reload (`CraftServer#reload`,
  `MinecraftServer#reloadResources`; Bukkit's calls vanilla's, and WorldScope reads once when the
  outermost one ends). These two hook names are unconfirmed on a real Paper build: if the startup
  log lacks `patched org.bukkit.craftbukkit.CraftServer` or `patched net.minecraft.server.MinecraftServer`,
  or a reload prints no `config reloaded` line, restart instead.
- A file that can't be read during a reload (locked, bad encoding) logs
  `[error] could not reload the config - keeping the previous settings` and changes nothing. A
  deleted file goes back to the defaults.
- `-Dworldscope.config` pointing at a missing file warns and uses the defaults without creating
  anything. Only the default path gets a generated file.
- `-Dworldscope.verbose=true` adds per-class patch lines and stack traces.

## Symptoms

| Symptom | Cause |
|---|---|
| Edits have no effect | wrong file: compare the absolute path in the log with the one edited; or no reload yet |
| `unknown key '...'` | key typo; valid keys are `mode`, `worlds`, `reach.<origin>` |
| `mode must be all, include or exclude` | typo or a trailing `# comment` on the line |
| `mode = include but no worlds are listed` | empty `worlds`, so nothing is restricted |
| A world isn't restricted under `include` | its name isn't in the list (misspelled, or Bukkit name vs dimension key) |
| `reach` has no effect | the origin is spelled differently from how the server names it, or the origin is unrestricted |
