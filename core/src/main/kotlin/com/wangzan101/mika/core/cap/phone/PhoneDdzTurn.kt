package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.capability.CapabilityKey
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.run.waitOrSteer
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.uistate.screen
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 斗地主的快速路径:一次调用走完这一手,再在手机上等到下一个要决定的时刻,一手只花一次模型调用。
 * 普通点击每下都等界面、核实效果,一手牌要几十秒,赶不上 15 秒的倒计时;这里跳过这些,改由自己看画面判断。
 * 所以只认这一款游戏:别的 App 一律不动,认不出的画面也不点。
 */
internal object PhoneDdzTurn : PhoneTool {
    const val PACKAGE = "com.ShenY.DDiZHU.nearme.gamecenter"
    private val STATE = CapabilityKey<Turn>("ddz_turn")
    private const val POLL_MS = 150L
    /** 两个对手各 15 秒,再留些发牌和动画的时间。 */
    private const val MAX_POLLS = 300
    /** 再来一局几秒就发牌;过了这么久还没有多半是弹窗,交给模型看。 */
    private const val AGAIN_POLLS = 60
    private const val TAP_GAP_MS = 60L
    private const val SETTLE_MS = 250L
    private const val VERDICT_MS = 800L
    /** 这一手过了这么久就不再问模型,直接保底,免得倒计时走完。 */
    private const val RETRY_BUDGET_MS = 8_000L

    private class Turn { var seenAt: Long? = null; var rejected = 0 }

    override val verb = Verb.PhoneGame
    override val risk = Risk.Write
    override val def = ToolDef("phone_ddz_turn", Prompts.tool("phone_ddz_turn"), mapOf(
        "action" to buildJsonObject {
            put("type", "string")
            put("enum", JsonArray(listOf("play", "pass", "hint", "call", "again", "wait").map(::JsonPrimitive)))
        },
        "hand" to ranks("Your whole hand, left to right."),
        "play" to ranks("The cards to play."),
        "level" to buildJsonObject { put("type", "integer"); put("description", "call: 0-3.") },
    ), listOf("action"))

    private fun ranks(text: String) = buildJsonObject {
        put("type", "array"); put("description", text)
        put("items", buildJsonObject { put("type", "string") })
    }

