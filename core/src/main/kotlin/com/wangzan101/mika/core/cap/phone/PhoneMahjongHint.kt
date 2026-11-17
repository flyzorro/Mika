package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 四川麻将血战到底的切牌建议:把模型从截图上读出的手牌、碰杠、定缺交给 Mac 上的麻将服务(tools/mahjong),
 * 由它算切哪张、几向听、进张是哪些。工具自己不看屏、不点屏、不截图。
 */
internal object PhoneMahjongHint : PhoneTool {
    override val verb = Verb.PhoneGame
    override val risk = Risk.Read
    override val def = ToolDef("phone_mahjong_hint", Prompts.tool("phone_mahjong_hint"), mapOf(
        "hand" to buildJsonObject {
            put("type", "array")
            put("description", "Your whole hand: 14 tiles minus 3 for each meld, e.g. 5m.")
            put("items", buildJsonObject { put("type", "string") })
        },
        "melds" to buildJsonObject {
            put("type", "array")
            put("description", "Pons and kans, each {type: pon|kan, tile: 5p}; omit when there are none.")
            put("items", buildJsonObject {
                put("type", "object")
                put("additionalProperties", false)
                put("required", JsonArray(listOf("type", "tile").map(::JsonPrimitive)))
                put("properties", buildJsonObject {
                    put("type", buildJsonObject {
                        put("type", "string")
                        put("enum", JsonArray(listOf("pon", "kan").map(::JsonPrimitive)))
                    })
                    put("tile", buildJsonObject { put("type", "string"); put("description", "The melded tile, e.g. 5p.") })
                })
            })
        },
        "que" to buildJsonObject {
            put("type", "string")
            put("enum", JsonArray(listOf("m", "p", "s").map(::JsonPrimitive)))
            put("description", "定缺: the suit you must get rid of first, m (万), p (筒) or s (条).")
        },
        "advisor" to buildJsonObject { put("type", "string"); put("description", "Mahjong service URL on the Mac, as the user gave it.") },
    ), listOf("hand", "que", "advisor"))

    /** 问麻将服务;测试里换掉。返回响应正文,连不上为 null。 */
    internal var post: suspend (url: String, body: String) -> String? = { url, body -> DdzAdvisor.post(url, body, "discard") }

    /** 工具列表在任务开始时就定了,那时游戏还没打开,所以这里只看主模型能不能看图。 */
    override fun available(ctx: ToolContext) = ctx.config.visionEnabled

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val hand = a.strList("hand", 1, 14) ?: throw ArgError("hand", "required")
        val que = a.str("que") ?: throw ArgError("que", "required")
        val advisor = a.str("advisor") ?: throw ArgError("advisor", "required")
        val reply = post(advisor, buildJsonObject {
            put("hand", JsonArray(hand.map(::JsonPrimitive)))
            put("melds", melds(a))
            put("que", que)
        }.toString()) ?: return fail("service_unavailable", SERVICE_DOWN)
        val advice = runCatching { Json.parseToJsonElement(reply) as? JsonObject }.getOrNull()
            ?: return fail("service_unavailable", SERVICE_DOWN)
        // 服务把校验错误也当 200 回:错误码原样交给模型,并让它重看截图核对
        val error = (advice["error"] as? JsonPrimitive)?.contentOrNull
        if (error != null) {
            val detail = (advice["detail"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            return ToolExecution(JsonObject(fail(error, REREAD).content + ("detail" to JsonPrimitive(detail))))
        }
        return ToolExecution(JsonObject(advice + ("ok" to JsonPrimitive(true))))
    }

    /**
     * 碰杠原样交给服务:哪种副露、哪张牌由服务校验(bad_meld),工具不另定一套规矩。
     * 没给就是空数组(服务两种都收)。
     */
    private fun melds(a: Args): JsonArray = when (val v = a.o["melds"]) {
        null, is JsonNull -> JsonArray(emptyList())
        is JsonArray -> v
        else -> throw ArgError("melds", "must be an array")
    }

    /** 服务连不上(含非 2xx、超时)时告诉模型:Mac 上的麻将服务没开。 */
    private const val SERVICE_DOWN =
        "Cannot reach the mahjong service at the advisor URL. Tell the user the mahjong service on the Mac is not running."

    /** 服务说请求不合法:多半是手牌、副露或定缺读错了。 */
    private const val REREAD = "Look at the screenshot again, check the hand, melds and 定缺, and call again."
}
