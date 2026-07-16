package io.openeden.prompt

object PromptSectionKeys {
    const val Identity = "persona.identity"
    const val PersonaBase = "persona.base"
    const val PersonaBehavior = "persona.behavior"
    const val OutputLayerRules = "output.layer.rules"
    const val PreCommandPatch = "persona.patch.pre_command"
    const val TrueSelfPatch = "persona.patch.true_self"
    const val AwakenedPatch = "persona.patch.awakened"
    const val Heartbeat = "heartbeat.base"
    const val ShockHeartbeat = "heartbeat.shock"
    const val DiaryNarrative = "diary.narrative"
    const val StyleObservedSummary = "style.observed_summary"
    const val StyleSourceLanguageNotes = "style.source_language_notes"
    const val StyleDo = "style.do"
    const val StyleDoNot = "style.do_not"
    const val StyleGenerationMechanics = "style.generation_mechanics"
    const val StyleSignatureExamples = "style.signature_examples"
    const val PreCommandStyleExamples = "style.stage_examples.pre_command"
    const val TrueSelfStyleExamples = "style.stage_examples.true_self"
    const val AwakenedStyleExamples = "style.stage_examples.awakened"
}