    /** 工具列表在任务开始时就定了,那时游戏还没打开,所以这里只看主模型能不能看图;是不是这款游戏在执行时查。 */
    override fun available(ctx: ToolContext) = ctx.config.visionEnabled

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        if (ctx.screen.peek().packageName != PACKAGE) return refused("wrong_app")
        val action = a.str("action") ?: throw ArgError("action", "required")
        val turn = ctx[STATE] ?: Turn().also { ctx[STATE] = it }
        val view = takeBackControl(read(ctx) ?: return refused("no_frame"), ctx)
        val thinkMs = turn.seenAt?.let { now() - it } ?: 0
        val tapStart = now()
        val move = move(action, a, view, ctx, turn, thinkMs)
        val tapMs = now() - tapStart
        val waitStart = now()
        val next = when {
            move.moved || action == "wait" -> awaitDecision(ctx, view.phase, move.moved, if (action == "again") AGAIN_POLLS else MAX_POLLS)
            else -> read(ctx) ?: view
        }
        val waitMs = now() - waitStart
        if (move.moved) turn.rejected = 0
        val shotStart = now()
        val needsLook = next.phase !in setOf(DdzPhase.Result, DdzPhase.AutoPlay) && ctx.config.visionEnabled
        val shot = if (needsLook) ctx.screen.quickShot() else null
        val shotMs = now() - shotStart
        turn.seenAt = now()
        return ToolExecution(buildJsonObject {
            put("ok", move.error == null)
            move.error?.let { put("error", it); put("recoverable", true) }
            move.note?.let { put("note", it) }
            put("phase", next.phase.name.lowercase())
            put("handCount", next.handCount)
            put("timing", buildJsonObject {
                put("thinkMs", thinkMs); put("tapMs", tapMs); put("waitMs", waitMs); put("shotMs", shotMs)
            })
        }, image = shot, frame = shot, changed = move.moved)
    }

    private class Move(val moved: Boolean, val error: String? = null, val note: String? = null)

    private suspend fun move(action: String, a: Args, view: DdzView, ctx: ToolContext, turn: Turn, thinkMs: Long): Move {
        val phase = view.phase
        return when (action) {
            "wait" -> Move(false)
            "again" -> if (phase == DdzPhase.Result) press(view.buttons[0], ctx) else wrongPhase(phase)
            "call" -> if (phase != DdzPhase.Call) wrongPhase(phase) else {
                val level = a.int("level", 0, 3) ?: 0
                // 灰的是比别人叫得低、点不了的分:按不叫算
                val button = view.buttons[level].takeIf { it.color != DdzColor.Gray } ?: view.buttons[0]
                press(button, ctx)
            }
            "pass" -> when (phase) {
                DdzPhase.Follow -> press(view.buttons[0], ctx)
                DdzPhase.Lead -> smallest(view, ctx)
                else -> wrongPhase(phase)
            }
            "hint" -> when (phase) {
                DdzPhase.Follow -> hint(view, ctx)
                DdzPhase.Lead -> smallest(view, ctx)
                else -> wrongPhase(phase)
            }
            "play" -> if (phase != DdzPhase.Follow && phase != DdzPhase.Lead) wrongPhase(phase) else play(a, view, ctx, turn, thinkMs)
            else -> throw ArgError("action", "unknown action")
        }
    }

    private suspend fun play(a: Args, view: DdzView, ctx: ToolContext, turn: Turn, thinkMs: Long): Move {
        val hand = strings(a, "hand")
        val want = pick(hand, strings(a, "play"))
        val error = when {
            hand.size != view.handCount -> "hand_mismatch"
            want == null || want.isEmpty() -> "card_not_in_hand"
            else -> null
        }
        val selected = if (error == null) select(want!!, ctx) else null
        if (selected != null) {
            if (confirm(view, ctx)) return Move(true)
        }
        // 模型读错、出的牌不合规:还来得及就让它改一次,来不及或改过了就保底
        val reason = error ?: if (selected == null) "select_failed" else "rejected"
        if (turn.rejected == 0 && thinkMs < RETRY_BUDGET_MS) {
            turn.rejected++
            return Move(false, reason)
        }
        val now = read(ctx) ?: view
        val fallback = if (now.phase == DdzPhase.Follow) press(now.buttons[0], ctx) else smallest(now, ctx)
        return Move(fallback.moved, fallback.error, "fallback_after_$reason")
    }

    private suspend fun hint(view: DdzView, ctx: ToolContext): Move {
        if (!ctx.screen.quickTap(view.buttons[1].x, view.buttons[1].y)) return Move(false, "tap_failed")
        delay(SETTLE_MS)
        val after = read(ctx) ?: return Move(false, "no_frame")
        // 按钮没了就是这一手已经过去;提示抬不起牌就是要不起
        if (after.phase != DdzPhase.Follow) return Move(true)
        if (after.lifted.isEmpty()) return press(view.buttons[0], ctx)
        return if (confirm(view, ctx)) Move(true) else press(view.buttons[0], ctx)
    }

    private suspend fun smallest(view: DdzView, ctx: ToolContext): Move {
        select(setOf(view.handCount - 1), ctx) ?: return Move(false, "select_failed")
        return if (confirm(view, ctx)) Move(true) else Move(false, "rejected")
    }

    /**
     * 按出牌,看画面确认牌出去了。抬牌动画里按下去游戏可能不认,没出去就再按一次;还不走才算不合规。
     * 用选牌前的 [view] 里的位置:按钮不动,而选完这一帧可能已经超时、没有按钮了。
     */
    private suspend fun confirm(view: DdzView, ctx: ToolContext): Boolean {
        repeat(2) {
            if (!ctx.screen.quickTap(view.buttons.last().x, view.buttons.last().y)) return false
            delay(VERDICT_MS)
            val after = read(ctx)
            if (after == null || after.phase != view.phase || after.handCount != view.handCount) return true
        }
        return false
    }

    /** 把抬起的牌改成 [want];点完看画面,没到位再补一次。 */
    private suspend fun select(want: Set<Int>, ctx: ToolContext): DdzView? {
        repeat(2) {
            val view = read(ctx)?.takeIf { it.phase == DdzPhase.Follow || it.phase == DdzPhase.Lead } ?: return null
            val toggle = (want - view.lifted) + (view.lifted - want)
            if (toggle.isEmpty()) return view
            for (index in toggle) {
                if (!ctx.screen.quickTap(view.cardX(index), DdzScreen.CARD_Y)) return null
                delay(TAP_GAP_MS)
            }
            delay(SETTLE_MS)
        }
        return read(ctx)?.takeIf { it.lifted == want }
    }

    private suspend fun press(button: DdzButton, ctx: ToolContext) =
        if (ctx.screen.quickTap(button.x, button.y)) Move(true) else Move(false, "tap_failed")

    /** 模型想的时候超时进了托管:先取消,再按取消后的画面做。 */
    private suspend fun takeBackControl(view: DdzView, ctx: ToolContext): DdzView {
        if (view.phase != DdzPhase.AutoPlay || !ctx.screen.quickTap(view.buttons[0].x, view.buttons[0].y)) return view
        delay(SETTLE_MS)
        return read(ctx) ?: view
    }

    /** 等到又该玩家决定;[moved] 刚动过手时,同一阶段要先离开过才算新的一手。托管了就取消。 */
    private suspend fun awaitDecision(ctx: ToolContext, from: DdzPhase, moved: Boolean, polls: Int): DdzView {
        var left = !moved
        var last = DdzView(DdzPhase.Other, emptyList(), 0, 0, 0, emptySet())
        repeat(polls) {
            val view = read(ctx)
            if (view != null) {
                last = view
                when (view.phase) {
                    DdzPhase.AutoPlay -> {
                        left = true
                        // 等的时候前台可能换了,点之前再认一次
                        if (ctx.screen.peek().packageName == PACKAGE) ctx.screen.quickTap(view.buttons[0].x, view.buttons[0].y)
                    }
                    // 加倍只有约 5 秒,模型来不及,让它走完就是不加倍
                    DdzPhase.Other, DdzPhase.Double -> left = true
                    else -> if (left || view.phase != from) return view
                }
            }
            // 用户插了话就不等了:这一手已经做完,模型接着读那句话
            waitOrSteer { delay(POLL_MS) } ?: return last
        }
        return last
    }

    private suspend fun read(ctx: ToolContext): DdzView? = ctx.screen.rawFrame(DdzScreen.ROWS)?.let(DdzScreen::read)

    private fun wrongPhase(phase: DdzPhase) = Move(false, "wrong_phase", phase.name.lowercase())

    private fun refused(code: String) = ToolExecution(buildJsonObject {
        put("ok", false); put("error", code); put("recoverable", true)
    })

    private fun strings(a: Args, key: String): List<String> =
        (a.o[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.uppercase() }

    /** 把要出的点数对到手牌的序号上;手里没有这张时 null。 */
    internal fun pick(hand: List<String>, play: List<String>): Set<Int>? {
        val free = hand.indices.toMutableList()
        val picked = LinkedHashSet<Int>()
        for (rank in play) {
            val at = free.firstOrNull { hand[it] == rank } ?: return null
            free -= at
            picked += at
        }
        return picked
    }

    private fun now() = System.currentTimeMillis()
}
