package io.openeden.codebook

import io.openeden.bio.BioVector

interface CodebookModelRunner {
    suspend fun predict(vector: BioVector, dissonance: Float): CodebookModelResult
}
