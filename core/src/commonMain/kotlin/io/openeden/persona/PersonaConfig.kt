package io.openeden.persona

data class PersonaConfig(
    val mode: PersonaMode,
    val startSubState: PersonaSubState,
    val promptSections: Map<String, String>,
) {
    init {
        require(mode != PersonaMode.LEGACY || startSubState == PersonaSubState.AWAKENED) {
            "Legacy persona mode only supports the awakened starting point"
        }
    }
}
