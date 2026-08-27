package com.nyfaria.spiffo

import java.io.File

/**
 * Finds the Project Zomboid install and ZombieBuddy jar by walking Steam's
 * library folders across Windows/Linux/macOS. Consumers can bypass all of this
 * by setting `spiffo.gameDir`.
 */
object SteamLocator {
    private const val APP_ID = "108600"

    fun findGameDir(): File? {
        for (lib in libraries()) {
            val dir = File(lib, "steamapps/common/ProjectZomboid")
            if (File(dir, "projectzomboid.jar").isFile) return dir
        }
        return null
    }

    fun findZombieBuddyJar(workshopId: String): File? {
        for (lib in libraries()) {
            val f = File(lib, "steamapps/workshop/content/$APP_ID/$workshopId/mods/ZombieBuddy/libs/ZombieBuddy.jar")
            if (f.isFile) return f
        }
        return null
    }

    private fun steamRoots(): List<File> {
        val home = System.getProperty("user.home")
        return listOf(
            File("C:/Program Files (x86)/Steam"),
            File("C:/Program Files/Steam"),
            File("$home/.steam/steam"),
            File("$home/.local/share/Steam"),
            File("$home/Library/Application Support/Steam"),
        ).filter { it.isDirectory }
    }

    /** Every Steam library folder (each root plus the paths listed in libraryfolders.vdf). */
    private fun libraries(): List<File> {
        val libs = LinkedHashSet<File>()
        for (root in steamRoots()) {
            libs += root
            val vdf = File(root, "steamapps/libraryfolders.vdf")
            if (vdf.isFile) {
                val rx = Regex("\"path\"\\s*\"([^\"]+)\"")
                for (m in rx.findAll(vdf.readText())) {
                    libs += File(m.groupValues[1].replace("\\\\", "/"))
                }
            }
        }
        return libs.filter { it.isDirectory }
    }
}
