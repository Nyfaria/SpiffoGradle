package com.nyfaria.spiffo

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * Configuration for the `spiffo { }` block in a consumer mod build.
 * Everything has a sensible default; most mods only set [modName].
 */
abstract class SpiffoExtension {
    /** Override the Project Zomboid install dir. If unset, it's auto-detected from Steam. */
    abstract val gameDir: Property<String>

    /** Isolated Zomboid profile dir used by the generated run config (own saves/config/logs). */
    abstract val cacheDir: Property<String>

    /** Display name used for the launcher/run config. Defaults to the Gradle project name. */
    abstract val modName: Property<String>

    /** Max heap for the game process in run configs (e.g. "3072m"). */
    abstract val xmx: Property<String>

    /** Vineflower version used to decompile the engine jar. */
    abstract val vineflowerVersion: Property<String>

    /** Auto-apply ZombieBuddy's Gradle plugin (build + signing) and add its jar to the classpath. */
    abstract val zombieBuddy: Property<Boolean>

    /** Steam Workshop id of the ZombieBuddy item (to locate ZombieBuddy.jar). */
    abstract val zbWorkshopId: Property<String>

    /** Open a JDWP debug port in run configs so you can attach a debugger. */
    abstract val debug: Property<Boolean>

    /** Port for remote debugging. */
    abstract val debugPort: Property<Int>

    /** Pass -debug so the in-game debug menu is available in run configs. */
    abstract val debugMenu: Property<Boolean>

    // --- Steam Workshop publishing (via SteamCMD) ---

    /** Existing Workshop item id (publishedfileid). Leave empty to create a new item on first publish. */
    abstract val workshopId: Property<String>

    /** Workshop item title. Defaults to modName. */
    abstract val workshopTitle: Property<String>

    /** Workshop item description (supports multiple lines). */
    abstract val workshopDescription: Property<String>

    /** Workshop visibility: public | friendsOnly | private | unlisted. Defaults to private. */
    abstract val workshopVisibility: Property<String>

    /** Change note recorded for this update. */
    abstract val workshopChangeNote: Property<String>

    /** Square 256x256 or 512x512 preview.png (< 1000 KB). Defaults to <projectDir>/preview.png. */
    abstract val previewImage: Property<String>

    /** Contents dir uploaded as the item; its subfolders (mods/, ...) become the item root. Defaults to <projectDir>/Contents. */
    abstract val contentsDir: Property<String>

    /** Path to the steamcmd executable (via `spiffo.steamcmd` in gradle.properties). Optional - if unset, SteamCMD is auto-downloaded into the Gradle user home. */
    abstract val steamCmdPath: Property<String>

    /** Steam account name used for the upload. Usually set via `spiffo.steamUser` in gradle.properties. */
    abstract val steamUser: Property<String>

    // --- Dedicated server (local MP testing) ---

    /** Server name; picks the <name>.ini under <cacheDir>/Server/. Defaults to the project name. */
    abstract val serverName: Property<String>

    /** Admin password passed on launch so the dedicated server's first run isn't interactive. */
    abstract val serverAdminPassword: Property<String>

    /** Mod ids to enable in the dedicated server ini. Defaults to [modName]. Add dependencies (e.g. ZombieBuddy) as needed. */
    abstract val serverMods: ListProperty<String>

    /**
     * Run the client and dedicated server with Steam networking (-Dzomboid.steam=1).
     * The two must match to connect. Defaults to true. For local server+client
     * testing on one machine, set false so both run non-Steam and can direct-connect
     * to 127.0.0.1.
     */
    abstract val steam: Property<Boolean>
}
