package io.openeden.terminal

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

data class TerminalEncodingProfile(
    val stdin: Charset,
    val stdout: Charset,
    val stderr: Charset,
) {
    companion object {
        fun utf8(): TerminalEncodingProfile = TerminalEncodingProfile(
            stdin = StandardCharsets.UTF_8,
            stdout = StandardCharsets.UTF_8,
            stderr = StandardCharsets.UTF_8,
        )

        fun fromEnvironment(environment: Map<String, String>): TerminalEncodingProfile =
            TerminalEncodingProfile(
                stdin = parse(environment, "OPENEDEN_STDIN_ENCODING"),
                stdout = parse(environment, "OPENEDEN_STDOUT_ENCODING"),
                stderr = parse(environment, "OPENEDEN_STDERR_ENCODING"),
            )

        private fun parse(environment: Map<String, String>, variable: String): Charset {
            val configured = environment[variable]?.trim().orEmpty()
            if (configured.isEmpty()) return StandardCharsets.UTF_8

            return try {
                Charset.forName(configured)
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException(
                    "Invalid charset configured by $variable: '$configured'",
                    error,
                )
            }
        }
    }
}
