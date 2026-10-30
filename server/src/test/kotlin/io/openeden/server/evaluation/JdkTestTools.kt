package io.openeden.server.evaluation

import java.nio.file.Path

internal object JdkTestTools {
    fun executable(name: String): Path {
        val suffix = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) ".exe" else ""
        return Path.of(System.getProperty("java.home"), "bin", "$name$suffix")
    }

    val runtimeClasspath: String
        get() = System.getProperty("openeden.test.runtimeClasspath") ?: System.getProperty("java.class.path")
}
