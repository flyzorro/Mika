package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.run.waitOrSteer
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.fail
import com.wangzan101.mika.core.tool.okObj
import com.wangzan101.mika.core.userscreen.MainScreenPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
 * 另外提供看屏用法(`action: "watch"`):在一次调用里自己截用户主屏、发给 Mac、把建议闪到悬浮胶囊上,循环中不调用模型。
 */
internal object PhoneMahjongHint : PhoneTool {
    override val verb = Verb.PhoneGame
    override val risk = Risk.Read
    /** 手机那组收着时也给:用户一句「开始麻将提示」,模型第一步就看得到;单用就够,不展开整组。 */
    override val entry = com.wangzan101.mika.core.tool.Entry.Alone

    /** 用户打麻将的那款 App;只有它在用户主屏前台时才截图、才发给 Mac。 */
    const val JJ_PACKAGE = "cn.jj.mahjong"

    override val def = ToolDef("phone_mahjong_hint", Prompts.tool("phone_mahjong_hint"), mapOf(
        "action" to buildJsonObject {
            put("type", "string")
            put("enum", JsonArray(listOf("watch").map(::JsonPrimitive)))
            put("description", "watch: keep watching the user's own screen and flash hints on the capsule until told to stop; hand and que are not needed. Omit for one answer about a hand you read.")
        },
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
        "advisor" to buildJsonObject { put("type", "string"); put("description", "Mahjong service URL on the Mac, as the user gave it; omit to use the preset one.") },
    ))

    /** 问麻将服务;测试里换掉。返回响应正文,连不上为 null。 */
    internal var post: suspend (url: String, body: String) -> String? = { url, body -> DdzAdvisor.post(url, body, "discard") }

    /** 看屏时把一帧发给 `/hint`;测试里换掉。连不上为 null。 */
    internal var postHint: suspend (url: String, body: String) -> String? = { url, body -> HintAdvisor.post(url, body) }

    /** 看屏的时钟;测试里换成虚拟时钟。 */
    internal var clock: () -> Long = System::currentTimeMillis

