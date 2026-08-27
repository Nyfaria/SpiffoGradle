plugins {
    `java-gradle-plugin`
    `kotlin-dsl`
    `maven-publish`
    id("com.gradle.plugin-publish") version "1.3.1"
}

group = "com.nyfaria"
version = "0.1.0"

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    // ZombieBuddy's own Gradle plugin (build + ZBS signing). We depend on its
    // plugin marker so SpiffoGradle can auto-apply it in consumer projects.
    implementation("io.github.zed-0xff.zb-gradle-plugin:io.github.zed-0xff.zb-gradle-plugin.gradle.plugin:1.0.3")
}

gradlePlugin {
    website = "https://github.com/Nyfaria/SpiffoGradle"
    vcsUrl = "https://github.com/Nyfaria/SpiffoGradle.git"
    plugins {
        create("spiffo") {
            id = "com.nyfaria.spiffo"
            implementationClass = "com.nyfaria.spiffo.SpiffoPlugin"
            displayName = "SpiffoGradle - Project Zomboid modding toolkit"
            description = "Bootstraps a Java/Lua Project Zomboid modding environment: locates the game, " +
                "exposes the engine jar and decompiled sources for navigation/search, mirrors base Lua, " +
                "wraps ZombieBuddy, and generates run configs that launch the game with your mod."
            tags = listOf("project-zomboid", "zomboid", "modding", "gamedev", "lua", "zombiebuddy")
        }
    }
}
