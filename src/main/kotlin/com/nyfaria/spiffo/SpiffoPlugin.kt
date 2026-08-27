package com.nyfaria.spiffo

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.JavaExec
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.register
import java.io.File

/**
 * SpiffoGradle: a Loom-style toolkit for Project Zomboid Java/Lua mods.
 *
 * Applying `com.nyfaria.spiffo` gives a consumer mod project:
 *  - Java + a JDK 25 toolchain (auto-downloaded) matching the game.
 *  - The engine jar (and ZombieBuddy.jar) as compileOnly deps, auto-located from Steam.
 *  - ZombieBuddy's own Gradle plugin applied for building/signing Java mods.
 *  - `decompileGame`  : decompile the engine to browsable/searchable sources.
 *  - `mirrorBaseLua`  : copy base-game Lua for reference/search.
 *  - `genRunConfigs`  : write a launcher + IntelliJ run config that starts the
 *                       game with the ZombieBuddy agent on an isolated profile.
 */
class SpiffoPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val ext = project.extensions.create("spiffo", SpiffoExtension::class.java)
        ext.modName.convention(project.name)
        ext.xmx.convention("3072m")
        ext.vineflowerVersion.convention("1.11.0")
        ext.zombieBuddy.convention(true)
        ext.zbWorkshopId.convention("3619862853")
        ext.debug.convention(true)
        ext.debugPort.convention(5005)
        ext.debugMenu.convention(true)
        ext.cacheDir.convention(File(project.gradle.gradleUserHomeDir, "spiffo-profiles/${project.name}").absolutePath)

        ext.workshopId.convention("")
        ext.workshopTitle.convention(ext.modName)
        ext.workshopDescription.convention("")
        ext.workshopVisibility.convention("private")
        ext.workshopChangeNote.convention("")
        ext.previewImage.convention(File(project.projectDir, "preview.png").absolutePath)
        ext.contentsDir.convention(File(project.projectDir, "Contents").absolutePath)
        ext.steamCmdPath.convention(project.providers.gradleProperty("spiffo.steamcmd").orElse(""))
        ext.steamUser.convention(project.providers.gradleProperty("spiffo.steamUser").orElse(""))

        ext.serverName.convention(project.name)
        ext.serverAdminPassword.convention("admin")
        ext.serverMods.convention(ext.modName.map { listOf(it) })
        ext.steam.convention(true)

        registerWorkshopTasks(project, ext)

        project.pluginManager.apply("java")
        project.extensions.getByType(JavaPluginExtension::class.java)
            .toolchain.languageVersion.set(JavaLanguageVersion.of(25))

        if (ext.zombieBuddy.get()) {
            runCatching { project.pluginManager.apply("io.github.zed-0xff.zb-gradle-plugin") }
                .onFailure { project.logger.warn("[spiffo] ZombieBuddy Gradle plugin not applied: ${it.message}") }
        }

        // Needed to fetch the decompiler (and any future tooling deps) without
        // the consumer mod having to declare repositories.
        project.repositories.mavenCentral()

        val vineflower = project.configurations.create("vineflower")

        project.afterEvaluate {
            project.dependencies.add(vineflower.name, "org.vineflower:vineflower:${ext.vineflowerVersion.get()}")

            val gameDir = ext.gameDir.orNull?.let(::File) ?: SteamLocator.findGameDir()
            if (gameDir == null || !File(gameDir, "projectzomboid.jar").isFile) {
                project.logger.warn("[spiffo] Project Zomboid install not found. Set spiffo.gameDir in build.gradle(.kts).")
                return@afterEvaluate
            }
            val gameJar = File(gameDir, "projectzomboid.jar")
            project.dependencies.add("compileOnly", project.files(gameJar))

            if (ext.zombieBuddy.get()) {
                SteamLocator.findZombieBuddyJar(ext.zbWorkshopId.get())?.let {
                    project.dependencies.add("compileOnly", project.files(it))
                } ?: project.logger.warn("[spiffo] ZombieBuddy.jar not found; subscribe to it on the Workshop.")
            }

            // -debug (PZ debug menu + mod debug logging) and JDWP are enabled only
            // when this run passes -Pspiffo.debug=true, so a normal run launches
            // the game clean. The generated "<mod> (debug)" run config sets it.
            val debugRun = project.findProperty("spiffo.debug")?.toString().equals("true", ignoreCase = true)

            val decompiledDir = project.layout.buildDirectory.dir("spiffo/pz-sources").get().asFile
            project.tasks.register<JavaExec>("decompileGame") {
                group = "spiffo"
                description = "Decompile projectzomboid.jar into browsable, searchable sources"
                classpath = vineflower
                mainClass.set("org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler")
                args("-dgs=1", gameJar.absolutePath, decompiledDir.absolutePath)
                inputs.file(gameJar)
                outputs.dir(decompiledDir)
                doFirst { decompiledDir.mkdirs() }
            }

            project.tasks.register<Copy>("mirrorBaseLua") {
                group = "spiffo"
                description = "Copy base-game Lua into build/ for reference and full-text search"
                from(File(gameDir, "media/lua"))
                into(project.layout.buildDirectory.dir("spiffo/base-lua"))
            }

            project.tasks.register("genRunConfigs") {
                group = "spiffo"
                description = "Generate the launcher script and IntelliJ run configs for this mod"
                doLast { writeRunConfigs(project, ext, gameDir) }
            }

            // One-click: rebuild the mod jar, then launch PZ with the ZombieBuddy
            // agent on the isolated profile. `dependsOn("jar")` guarantees the
            // freshly built jar is what the game loads.
            project.tasks.register<Exec>("runGame") {
                group = "spiffo"
                description = "Build the mod jar and launch Project Zomboid with it"
                dependsOn("jar")
                workingDir = gameDir
                if (ext.zombieBuddy.get()) environment("_JAVA_OPTIONS", "-agentlib:zbNative")
                val cmd = mutableListOf(
                    File(gameDir, "jre64/bin/java.exe").absolutePath,
                    "-Djava.awt.headless=true",
                    "--enable-native-access=ALL-UNNAMED",
                    "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
                    "-XX:+UseZGC",
                    "-Xmx${ext.xmx.get()}",
                    "-Djava.library.path=./win64/;./",
                )
                if (ext.steam.get()) cmd.add("-Dzomboid.steam=1")
                if (ext.debug.get() && debugRun) {
                    cmd.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:${ext.debugPort.get()}")
                }
                cmd.add("-cp"); cmd.add("./;projectzomboid.jar")
                cmd.add("zombie.gameStates.MainScreenState")
                cmd.add("-cachedir=${ext.cacheDir.get()}")
                if (ext.debugMenu.get() && debugRun) cmd.add("-debug")
                commandLine(cmd)
            }

            // Launch a dedicated server on the same isolated profile with this mod
            // enabled, for local multiplayer testing. Start this, then connect a
            // client (runGame) to 127.0.0.1. JDWP (if enabled) uses debugPort+1 so
            // it doesn't clash with the client's debug port.
            project.tasks.register<Exec>("runServer") {
                group = "spiffo"
                description = "Launch a Project Zomboid dedicated server on the isolated profile with this mod enabled"
                dependsOn("jar")
                workingDir = gameDir
                standardInput = System.`in`
                if (ext.zombieBuddy.get()) environment("_JAVA_OPTIONS", "-agentlib:zbNative")
                doFirst { ensureServerModEnabled(project, ext) }
                val cmd = mutableListOf(
                    File(gameDir, "jre64/bin/java.exe").absolutePath,
                    "--enable-native-access=ALL-UNNAMED",
                    "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
                    "-XX:+UseZGC",
                    "-XX:-CreateCoredumpOnCrash",
                    "-XX:-OmitStackTraceInFastThrow",
                    "-Xmx${ext.xmx.get()}",
                    "-Djava.library.path=./natives/;./natives/win64/;./",
                )
                if (ext.steam.get()) cmd.add("-Dzomboid.steam=1")
                if (ext.debug.get() && debugRun) {
                    cmd.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:${ext.debugPort.get() + 1}")
                }
                cmd.add("-cp"); cmd.add("./;projectzomboid.jar")
                cmd.add("zombie.network.GameServer")
                cmd.add("-cachedir=${ext.cacheDir.get()}")
                cmd.add("-servername"); cmd.add(ext.serverName.get())
                cmd.add("-adminpassword"); cmd.add(ext.serverAdminPassword.get())
                commandLine(cmd)
            }
        }
    }

    // Make sure the dedicated server's ini enables this mod. Merges into an
    // existing Mods= line, or writes a minimal ini if the server hasn't been run
    // yet (PZ fills the remaining defaults on first launch).
    private fun ensureServerModEnabled(project: Project, ext: SpiffoExtension) {
        val mods = ext.serverMods.get().filter { it.isNotBlank() }
        if (mods.isEmpty()) return
        val serverDir = File(ext.cacheDir.get(), "Server")
        val ini = File(serverDir, "${ext.serverName.get()}.ini")
        if (ini.isFile) {
            val lines = ini.readLines().toMutableList()
            val idx = lines.indexOfFirst { it.startsWith("Mods=") }
            if (idx >= 0) {
                val existing = lines[idx].removePrefix("Mods=").split(";").map { it.trim() }.filter { it.isNotEmpty() }
                lines[idx] = "Mods=" + (existing + mods).distinct().joinToString(";")
            } else {
                lines.add("Mods=" + mods.joinToString(";"))
            }
            ini.writeText(lines.joinToString(System.lineSeparator()))
            project.logger.lifecycle("[spiffo] ensured server mods in $ini -> ${mods.joinToString(";")}")
        } else {
            serverDir.mkdirs()
            ini.writeText("Mods=" + mods.joinToString(";") + System.lineSeparator())
            project.logger.lifecycle("[spiffo] created $ini with Mods=${mods.joinToString(";")} (PZ fills the rest on first launch)")
        }
    }

    private fun writeRunConfigs(project: Project, ext: SpiffoExtension, gameDir: File) {
        val name = ext.modName.get()
        val cache = ext.cacheDir.get()
        val xmx = ext.xmx.get()
        val dbg = if (ext.debug.get())
            "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:${ext.debugPort.get()} "
        else ""
        val menu = if (ext.debugMenu.get()) " -debug" else ""

        val bat = File(gameDir, "run-${project.name}.bat")
        val batText = """
            @echo off
            title $name (SpiffoGradle)
            cd /d "%~dp0"
            set _JAVA_OPTIONS=-agentlib:zbNative
            set PZ_CLASSPATH=./;projectzomboid.jar
            ".\jre64\bin\java.exe" -Djava.awt.headless=true --enable-native-access=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED -Dzomboid.steam=1 -XX:+UseZGC -Xmx$xmx -Djava.library.path=./win64/;./ ${dbg}-cp ./;projectzomboid.jar zombie.gameStates.MainScreenState -cachedir=$cache$menu
            echo.
            echo === $name exited (code %errorlevel%) ===
            pause
        """.trimIndent().replace("\n", "\r\n")
        bat.writeText(batText)

        val runDir = File(project.projectDir, ".run").apply { mkdirs() }
        val gd = gameDir.absolutePath.replace("\\", "/")
        val bp = bat.absolutePath.replace("\\", "/")
        File(runDir, "$name.run.xml").writeText(
            """
            <component name="ProjectRunConfigurationManager">
              <configuration default="false" name="$name (SpiffoGradle)" type="ShConfigurationType">
                <option name="SCRIPT_PATH" value="$bp" />
                <option name="INDEPENDENT_SCRIPT_PATH" value="true" />
                <option name="SCRIPT_WORKING_DIRECTORY" value="$gd" />
                <option name="INDEPENDENT_SCRIPT_WORKING_DIRECTORY" value="true" />
                <option name="INTERPRETER_PATH" value="" />
                <option name="EXECUTE_IN_TERMINAL" value="true" />
                <option name="EXECUTE_SCRIPT_FILE" value="true" />
                <envs />
                <method v="2" />
              </configuration>
            </component>
            """.trimIndent()
        )
        // Gradle run config that builds the jar then launches PZ (one click).
        File(runDir, "$name-build-and-run.run.xml").writeText(
            """
            <component name="ProjectRunConfigurationManager">
              <configuration default="false" name="$name build &amp; run" type="GradleRunConfiguration" factoryName="Gradle">
                <ExternalSystemSettings>
                  <option name="executionName" />
                  <option name="externalProjectPath" value="${'$'}PROJECT_DIR${'$'}" />
                  <option name="externalSystemIdString" value="GRADLE" />
                  <option name="scriptParameters" value="" />
                  <option name="taskDescriptions">
                    <list />
                  </option>
                  <option name="taskNames">
                    <list>
                      <option value="runGame" />
                    </list>
                  </option>
                  <option name="vmOptions" />
                </ExternalSystemSettings>
                <GradleScriptDebugEnabled>false</GradleScriptDebugEnabled>
                <method v="2" />
              </configuration>
            </component>
            """.trimIndent()
        )
        // Same as build & run, but passes -Pspiffo.debug=true so the game launches
        // with the PZ debug menu, mod debug logging, and the JDWP port open.
        File(runDir, "$name-build-and-run-debug.run.xml").writeText(
            """
            <component name="ProjectRunConfigurationManager">
              <configuration default="false" name="$name build &amp; run (debug)" type="GradleRunConfiguration" factoryName="Gradle">
                <ExternalSystemSettings>
                  <option name="executionName" />
                  <option name="externalProjectPath" value="${'$'}PROJECT_DIR${'$'}" />
                  <option name="externalSystemIdString" value="GRADLE" />
                  <option name="scriptParameters" value="-Pspiffo.debug=true" />
                  <option name="taskDescriptions">
                    <list />
                  </option>
                  <option name="taskNames">
                    <list>
                      <option value="runGame" />
                    </list>
                  </option>
                  <option name="vmOptions" />
                </ExternalSystemSettings>
                <GradleScriptDebugEnabled>false</GradleScriptDebugEnabled>
                <method v="2" />
              </configuration>
            </component>
            """.trimIndent()
        )
        // Gradle run config that launches a dedicated server with this mod.
        File(runDir, "$name-server.run.xml").writeText(
            """
            <component name="ProjectRunConfigurationManager">
              <configuration default="false" name="$name server" type="GradleRunConfiguration" factoryName="Gradle">
                <ExternalSystemSettings>
                  <option name="executionName" />
                  <option name="externalProjectPath" value="${'$'}PROJECT_DIR${'$'}" />
                  <option name="externalSystemIdString" value="GRADLE" />
                  <option name="scriptParameters" value="" />
                  <option name="taskDescriptions">
                    <list />
                  </option>
                  <option name="taskNames">
                    <list>
                      <option value="runServer" />
                    </list>
                  </option>
                  <option name="vmOptions" />
                </ExternalSystemSettings>
                <GradleScriptDebugEnabled>false</GradleScriptDebugEnabled>
                <method v="2" />
              </configuration>
            </component>
            """.trimIndent()
        )

        // Remote JVM Debug config to attach to the running game (JDWP).
        if (ext.debug.get()) {
            File(runDir, "$name-debug.run.xml").writeText(
                """
                <component name="ProjectRunConfigurationManager">
                  <configuration default="false" name="$name debug (attach)" type="Remote">
                    <option name="USE_SOCKET_TRANSPORT" value="true" />
                    <option name="SERVER_MODE" value="false" />
                    <option name="SHMEM_ADDRESS" />
                    <option name="HOST" value="localhost" />
                    <option name="PORT" value="${ext.debugPort.get()}" />
                    <option name="AUTO_RESTART" value="false" />
                    <method v="2" />
                  </configuration>
                </component>
                """.trimIndent()
            )
        }
        project.logger.lifecycle("[spiffo] wrote launcher $bat + run configs '$name', '$name build & run', '$name debug (attach)'")
    }

    // Steam has no headless upload in PZ itself, so publishing is done with
    // SteamCMD (`+workshop_build_item <vdf>`), independent of the game. We only
    // generate the VDF and invoke SteamCMD; the item content is the project's
    // Contents dir (its subfolders become the uploaded item root).
    private fun registerWorkshopTasks(project: Project, ext: SpiffoExtension) {
        val vdfFile = project.layout.buildDirectory.file("spiffo/workshop.vdf").get().asFile

        project.tasks.register("genWorkshopVdf") {
            group = "spiffo"
            description = "Generate the SteamCMD workshop_build_item VDF from the spiffo { } config"
            doLast {
                val contents = File(ext.contentsDir.get())
                if (!File(contents, "mods").isDirectory) {
                    throw GradleException("[spiffo] no Contents/mods found at $contents")
                }
                val preview = File(ext.previewImage.get())
                if (!preview.isFile) {
                    project.logger.warn("[spiffo] preview image not found at $preview - Steam requires a preview; the upload will likely fail")
                } else if (preview.length() > 1_000_000L) {
                    project.logger.warn("[spiffo] preview image is larger than 1000 KB; Steam may reject it")
                }
                vdfFile.parentFile.mkdirs()
                vdfFile.writeText(buildWorkshopVdf(ext, contents, preview))
                val idNote = ext.workshopId.get().ifBlank { "0 (creates a new item)" }
                project.logger.lifecycle("[spiffo] wrote $vdfFile (publishedfileid=$idNote)")
            }
        }

        project.tasks.register("installSteamCmd") {
            group = "spiffo"
            description = "Download SteamCMD into the Gradle user home (skipped if already present or spiffo.steamcmd is set)"
            doLast { project.logger.lifecycle("[spiffo] SteamCMD at ${resolveSteamCmd(project, ext)}") }
        }

        project.tasks.register<Exec>("publishWorkshop") {
            group = "spiffo"
            description = "Upload the mod to the Steam Workshop via SteamCMD (auto-downloads SteamCMD if needed)"
            dependsOn("genWorkshopVdf")
            standardInput = System.`in`
            doFirst {
                val user = ext.steamUser.get()
                if (user.isBlank()) throw GradleException("[spiffo] set 'spiffo.steamUser' in gradle.properties (Steam account name)")
                if (!vdfFile.isFile) throw GradleException("[spiffo] $vdfFile missing; genWorkshopVdf should have created it")
                val cmd = resolveSteamCmd(project, ext)
                commandLine(cmd.absolutePath, "+login", user, "+workshop_build_item", vdfFile.absolutePath, "+quit")
                project.logger.lifecycle("[spiffo] publishing to Steam Workshop as '$user' via SteamCMD ...")
                project.logger.lifecycle("[spiffo] first time only: if this fails with 'Invalid Password', the IDE/Gradle")
                project.logger.lifecycle("[spiffo] console can't accept the password prompt. Run this ONCE in a real")
                project.logger.lifecycle("[spiffo] terminal to cache your Steam session, then retry publishWorkshop:")
                project.logger.lifecycle("[spiffo]   \"${cmd.absolutePath}\" +login $user +quit")
            }
        }
    }

    // Returns the steamcmd executable: the explicit spiffo.steamcmd path if set,
    // otherwise downloads SteamCMD from Valve's CDN into the Gradle user home
    // (once, shared across all mods) and returns that. SteamCMD self-updates the
    // rest of its files on first run.
    private fun resolveSteamCmd(project: Project, ext: SpiffoExtension): File {
        val explicit = ext.steamCmdPath.get()
        if (explicit.isNotBlank()) {
            val f = File(explicit)
            if (!f.isFile) throw GradleException("[spiffo] steamcmd not found at $f (spiffo.steamcmd)")
            return f
        }
        val os = System.getProperty("os.name").lowercase()
        val dir = File(project.gradle.gradleUserHomeDir, "spiffo/steamcmd")
        val (url, exe) = when {
            os.contains("win") ->
                "https://steamcdn-a.akamaihd.net/client/installer/steamcmd.zip" to File(dir, "steamcmd.exe")
            os.contains("mac") || os.contains("darwin") ->
                "https://steamcdn-a.akamaihd.net/client/installer/steamcmd_osx.tar.gz" to File(dir, "steamcmd.sh")
            else ->
                "https://steamcdn-a.akamaihd.net/client/installer/steamcmd_linux.tar.gz" to File(dir, "steamcmd.sh")
        }
        if (exe.isFile) return exe

        dir.mkdirs()
        val archive = File(dir, url.substringAfterLast('/'))
        project.logger.lifecycle("[spiffo] downloading SteamCMD from $url")
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "SpiffoGradle")
        conn.inputStream.use { input -> archive.outputStream().use { input.copyTo(it) } }

        if (url.endsWith(".zip")) {
            java.util.zip.ZipInputStream(archive.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val out = File(dir, entry.name)
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile.mkdirs()
                        out.outputStream().use { zis.copyTo(it) }
                    }
                    entry = zis.nextEntry
                }
            }
        } else {
            project.exec { commandLine("tar", "-xzf", archive.absolutePath, "-C", dir.absolutePath) }
            exe.setExecutable(true)
        }

        if (!exe.isFile) throw GradleException("[spiffo] SteamCMD extraction failed; expected $exe")
        project.logger.lifecycle("[spiffo] SteamCMD installed at $exe")
        return exe
    }

    private fun buildWorkshopVdf(ext: SpiffoExtension, contents: File, preview: File): String {
        // SteamCMD's VDF parser does NOT interpret \n escapes, so newlines must be
        // literal line breaks inside the quoted value. It also has no way to escape
        // a double-quote, so swap it for a typographic one (same as PZ's uploader).
        fun esc(s: String) = s.replace("\r\n", "\n").replace("\"", "”")
        fun fwd(f: File) = f.absolutePath.replace("\\", "/")
        val id = ext.workshopId.get().ifBlank { "0" }
        val vis = when (ext.workshopVisibility.get()) {
            "public" -> 0
            "friendsOnly" -> 1
            "private" -> 2
            "unlisted" -> 3
            else -> 2
        }
        return buildString {
            appendLine("\"workshopitem\"")
            appendLine("{")
            appendLine("\t\"appid\"\t\t\"108600\"")
            appendLine("\t\"publishedfileid\"\t\"$id\"")
            appendLine("\t\"contentfolder\"\t\"${fwd(contents)}\"")
            if (preview.isFile) appendLine("\t\"previewfile\"\t\"${fwd(preview)}\"")
            appendLine("\t\"visibility\"\t\"$vis\"")
            appendLine("\t\"title\"\t\"${esc(ext.workshopTitle.get())}\"")
            appendLine("\t\"description\"\t\"${esc(ext.workshopDescription.get())}\"")
            appendLine("\t\"changenote\"\t\"${esc(ext.workshopChangeNote.get())}\"")
            appendLine("}")
        }
    }
}
