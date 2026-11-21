package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.capability.CapabilityKey
import com.wangzan101.mika.core.run.waitOrSteer
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.uistate.screen
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.random.Random

/**
 * JJ 斗地主(cn.jj)真人场的全自动打法:只做 human 模式的 start / wait / stop。
 * 按钮和闹钟在手机上看像素([JjDdzScreen]);牌每一轮截一张 JPEG 交给电脑认([JjCards]),喂给 [DdzTracker] 记牌,
 * 轮到自己就问 DouZero。和经典版([PhoneDdzTurn])共用记牌、问 DouZero、叫分建议,别的流程各写各的:
 * 经典版每 150ms 在手机上同步读牌,这里一轮要等电脑回话。
 *
 * 认不出的画面一下都不点;连不上电脑、切走了 App、很久没有手牌(终局页、没见过的弹窗)都停下交还模型。
 */
internal object JjDdzTurn {
    private val STATE = CapabilityKey<Session>("jj_ddz_turn")
    private const val POLL_MS = 150L
    /** 电脑连着这么多轮认不了牌就停。 */
    private const val DETECT_FAILURES = 4
    /** 连着这么多轮没有手牌,上一手就打完了;再看到手牌是新的一手。 */
    private const val NEW_HAND_GAP = 6
    /** 没有手牌这么久(结算、晋级、等别桌打完都在 1 分钟内):多半是终局页或弹窗,停下。 */
    private const val IDLE_MS = 90_000L
    /** 轮到自己时别家的牌还没读稳,最多再等这么久(倒计时 15 秒)。 */
    private const val SETTLE_MS = 4_000L
    private const val TAP_GAP_MS = 120L
    private const val AGAIN_GAP_MS = 5_000L
    private const val MAX_HANDS = 10
    private const val HAND_SIZE = 17

    internal class Session(val advisor: String, var handsLeft: Int) {
        var tracker: DdzTracker? = null
        var state = DdzSessionState.Idle
        var reason: String? = null
        var stopped = false
        var handsPlayed = 0
        /** DouZero 出完这一手后手里该剩的牌;下一手认到的不一样就是有张牌认错、点错了。 */
        var expectedHand: List<String>? = null
        var bid = false
        /** 这一个决定已经点过了:按钮还会留几帧,等它消失前不再点。 */
        var acted = false
        var noHand = 0
        var lastHandAt = 0L
        var settleSince = 0L
        var detectFailures = 0
        /** 上次点「再来一局」的时刻:点完页面要一两秒才走,别连着点。 */
        var againAt = 0L
        var lastPhase = DdzPhase.Other
        var handCount = 0
    }

    /** 叫分、出牌前想一会儿(1.5–4 秒,在 15 秒倒计时里):每次都秒点太像机器。测试里换掉。 */
    internal var think: suspend () -> Unit = { delay(Random.nextLong(1_500, 4_000)) }

    /** 把一帧交给电脑认牌;测试里换掉。 */
    internal var detect: suspend (url: String, jpeg: ByteArray) -> List<JjCard>? = JjCards::detect

    fun active(ctx: ToolContext): Boolean = ctx[STATE]?.let { !it.stopped } == true

    suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val action = a.str("action") ?: return refused("action_required")
        if (action == "stop") {
            val s = ctx[STATE] ?: return refused("not_started")
            s.stopped = true
            s.state = DdzSessionState.Stopped
            s.reason = DdzSessionReason.USER_STOP
            return result(s)
        }
        if (action == "start") {
            if (a.str("mode") != DdzMode.Human.wire) return refused("jj_human_only")
            val advisor = a.str("advisor") ?: return refused("advisor_required")
            if (!a.bool("auto", false)) return refused("jj_auto_only")
            if (!PhoneDdzTurn.probeAdvisor(advisor)) return refused(DdzSessionReason.SERVICE_UNAVAILABLE)
            val s = Session(advisor, a.int("hands", 1, MAX_HANDS) ?: 1)
            s.lastHandAt = PhoneDdzTurn.clock()
            ctx[STATE] = s
            return drive(ctx, s)
        }
        val s = ctx[STATE] ?: return refused("not_started")
        if (s.stopped) return result(s)
        return when (action) {
            // 停下的原因还在:模型处理完(关了弹窗、点了继续)再 wait 接着打
            "wait" -> {
                s.state = DdzSessionState.Playing
                s.reason = null
                s.detectFailures = 0
                s.lastHandAt = PhoneDdzTurn.clock()
                drive(ctx, s)
            }
            else -> refused("jj_unsupported_action")
        }
    }

    private suspend fun drive(ctx: ToolContext, s: Session): ToolExecution {
        while (true) {
            if (s.stopped) return result(s)
            if (ctx.screen.peek().packageName != JjDdzScreen.PACKAGE) return pause(s, DdzSessionReason.APP_SWITCH)
            val frame = ctx.screen.rawFrame(JjDdzScreen.ROWS)
            val jpeg = frame?.let { ctx.screen.quickShot() }
            val cards = jpeg?.let { detect(s.advisor, it) }
            if (frame == null || cards == null) {
                if (++s.detectFailures >= DETECT_FAILURES) return pause(s, DdzSessionReason.SERVICE_UNAVAILABLE)
                waitOrSteer { delay(POLL_MS) } ?: return result(s)
                continue
            }
            s.detectFailures = 0
            val view = JjDdzScreen.read(frame)
            val table = JjCards.table(cards, frame.width)
            s.handCount = table.hand.size
            if (!view.phase.mine()) s.acted = false
            s.lastPhase = view.phase
            if (table.hand.isEmpty()) {
                if (++s.noHand == NEW_HAND_GAP && s.tracker != null) {
                    // 一手打完
                    s.tracker = null
                    s.expectedHand = null
                    s.handsPlayed++
                    if (--s.handsLeft <= 0) {
                        s.state = DdzSessionState.Result
                        return result(s)
                    }
                }
                // 终局页:还没打够就点「再来一局」(白名单里唯一会点的结算按钮)
                if (view.phase == DdzPhase.Result && PhoneDdzTurn.clock() - s.againAt > AGAIN_GAP_MS) {
                    think()
                    tap(ctx, view.buttons[0].x, JjDdzScreen.AGAIN_TAP_Y)
                    s.againAt = PhoneDdzTurn.clock()
                    s.lastHandAt = s.againAt
                }
                if (PhoneDdzTurn.clock() - s.lastHandAt > IDLE_MS) return pause(s, DdzSessionReason.UNKNOWN_SCENE)
                waitOrSteer { delay(POLL_MS) } ?: return result(s)
                continue
            }
            s.lastHandAt = PhoneDdzTurn.clock()
            if (s.noHand >= NEW_HAND_GAP || s.tracker == null) {
                // 牌是一张张发下来的:没到 17 张、也还没轮到我出牌,就是还在发牌,等发完再从头记
                if (table.hand.size != HAND_SIZE && view.phase != DdzPhase.Lead && view.phase != DdzPhase.Follow) {
                    waitOrSteer { delay(POLL_MS) } ?: return result(s)
                    continue
                }
                newHand(s, table, view)
            }
            s.noHand = 0
            val tracker = s.tracker!!
            tracker.observe(
                rawTurn = view.turn,
                read = table::seen,
                bottom = { table.bottom },
                leadingWith20 = view.phase == DdzPhase.Lead && table.hand.size == 20,
                leading = view.phase == DdzPhase.Lead,
                hand = { table.handRanks.takeUnless { table.handUnsure } },
            )
            if (view.phase.mine() && !s.acted) {
                s.state = if (view.phase == DdzPhase.Call) DdzSessionState.Calling else DdzSessionState.Playing
                if (decide(ctx, s, view, table)) s.acted = true
            }
            waitOrSteer { delay(POLL_MS) } ?: return result(s)
        }
    }

    private fun DdzPhase.mine() = this == DdzPhase.Call || this == DdzPhase.Lead || this == DdzPhase.Follow

    /** 看到新发的 17 张从头记;轮到我出牌时才第一次看到手牌(张数不对)是中途进来的,这一手只按规则打完。 */
    private fun newHand(s: Session, table: JjTable, view: JjView) {
        s.tracker = DdzTracker(DdzSettlingPolicy.HUMAN)
        s.expectedHand = null
        s.bid = false
        s.settleSince = 0
        if (table.hand.size != HAND_SIZE) s.tracker!!.fail(DdzSessionReason.JOINED_MID_GAME)
    }

    /** 这一个决定做完(点过了)为 true;还要再等几帧(别家的牌没读稳、手牌认不准)为 false。 */
    private suspend fun decide(ctx: ToolContext, s: Session, view: JjView, table: JjTable): Boolean {
        val tracker = s.tracker ?: return false
        if (view.phase == DdzPhase.Call) {
            if (s.bid || table.handUnsure || table.hand.size != HAND_SIZE) return false
            val level = PhoneDdzTurn.adviseBid(s.advisor, table.handRanks)?.level ?: ConservativeDdzPolicy.callLevel(table.handRanks)
            // 想叫的分不能叫(灰的)就不叫
            val button = view.buttons.getOrNull(level)?.takeIf { level > 0 && it.color == JjColor.Yellow } ?: view.buttons[0]
            think()
            tap(ctx, button.x, JjDdzScreen.TAP_Y)
            s.bid = true
            return true
        }
        if (view.onlyPass) {
            think()
            tap(ctx, view.buttons[0].x, JjDdzScreen.TAP_Y)
            tracker.mine(emptyList())
            return true
        }
        if (tracker.broken == null && (!tracker.ready() || table.handUnsure)) {
            val now = PhoneDdzTurn.clock()
            if (s.settleSince == 0L) s.settleSince = now
            if (now - s.settleSince < SETTLE_MS) return false
            tracker.fail(if (table.handUnsure) DdzSessionReason.UNREADABLE_HAND else "history_not_ready")
        }
        s.settleSince = 0
        val hand = table.handRanks
        if (tracker.broken == null && s.expectedHand?.sorted()?.let { it != hand.sorted() } == true) tracker.fail(DdzSessionReason.HAND_DRIFT)
        val play = if (tracker.broken == null) askDouZero(s, tracker, hand) else null
        if (play == null) return byRules(ctx, view, table)
        think()
        if (play.isEmpty()) {
            if (view.phase != DdzPhase.Follow) {
                tracker.fail(DdzSessionReason.INVALID_ADVISOR_PLAY)
                return byRules(ctx, view, table)
            }
            tap(ctx, view.buttons[0].x, JjDdzScreen.TAP_Y)
            tracker.mine(emptyList())
            return true
        }
        val want = PhoneDdzTurn.pick(hand, play)
        if (want == null || !select(ctx, s, want, table)) {
            tracker.fail(DdzSessionReason.REJECTED_ACTION)
            return byRules(ctx, view, table)
        }
        tap(ctx, view.buttons[2].x, JjDdzScreen.TAP_Y)
        tracker.mine(play)
        s.expectedHand = hand.toMutableList().apply { play.forEach { remove(it) } }
        return true
    }

    /**
     * 记不下去的这一手按简单规则打完:跟牌不出;先出出最小的一张单牌(手牌从大到小排,最右边那张)。
     * 先出时提示是灰的,点了没用。抬着的牌先放下。
     */
    private suspend fun byRules(ctx: ToolContext, view: JjView, table: JjTable): Boolean {
        think()
        if (view.phase == DdzPhase.Follow) {
            tap(ctx, view.buttons[0].x, JjDdzScreen.TAP_Y)
            return true
        }
        val smallest = table.hand.lastIndex
        for (i in table.hand.indices) if ((i == smallest) != (i in table.lifted)) {
            tap(ctx, JjCards.tapX(table, i), JjCards.HAND_TAP_Y)
            delay(TAP_GAP_MS)
        }
        tap(ctx, view.buttons[2].x, JjDdzScreen.TAP_Y)
        return true
    }

    /** 把要出的几张点起来、别的放下,再认一遍核对;对不上再改一次,还不对就算失败。 */
    private suspend fun select(ctx: ToolContext, s: Session, want: Set<Int>, table: JjTable): Boolean {
        var current = table
        repeat(2) {
            val toggle = current.hand.indices.filter { (it in want) != (it in current.lifted) }
            if (toggle.isEmpty()) return true
            for (i in toggle) {
                tap(ctx, JjCards.tapX(current, i), JjCards.HAND_TAP_Y)
                delay(TAP_GAP_MS)
            }
            val jpeg = ctx.screen.quickShot() ?: return false
            val cards = detect(s.advisor, jpeg) ?: return false
            val frame = ctx.screen.rawFrame(JjDdzScreen.ROWS) ?: return false
            current = JjCards.table(cards, frame.width)
            if (current.hand.size != table.hand.size) return false
        }
        return current.lifted == want
    }

    private suspend fun askDouZero(s: Session, tracker: DdzTracker, hand: List<String>): List<String>? {
        val body = buildJsonObject {
            put("seat", tracker.seat())
            put("hand", JsonArray(hand.map(::JsonPrimitive)))
            put("bottom", JsonArray(tracker.bottom.orEmpty().map(::JsonPrimitive)))
            put("history", JsonArray(tracker.history.map { m -> JsonArray(m.map(::JsonPrimitive)) }))
        }.toString()
        val reply = PhoneDdzTurn.advise(s.advisor, body)
            ?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        if (reply == null) {
            tracker.fail(DdzSessionReason.SERVICE_UNAVAILABLE)
            return null
        }
        (reply["error"] as? JsonPrimitive)?.contentOrNull?.let { tracker.fail("douzero_$it"); return null }
        return (reply["play"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: null.also { tracker.fail("douzero_bad_reply") }
    }

    private suspend fun tap(ctx: ToolContext, x: Int, y: Int) {
        ctx.screen.quickTap(x, y)
    }

    private fun pause(s: Session, reason: String): ToolExecution {
        s.state = DdzSessionState.Paused
        s.reason = reason
        return result(s)
    }

    private fun result(s: Session) = ToolExecution(buildJsonObject {
        put("ok", s.state != DdzSessionState.Paused)
        put("game", "jj")
        put("mode", DdzMode.Human.wire)
        put("state", s.state.wire)
        s.reason?.let { put("reason", it) }
        put("phase", s.lastPhase.name.lowercase())
        put("handCount", s.handCount)
        put("handsPlayed", s.handsPlayed)
        s.tracker?.broken?.let { put("douzeroOff", it) }
    })

    private fun refused(error: String) = ToolExecution(buildJsonObject {
        put("ok", false)
        put("game", "jj")
        put("error", error)
    })
}