    /**
     * 工具列表在任务开始时就定了,那时游戏还没打开。手动用法要模型看图读牌;看屏用法不经过模型看图,
     * 能截主屏、能闪胶囊就够——主模型不能看图(如 DeepSeek flash)时也要提供。
     */
    override fun available(ctx: ToolContext) =
        ctx.config.visionEnabled || (ctx.config.mainScreen != null && ctx.config.hintHud != null)

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val advisor = a.str("advisor") ?: ctx.config.mahjongAdvisor?.takeIf { it.isNotBlank() } ?: throw ArgError("advisor", "required")
        return when (val action = a.str("action")?.trim()?.lowercase()) {
            // 没说 action:手牌、定缺都没给就是开始看屏(弱模型常空着参数调);给了任一个是手动问一次
            null -> if (a.o["hand"] == null && a.o["que"] == null) watch(advisor, ctx) else discard(a, advisor)
            "watch" -> watch(advisor, ctx)
            else -> throw ArgError("action", "must be watch")
        }
    }

    private suspend fun discard(a: Args, advisor: String): ToolExecution {
        val hand = a.strList("hand", 1, 14) ?: throw ArgError("hand", "required")
        val que = a.str("que") ?: throw ArgError("que", "required")
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

    // ---------- 看屏 ----------

    /** 一共看这么久。 */
    private const val WATCH_TOTAL_MS = 60L * 60 * 1000
    /** JJ麻将连续这么久不在前台就停。 */
    private const val WATCH_APP_LEFT_MS = 60_000L
    /** 开始后这么久一直没看到 JJ麻将也停。 */
    private const val WATCH_START_GRACE_MS = 2 * 60_000L
    /** 不在麻将里(没截图)时多看几眼前台的间隔。 */
    private const val WATCH_POLL_MS = 500L
    /** 截一张到下一张之间至少隔这么久(系统限制两次截图间隔,取 1 秒)。 */
    private const val WATCH_ROUND_MS = 1_000L
    /** 一轮已经超过 [WATCH_ROUND_MS] 时也让出一下,别把循环挤成死循环。 */
    private const val WATCH_MIN_PAUSE_MS = 50L
    /** Mac 连续这么多次连不上就停。 */
    private const val SERVICE_DOWN_LIMIT = 10

    private const val STOP_APP_LEFT = "app_left"
    private const val STOP_NEW_MESSAGE = "new_message"
    private const val STOP_TIMEOUT = "timeout"
    private const val STOP_SERVICE_DOWN = "service_down"
    private const val STOP_SCREEN_UNAVAILABLE = "screen_unavailable"
    private const val STOP_BUSY = "busy"

    private fun now() = clock()

    private fun summary(hinted: Int, unsure: Int, stopped: String) = ToolExecution(okObj {
        put("hinted", hinted)
        put("unsure", unsure)
        put("stopped", stopped)
    })

    /**
     * 持续看用户的主屏:只在前台是 JJ麻将时截图、发给 Mac 的 `/hint`,把建议闪到悬浮胶囊上。
     * 循环里不调用模型、不点屏;用户插话时收尾,用户点停止时协程被取消(在 finally 里闪一句"已停止")。
     */
    private suspend fun watch(advisor: String, ctx: ToolContext): ToolExecution {
        val main = ctx.config.mainScreen ?: return fail("watch_unavailable", NO_WATCH)
        val hud = ctx.config.hintHud ?: return fail("watch_unavailable", NO_WATCH)
        if (!main.beginWatch()) return summary(0, 0, STOP_BUSY)
        var hinted = 0
        var unsure = 0
        var stopped: String? = null
        var firstSeen: Long? = null
        var lastSeen = 0L
        var failures = 0
        var pending: String? = null
        var hintedKey: String? = null
        var winFlashed = false
        var queHinted: String? = null
        var cancelled = false
        val started = now()
        try {
            while (stopped == null) {
                if (now() - started >= WATCH_TOTAL_MS) { stopped = STOP_TIMEOUT; break }
                if (firstSeen == null && now() - started >= WATCH_START_GRACE_MS) { stopped = STOP_APP_LEFT; break }
                if (firstSeen != null && now() - lastSeen >= WATCH_APP_LEFT_MS) { stopped = STOP_APP_LEFT; break }
                val tick = now()
                var captured = false
                if (main.foreground() == JJ_PACKAGE) {
                    firstSeen = firstSeen ?: now()
                    lastSeen = now()
                    when (val shot = main.capture(JJ_PACKAGE)) {
                        is MainScreenPort.Capture.Ok -> {
                            captured = true
                            val reply = postHint(advisor, hintBody(shot.jpeg))
                            if (reply == null) {
                                if (++failures >= SERVICE_DOWN_LIMIT) { stopped = STOP_SERVICE_DOWN; break }
                            } else {
                                failures = 0
                                val root = runCatching { Json.parseToJsonElement(reply) as? JsonObject }.getOrNull()
                                when (stateOf(root)) {
                                    "turn" -> {
                                        winFlashed = false
                                        queHinted = null
                                        val key = root?.let(::frameKey)
                                        if (key != null && key == pending && key != hintedKey) {
                                            val text = root?.let(::turnText)
                                            if (text != null) {
                                                hud.flash(text)
                                                hinted++
                                                hintedKey = key
                                            } else {
                                                unsure++
                                            }
                                        }
                                        pending = key
                                    }
                                    "win" -> {
                                        pending = null
                                        if (!winFlashed) {
                                            hud.flash(WIN_TEXT)
                                            hinted++
                                            winFlashed = true
                                        }
                                    }
                                    "que_select" -> {
                                        pending = null
                                        val text = root?.let(::queText)
                                        if (text == null) {
                                            unsure++
                                        } else if (text != queHinted) {
                                            hud.flash(text)
                                            hinted++
                                            queHinted = text
                                        }
                                    }
                                    "unsure" -> {
                                        pending = null
                                        unsure++
                                    }
                                    else -> pending = null
                                }
                            }
                        }
                        MainScreenPort.Capture.Skip -> Unit
                        MainScreenPort.Capture.Unavailable -> { stopped = STOP_SCREEN_UNAVAILABLE; break }
                    }
                }
                val sleep = if (captured) (WATCH_ROUND_MS - (now() - tick)).coerceAtLeast(WATCH_MIN_PAUSE_MS) else WATCH_POLL_MS
                if (waitOrSteer { delay(sleep) } == null) { stopped = STOP_NEW_MESSAGE; break }
            }
        } catch (e: CancellationException) {
            cancelled = true
            throw e
        } finally {
            withContext(NonCancellable) {
                if (cancelled) runCatching { hud.flash(STOPPED) }
                main.endWatch()
            }
        }
        return summary(hinted, unsure, stopped ?: STOP_NEW_MESSAGE)
    }

    private fun hintBody(jpeg: ByteArray) = buildJsonObject {
        put("image", java.util.Base64.getEncoder().encodeToString(jpeg))
    }.toString()

    private fun stateOf(root: JsonObject?) = root?.str("state")

    /** 这一手的身份:手牌 + 碰杠(和顺序无关)。认不出(没有手牌)为 null。 */
    private fun frameKey(root: JsonObject): String? {
        val hand = (root["hand"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val melds = (root["melds"] as? JsonArray).orEmpty().mapNotNull { m ->
            val o = m as? JsonObject ?: return@mapNotNull null
            "${o.str("type")}:${o.str("tile")}"
        }.sorted()
        return hand.sorted().joinToString(",") + "|" + melds.joinToString(",")
    }

    /**
     * 胶囊上的那句话,不超过 30 个字:`打 5万 · 冲清一色 · 期望 1.4倍`,有不一样的「荐」时后面加 `（游戏荐 9条）`。
     * 服务没给建议(读错、没轮到)时为 null。
     */
    private fun turnText(root: JsonObject): String? {
        val advice = root["advice"] as? JsonObject ?: return null
        val discard = advice.str("discard") ?: return null
        val parts = mutableListOf("打 ${tileName(discard)}")
        when (advice.str("target")) {
            "fast" -> parts += "快胡"
            "qingyise" -> parts += "冲清一色"
            "pengpeng" -> parts += "冲碰碰胡"
            "qidui" -> parts += "冲七对"
        }
        val value = advice.num("value")
        if (value != null && value > 0) parts += "期望 ${multiplier(value)}倍"
        val jian = root.str("jian")
        val suffix = if (jian != null && jian != discard) "（游戏荐 ${tileName(jian)}）" else ""
        return clip(parts.joinToString(" · ") + suffix)
    }

    /** 定缺那句:`定缺 条`;服务没给出定缺哪门时为 null。 */
    private fun queText(root: JsonObject): String? {
        val que = (root["advice"] as? JsonObject)?.str("que") ?: return null
        val suit = when (que) {
            "m" -> "万"
            "p" -> "筒"
            "s" -> "条"
            else -> return null
        }
        return "定缺 $suit"
    }

    private const val WIN_TEXT = "可以胡"

    private fun tileName(tile: String): String = when (tile.lastOrNull()) {
        'm' -> tile.dropLast(1) + "万"
        'p' -> tile.dropLast(1) + "筒"
        's' -> tile.dropLast(1) + "条"
        else -> tile
    }

    private fun multiplier(value: Double): String =
        String.format(java.util.Locale.ROOT, "%.1f", value).removeSuffix(".0")

    private fun clip(text: String): String =
        if (text.length <= 30) text else text.take(29) + "…"

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.num(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

    private const val STOPPED = "麻将提示已停止"

    private const val NO_WATCH =
        "Watching the user's own screen is not available in this task; tell the user to start the task in the Mika app on the phone."

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
