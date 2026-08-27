# SpiffoGradle

A Loom-style Gradle toolkit for **Project Zomboid** Java/Lua mods. It turns the
"copy jars around by hand, guess the Java version, hand-write launch bats"
workflow into a proper modding environment.

Applying the plugin gives your mod project:

- **Java + JDK 25 toolchain** (auto-downloaded) matching the game's runtime.
- The **engine jar** (`projectzomboid.jar`) and **ZombieBuddy.jar** wired as
  `compileOnly` dependencies, auto-located from your Steam install.
- **ZombieBuddy's Gradle plugin applied for you** (build + ZBS signing), so
  Workshop-distributable Java mods just work.
- `decompileGame` — decompile the engine (via Vineflower) into browsable,
  full-text-searchable sources for go-to-definition.
- `mirrorBaseLua` — copy the base-game Lua into `build/` for reference/search.
- `genRunConfigs` — generate a launcher script **and an IntelliJ run config**
  that starts the game with the ZombieBuddy agent on an isolated profile, so a
  test instance runs alongside your normal game without clobbering saves/logs.

## Requirements

- Project Zomboid installed via Steam (Build 42+).
- [ZombieBuddy](https://pzwiki.net/wiki/ZombieBuddy) subscribed on the Workshop
  and installed (needed to load Java mods, and for its jar/agent).

## Usage

In your mod's `build.gradle.kts`:

```kotlin
plugins {
    id("com.nyfaria.spiffo") version "0.1.0"
}

spiffo {
    modName.set("Tezlor")
    // gameDir.set("A:/SteamLibrary/steamapps/common/ProjectZomboid") // only if auto-detect fails
    // xmx.set("3072m")
    // zombieBuddy.set(true)
}
```

Then:

```
./gradlew decompileGame   # engine sources -> build/spiffo/pz-sources  (mark as Sources root)
./gradlew mirrorBaseLua   # base Lua       -> build/spiffo/base-lua
./gradlew genRunConfigs   # launcher + IntelliJ run config "<modName> (SpiffoGradle)"
```

`compileOnly` already sees the engine + ZombieBuddy, so your `@Patch` classes
and Lua-exposed globals compile directly against the real game classes.

## Running the game / a local server

```
./gradlew runGame     # build the jar + launch the client on the isolated profile
./gradlew runServer   # launch a dedicated server on the same profile, mod enabled
```

`runServer` launches `zombie.network.GameServer` against `cacheDir`, so it shares
the isolated profile (and mod junctions) with `runGame`. It ensures the server's
`<serverName>.ini` lists your mod (`serverMods`, default `[modName]`) before
starting. For local multiplayer testing: run `runServer`, then `runGame`, and
connect the client to `127.0.0.1`.

```kotlin
spiffo {
    // serverName.set("MyMod")            // <name>.ini under <cacheDir>/Server/
    // serverAdminPassword.set("admin")   // avoids the interactive first-run prompt
    // serverMods.set(listOf("MyMod", "ZombieBuddy"))  // add dependencies as needed
}
```

Notes:
- If the server has never run, a minimal ini is written and PZ fills the rest on
  first launch; if a mod doesn't take, run once then `runServer` again (it merges
  into the existing `Mods=`).
- Workshop-only dependencies still need `WorkshopItems=` set in the ini; local
  mods just need to be in `<cacheDir>/mods` (a junction, like the client uses).
- With `debug` on, the server opens JDWP on `debugPort + 1` (client uses
  `debugPort`), so you can attach to both at once.

## Publishing to the Steam Workshop

PZ has no headless upload (its in-game uploader talks to the running Steam
client), so SpiffoGradle publishes with **SteamCMD** instead. Configure it in the
`spiffo { }` block plus two `gradle.properties` keys:

```kotlin
spiffo {
    modName.set("MyMod")
    workshopTitle.set("My Mod")
    workshopDescription.set("What it does.\n\nMore detail.")
    workshopVisibility.set("private") // public | friendsOnly | private | unlisted
    workshopChangeNote.set("Initial release")
    // workshopId.set("1234567890")   // set after the first publish
    // previewImage / contentsDir default to <projectDir>/preview.png and Contents/
}
```

```properties
# gradle.properties (keep out of git; ~/.gradle/gradle.properties works too)
spiffo.steamUser=YourSteamAccountName
# spiffo.steamcmd=C:/steamcmd/steamcmd.exe   # optional; auto-downloaded if unset
```

Tasks:

```
./gradlew installSteamCmd   # download SteamCMD (auto-run by publishWorkshop)
./gradlew genWorkshopVdf    # write build/spiffo/workshop.vdf (no upload)
./gradlew publishWorkshop   # ensure SteamCMD + genWorkshopVdf + workshop_build_item
```

- **SteamCMD is downloaded automatically** into the Gradle user home on first use
  (Valve's CDN, per-OS). Set `spiffo.steamcmd` only to reuse an existing install.
- **One-time login must be done in a real terminal**, not the IDE/Gradle console
  (which can't accept the interactive password prompt - you'll get "Invalid
  Password"). Run `installSteamCmd`, then in PowerShell/cmd/bash:
  `"<gradleUserHome>/spiffo/steamcmd/steamcmd.exe" +login <user> +quit` and enter
  your password + Steam Guard. After that SteamCMD caches the session and
  `publishWorkshop` runs non-interactively from anywhere.
- A `preview.png` (square 256 or 512, < 1000 KB) is required by Steam.
- On first publish, `workshopId` is empty so a **new** item is created; copy the
  printed id into `workshopId` for subsequent updates.
- SteamCMD can't set Workshop tags; set those on the item page once.

## Local development (Maven Local)

Until it's published to the Gradle Plugin Portal, install it locally:

```
# in SpiffoGradle/
./gradlew publishToMavenLocal
```

That publishes both the plugin and its marker to `~/.m2`
(`com.nyfaria:spiffo-gradle:0.1.0` and the `com.nyfaria.spiffo` plugin marker).

Then in the consuming mod's `settings.gradle.kts`, let Gradle resolve plugins
from Maven Local:

```kotlin
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
}
```

and apply it as shown in [Usage](#usage).

## Notes

- **Decompilation is local-only.** Sources are produced from *your* installed
  jar on *your* machine and are never redistributed — same model as Minecraft
  toolchains. Don't commit `build/spiffo/pz-sources`.
- The generated IntelliJ run config uses the bundled **Shell Script** plugin.
- Re-run `decompileGame` after a game update to refresh the sources.

## Status

Early (0.1.0). MVP scaffold — expect rough edges while the task wiring settles.
