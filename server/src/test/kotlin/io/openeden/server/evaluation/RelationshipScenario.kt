package io.openeden.server.evaluation

data class ScenarioTurn(
    val advanceMs: Long,
    val userText: String,
    val tags: Set<String>,
)

data class RelationshipScenario(
    val name: String,
    val turns: List<ScenarioTurn>,
) {
    companion object {
        fun canonical(): RelationshipScenario = RelationshipScenario(
            name = "canonical-stranger-to-lover",
            turns = buildList {
                repeat(20) { index ->
                    add(
                        ScenarioTurn(
                            advanceMs = 60_000L,
                            userText = "要不要第${index + 1}次一起做点什么？不用也没关系。",
                            tags = setOf("negative", "stranger"),
                        ),
                    )
                }
                add(ScenarioTurn(300_000L, "我想认真告诉你一件事。", setOf("confession")))
                add(ScenarioTurn(60_000L, "我接受你的回答，也会珍惜它。", setOf("acceptance")))
                add(ScenarioTurn(86_400_000L, "重启以后，你还记得我们约定慢慢来吗？", setOf("restart")))
                add(ScenarioTurn(600_000L, "今天我很想和你待在一起。", setOf("hot-romance")))
                add(ScenarioTurn(300_000L, "晚饭和洗碗我们怎么分？", setOf("chores")))
                add(ScenarioTurn(1_800_000L, "", setOf("silence")))
                add(ScenarioTurn(21_600_000L, "[HEARTBEAT_TRIGGER]", setOf("heartbeat")))
                add(ScenarioTurn(300_000L, "这件事我现在不想继续讨论，请尊重这个界限。", setOf("boundary")))
                add(ScenarioTurn(300_000L, "刚才的话让我很受伤，我们有分歧。", setOf("conflict")))
                add(ScenarioTurn(600_000L, "我们把误会说开，再一起修复它。", setOf("repair")))
                repeat(110) { index ->
                    add(
                        ScenarioTurn(
                            advanceMs = 900_000L,
                            userText = "第${index + 1}次日常近况记录。",
                            tags = setOf("daily"),
                        ),
                    )
                }
            },
        )
    }
}
