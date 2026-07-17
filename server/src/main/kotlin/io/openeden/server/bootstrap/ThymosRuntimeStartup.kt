package io.openeden.server.bootstrap

import io.openeden.relationship.DjlTextAffectAnalyzer

internal inline fun <T> withPreparedThymosRuntime(
    prepare: () -> Unit = { DjlTextAffectAnalyzer.prepareRuntime() },
    loadModels: () -> T,
): T {
    prepare()
    return loadModels()
}
