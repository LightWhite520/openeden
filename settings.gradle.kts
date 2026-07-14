pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("ktorLibs").from("io.ktor:ktor-version-catalog:3.5.0")
    }
}

sourceControl {
    gitRepository(uri("https://github.com/LightWhite520/thymos.git")) {
        producesModule("io.openeden:thymos")
    }
}

rootProject.name = "openeden"

include(":client")
include(":core")
include(":server")
include(":trainer")
