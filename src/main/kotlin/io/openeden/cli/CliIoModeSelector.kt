package io.openeden.cli

internal object CliIoModeSelector {
    fun <T> select(
        arguments: List<String>,
        stdinInteractive: Boolean,
        stdoutInteractive: Boolean,
        interactiveFactory: () -> T,
        plainFactory: (terminalFailure: Throwable?) -> T,
    ): T {
        if (arguments.isNotEmpty() || !stdinInteractive || !stdoutInteractive) {
            return plainFactory(null)
        }

        return try {
            interactiveFactory()
        } catch (error: Exception) {
            plainFactory(error)
        } catch (error: UnsatisfiedLinkError) {
            plainFactory(error)
        }
    }
}
