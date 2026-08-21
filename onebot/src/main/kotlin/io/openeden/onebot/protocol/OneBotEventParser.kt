package io.openeden.onebot.protocol

import io.openeden.onebot.config.OneBotConfig
import io.openeden.onebot.config.OneBotGroupPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class OneBotEventParser(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun parse(raw: String, config: OneBotConfig): OneBotInbound = runCatching {
        parseObject(json.parseToJsonElement(raw).jsonObject, config)
    }.getOrElse { OneBotInbound.Ignored("malformed") }

    private fun parseObject(root: JsonObject, config: OneBotConfig): OneBotInbound {
        root.id("echo")?.let { echo ->
            return OneBotInbound.Action(
                OneBotActionResponse(
                    status = root.id("status").orEmpty(),
                    retCode = root["retcode"]?.jsonPrimitive?.intOrNull ?: -1,
                    echo = echo,
                ),
            )
        }
        if (root.id("post_type") != "message") return OneBotInbound.Ignored("unsupported_post_type")
        val selfId = root.id("self_id") ?: return OneBotInbound.Ignored("missing_self_id")
        if (selfId != config.botSelfId) return OneBotInbound.Ignored("self_id_mismatch")
        val userId = root.id("user_id") ?: return OneBotInbound.Ignored("missing_user_id")
        if (userId == selfId) return OneBotInbound.Ignored("self_message")
        val messageId = root.id("message_id") ?: return OneBotInbound.Ignored("missing_message_id")
        val extracted = extract(root["message"], selfId)
        val text = extracted.text.trim()
        if (text.isEmpty()) return OneBotInbound.Ignored("empty_text")

        val target = when (root.id("message_type")) {
            "private" -> OneBotReplyTarget.Private(userId)
            "group" -> {
                when (config.groupPolicy) {
                    OneBotGroupPolicy.DISABLED -> return OneBotInbound.Ignored("group_disabled")
                    OneBotGroupPolicy.MENTION_ONLY -> if (!extracted.mentioned) {
                        return OneBotInbound.Ignored("mention_required")
                    }
                    OneBotGroupPolicy.ALL -> Unit
                }
                OneBotReplyTarget.Group(
                    root.id("group_id") ?: return OneBotInbound.Ignored("missing_group_id"),
                )
            }
            else -> return OneBotInbound.Ignored("unsupported_message_type")
        }
        return OneBotInbound.Message(
            OneBotMessageEvent(selfId, messageId, userId, text, target),
        )
    }

    private fun extract(message: JsonElement?, selfId: String): ExtractedText = when (message) {
        is JsonArray -> {
            val text = StringBuilder()
            var mentioned = false
            message.forEach { element ->
                val segment = element.jsonObject
                val data = segment["data"]?.jsonObject ?: return@forEach
                when (segment.id("type")) {
                    "text" -> text.append(data.id("text").orEmpty())
                    "at" -> if (data.id("qq") == selfId) mentioned = true
                }
            }
            ExtractedText(text.toString(), mentioned)
        }
        null -> ExtractedText("", false)
        else -> ExtractedText(message.jsonPrimitive.contentOrNull.orEmpty(), false)
    }

    private fun JsonObject.id(name: String): String? =
        get(name)?.jsonPrimitive?.contentOrNull

    private data class ExtractedText(val text: String, val mentioned: Boolean)
}
