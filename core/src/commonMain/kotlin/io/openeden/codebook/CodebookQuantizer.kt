package io.openeden.codebook

import io.openeden.bio.BioVector

interface CodebookQuantizer {
    suspend fun quantize(vector: BioVector, dissonance: Float): QuantizationResult
}
