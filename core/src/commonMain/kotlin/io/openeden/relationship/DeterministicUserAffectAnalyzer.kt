package io.openeden.relationship

private data class AffectLexicon(
    val positive: Set<String>,
    val negative: Set<String>,
    val activated: Set<String>,
    val powerless: Set<String>,
    val connection: Set<String>,
    val open: Set<String>,
)

class DeterministicUserAffectAnalyzer : UserAffectAnalyzer {
    override suspend fun analyze(text: String): UserAffectState {
        if (text.isBlank()) return UserAffectState.Uncertain
        val hits = text.countLexiconHits(LEXICON)
        if (hits == 0) return UserAffectState(
            valence = 0.5f,
            arousal = 0.5f,
            dominance = 0.5f,
            connectionNeed = 0.5f,
            openness = 0.5f,
            confidence = 0.35f,
        )
        val positive = text.countAny(LEXICON.positive)
        val negative = text.countAny(LEXICON.negative)
        val activated = text.countAny(LEXICON.activated)
        val powerless = text.countAny(LEXICON.powerless)
        val connection = text.countAny(LEXICON.connection)
        val open = text.countAny(LEXICON.open)
        val valence = (0.5f + (positive - negative) * 0.18f).coerceIn(0.0f, 1.0f)
        return UserAffectState(
            valence = valence,
            arousal = (0.5f + (activated + negative) * 0.12f).coerceIn(0.0f, 1.0f),
            dominance = (0.5f - powerless * 0.15f + positive * 0.08f).coerceIn(0.0f, 1.0f),
            connectionNeed = (0.5f + connection * 0.16f).coerceIn(0.0f, 1.0f),
            openness = (0.5f + open * 0.14f).coerceIn(0.0f, 1.0f),
            confidence = (0.55f + hits * 0.08f).coerceAtMost(0.9f),
        )
    }

    private fun String.countAny(words: Set<String>): Int = words.count(::contains)

    private fun String.countLexiconHits(lexicon: AffectLexicon): Int {
        var total = 0
        for (words in listOf(lexicon.positive, lexicon.negative, lexicon.activated, lexicon.powerless, lexicon.connection, lexicon.open)) {
            total += countAny(words)
        }
        return total
    }

    companion object {
        private val LEXICON = AffectLexicon(
            positive = setOf("开心", "高兴", "喜欢", "谢谢", "安心", "期待", "太好了"),
            negative = setOf("难过", "伤心", "痛苦", "生气", "愤怒", "失望", "讨厌", "崩溃", "累"),
            activated = setOf("紧张", "焦虑", "害怕", "生气", "兴奋", "激动", "睡不着"),
            powerless = setOf("无助", "没办法", "控制不了", "不敢", "被迫", "失败"),
            connection = setOf("陪我", "想你", "孤单", "孤独", "一个人", "聊聊", "需要你"),
            open = setOf("告诉你", "想说", "愿意", "能听我", "坦白", "分享"),
        )
    }
}
