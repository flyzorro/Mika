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
        /**
     * 没有手牌这么久:多半是终局页或没见过的弹窗,停下交还模型。锦标赛轮与轮之间的「比赛进度」画面
     * 要等别桌打完,真机 11:32 超过 90 秒,工具停了、模型又没认出来,这一轮差点没人打;放宽到 5 分钟。
     */
    private const val IDLE_MS = 300_000L
    /** 轮到自己时别家的牌还没读稳,最多再等这么久(倒计时 15 秒)。 */
    private const val SETTLE_MS = 4_000L
    private const val TAP_GAP_MS = 120L
    /** 抬牌动画约 200ms:点完隔这么久再认。 */
    private const val LIFT_MS = 200L
    private const val SETTLE_READS = 4
    private const val SELECT_ROUNDS = 4
    /** 等「出牌」变绿最多约 1 秒。 */
    private const val PLAYABLE_READS = 7
    private const val THOUGHT_MS = 1_500L
    private const val HOLD_MS = 150L
    /** 一轮截屏 + 认牌超过这么久就打日志。 */
    private const val SLOW_FRAME_MS = 3_000L
    /** 别家出的牌分数不到 SURE、但连着这么久点数都一样,就算认准。 */
    private const val STEADY_MS = 1_500L
    /** 低于这个分数的框不算,怎么稳也不认。 */
    private const val STEADY_MIN_SCORE = 0.6
    private const val TRICK_PLAY_FRAMES = 3
    private const val TRICK_PASS_FRAMES = 2
    /** 炸弹、火箭的特效会挡住人物一两帧。 */
    private const val LORD_FRAMES = 5
    private const val ME_LORD_FRAMES = 12
    private const val AGAIN_GAP_MS = 5_000L
    private const val MAX_HANDS = 10
    private const val HAND_SIZE = 17

    internal class OpponentTrick(val seat: DdzSeat, val historySize: Int, val cards: List<String>) {
        var cardFrames = 0
        var cleared = false
        var sawOtherBeforePass = false
        var passFrames = 0
    }

    internal class Session(val advisor: String, var handsLeft: Int) {
        var tracker: DdzTracker? = null
        var opponentTrick: OpponentTrick? = null
        var previousRawTurn: DdzSeat? = null
        var state = DdzSessionState.Idle
        var reason: String? = null
        var stopped = false
        var handsPlayed = 0
        /** DouZero 出完这一手后手里该剩的牌;下一手认到的不一样就是有张牌认错、点错了。 */
        var expectedHand: List<String>? = null
        var bid = false
        /** 叫分时的手牌:都不叫游戏会重新发牌,再轮到叫分时手牌不一样就是新发的,要重新叫。 */
        var bidHand: List<String>? = null
        /** 这一个决定已经点过了:按钮还会留几帧,等它消失前不再点。 */
        var acted = false
        var noHand = 0
        /** 最近一次读稳的手牌;一手被当成打完时连同记牌一起留着,特效挡住手牌后原样回来就接着记(见 drive)。 */
        var lastRanks: List<String>? = null
        var closed: DdzTracker? = null
        /** 左右两家认不准的那手:点数和第一次看到的时刻;连着 [STEADY_MS] 一样就算认准(见 steady)。 */
        val unsure = mutableMapOf<DdzSide, Pair<List<String>, Long>>()
        var closedHand: List<String>? = null
        var lastHandAt = 0L
        var settleSince = 0L
        var detectFailures = 0
        /** 出牌时从人物衣服看出的地主,和连着几帧都是它;读稳了才拿来核对记牌认的地主。 */
        var lordSeen: DdzSeat? = null
        var lordFrames = 0
        /** 这一手记牌断掉的原因已经打过日志了。 */
        var brokenLogged = false
        /** 上次点「再来一局」的时刻:点完页面要一两秒才走,别连着点。 */
        var againAt = 0L
        var lastPhase = DdzPhase.Other
        var handCount = 0
    }

    /**
     * 出牌前想一会儿(0.8–2 秒):每次都秒点太像机器。叫分不另外想:问 /bid 本身就要 3 秒,
     * 2026-11-21 真机上工具接手晚,再加 1.5–4 秒就超时了,游戏替我不叫还进了托管。测试里换掉。
     */
    internal var think: suspend () -> Unit = { delay(Random.nextLong(800, 2_000)) }

    /** 诊断:每个决定和记牌断掉的原因打到 logcat(JJTRACE),真机上查为什么没压、没出。 */
    private fun trace(s: Session, msg: String) = println("JJTRACE ${s.tracker?.broken ?: "ok"} $msg")

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
                s.resetTurnReads()
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
            val t0 = PhoneDdzTurn.clock()
            val frame = ctx.screen.rawFrame(JjDdzScreen.ROWS)
            val t1 = PhoneDdzTurn.clock()
            val jpeg = frame?.let { ctx.screen.quickShot() }
            val t2 = PhoneDdzTurn.clock()
            val cards = jpeg?.let { detect(s.advisor, it) }
            val t3 = PhoneDdzTurn.clock()
            // 真机 15:13:23–37:14 秒没有一张截图到电脑,工具一步没动,超时进了托管;当时分不清卡在截屏、网络还是电脑。
            // 慢的一轮和失败的一轮都打出来,各步用了多久
            if (t3 - t0 > SLOW_FRAME_MS || frame == null || cards == null) {
                s.resetTurnReads()
                trace(s, "frame raw=${t1 - t0}ms${if (frame == null) "(null)" else ""} shot=${t2 - t1}ms${if (frame != null && jpeg == null) "(null)" else ""} " +
                    "cards=${t3 - t2}ms${if (jpeg != null && cards == null) "(null)" else ""} fails=${s.detectFailures + if (cards == null) 1 else 0}")
            }
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
            // 托管(超时后游戏替我打):先把它取消,不然按钮都不出来。「取消托管」盖在手牌上,看不到手牌就不点:
            // 斗地主大厅泳池的池水在同一处也是这种蓝
            if (view.phase == DdzPhase.AutoPlay && table.hand.isNotEmpty()) {
                s.resetTurnReads()
                if (PhoneDdzTurn.clock() - s.againAt > AGAIN_GAP_MS) {
                    trace(s, "cancel_auto")
                    tap(ctx, view.buttons[0].x, JjDdzScreen.AUTO_TAP_Y)
                    s.againAt = PhoneDdzTurn.clock()
                }
                waitOrSteer { delay(POLL_MS) } ?: return result(s)
                continue
            }
            if (table.hand.isEmpty()) {
                s.resetTurnReads()
                if (++s.noHand == NEW_HAND_GAP && s.tracker != null) {
                    // 一手打完。记牌先留着:王炸、炸弹的特效会挡住手牌好几帧,手牌原样回来就不是新的一手
                    s.closed = s.tracker
                    s.closedHand = s.expectedHand ?: s.lastRanks
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
                val back = s.closed?.takeIf {
                    table.hand.size != HAND_SIZE && !table.handUnsure && table.handRanks.sorted() == s.closedHand?.sorted()
                }
                // 牌是一张张发下来的:没到 17 张、也还没轮到我出牌,就是还在发牌,等发完再从头记
                if (back == null && table.hand.size != HAND_SIZE && view.phase != DdzPhase.Lead && view.phase != DdzPhase.Follow) {
                    waitOrSteer { delay(POLL_MS) } ?: return result(s)
                    continue
                }
                if (back != null) {
                    // 真机 15:04:王炸特效挡了手牌 3 秒,当成一手打完、又当成中途进来,按规则先出了单张 9 输掉
                    trace(s, "resume_after_gap hand=${table.handRanks.joinToString(" ")}")
                    s.tracker = back
                    s.handsPlayed--
                    s.handsLeft++
                } else {
                    newHand(s, table, view)
                }
                s.closed = null
                s.closedHand = null
            }
            s.noHand = 0
            if (!table.handUnsure) {
                playedWithoutMe(s, table.handRanks)?.let { trace(s, "played_without_me cards=${it.joinToString(" ")} left=${table.hand.size}") }
                s.lastRanks = table.handRanks
            }
            val tracker = s.tracker!!
            observe(s, table, view)
            // 出牌时(轮到谁出看得出来):左右谁穿红衣谁是地主,都不是就是我
            if (view.phase == DdzPhase.Lead || view.phase == DdzPhase.Follow || view.turn == DdzSeat.Left || view.turn == DdzSeat.Right) {
                val lord = view.redLord ?: DdzSeat.Me
                s.lordFrames = if (lord == s.lordSeen) s.lordFrames + 1 else 1
                s.lordSeen = lord
            }
            if (tracker.broken != null && !s.brokenLogged) {
                s.brokenLogged = true
                trace(s, "broken trace=${tracker.trace.joinToString(" | ")}")
            }
            if (view.phase.mine() && !s.acted) {
                s.state = if (view.phase == DdzPhase.Call) DdzSessionState.Calling else DdzSessionState.Playing
                if (decide(ctx, s, view, table, frame.width)) s.acted = true
            }
            waitOrSteer { delay(POLL_MS) } ?: return result(s)
        }
    }

    private fun DdzPhase.mine() = this == DdzPhase.Call || this == DdzPhase.Lead || this == DdzPhase.Follow

    private fun Session.resetTurnReads() {
        opponentTrick = null
        previousRawTurn = null
    }

    internal fun observe(s: Session, table: JjTable, view: JjView) {
        val tracker = s.tracker!!
        val turn = view.turn
        // 左家后该轮到我。自己的出牌区还空着时，等右家闹钟再亮一帧才认作跳过了我。
        val skipsMe = turn == DdzSeat.Right && tracker.nextOpponent() == DdzSeat.Left && table.mine == DdzSeen.Empty
        val confirmedTurn = if (skipsMe && s.previousRawTurn != turn) null else turn
        s.previousRawTurn = turn
        recoverOpponentTrick(s, tracker, table, turn)
        tracker.observe(
            rawTurn = confirmedTurn,
            read = { side -> steady(s, table, side) },
            bottom = { table.bottom },
            leadingWith20 = view.phase == DdzPhase.Lead && table.hand.size == 20,
            leading = view.phase == DdzPhase.Lead,
            hand = { table.handRanks.takeUnless { table.handUnsure } },
        )
    }

    /**
     * 闹钟可能从出牌人直接回到他自己。只在新牌连着读稳、另一家随后连着亮「不出」、
     * 我也亮了「不出」且手里确定压不住时补整轮。读数冲突或看见另一家的闹钟就交回正常跟踪。
     */
    private fun recoverOpponentTrick(s: Session, tracker: DdzTracker, table: JjTable, turn: DdzSeat?) {
        val seat = tracker.nextOpponent()
        val previous = s.opponentTrick
        s.opponentTrick = null
        if (seat == null || (turn != null && turn != seat) || table.handUnsure) return

        val seen = table.seen(sideOf(seat))
        val old = previous?.takeIf { it.seat == seat && it.historySize == tracker.history.size }
        val trick = when {
            seen is DdzSeen.Cards && tracker.isNewOpponentPlay(seat, seen.ranks) ->
                old?.takeIf { !it.cleared && it.cards == seen.ranks }
                    ?: OpponentTrick(seat, tracker.history.size, seen.ranks)
            seen == DdzSeen.Empty && old != null && old.cardFrames >= TRICK_PLAY_FRAMES && !old.cleared -> old
            else -> return
        }
        if (seen == DdzSeen.Empty) trick.cleared = true else trick.cardFrames++

        val other = if (seat == DdzSeat.Left) DdzSeat.Right else DdzSeat.Left
        when (val otherSeen = table.seen(sideOf(other))) {
            DdzSeen.Unsure -> return
            DdzSeen.Pass -> if (trick.sawOtherBeforePass) trick.passFrames++
            else -> {
                // 他可能真的压了一手。桌上还摆着已记过的牌，可以等随后出现的不出。
                if (otherSeen is DdzSeen.Cards && tracker.isNewOpponentPlay(other, otherSeen.ranks)) return
                trick.sawOtherBeforePass = true
                trick.passFrames = 0
            }
        }
        if (trick.cardFrames >= TRICK_PLAY_FRAMES && trick.passFrames >= TRICK_PASS_FRAMES &&
            table.mine == DdzSeen.Pass && cannotBeat(table.handRanks, trick.cards) &&
            tracker.recordOpponentPlayAndPasses(seat, trick.cards, table.handRanks)
        ) return
        if (!trick.cleared) s.opponentTrick = trick
    }

    /** 只检查能确定要不起的情况。其余牌型走正常跟踪。 */
    private fun cannotBeat(hand: List<String>, cards: List<String>): Boolean {
        val move = DdzSnapshotRecovery.classify(cards) ?: return false
        if (move.kind == DdzSnapshotRecovery.Kind.Rocket) return true
        if ("SJ" in hand && "BJ" in hand) return false
        if (move.kind != DdzSnapshotRecovery.Kind.Bomb && hand.size >= cards.size) return false
        return hand.groupingBy { it }.eachCount().none { (rank, n) ->
            n == 4 && (move.kind != DdzSnapshotRecovery.Kind.Bomb || (DdzCards.ORDER[rank] ?: -1) > move.main)
        }
    }

    /** 看到新发的 17 张从头记;轮到我出牌时才第一次看到手牌(张数不对)是中途进来的,这一手只按规则打完。 */
    private fun newHand(s: Session, table: JjTable, view: JjView) {
        s.tracker = DdzTracker(DdzSettlingPolicy.HUMAN)
        s.resetTurnReads()
        s.brokenLogged = false
        s.lordSeen = null
        s.lordFrames = 0
        s.expectedHand = null
        s.bid = false
        s.bidHand = null
        s.settleSince = 0
        if (table.hand.size != HAND_SIZE) s.tracker!!.fail(DdzSessionReason.JOINED_MID_GAME)
    }

    /** 这一个决定做完(点过了)为 true;还要再等几帧(别家的牌没读稳、手牌认不准)为 false。 */
    private suspend fun decide(ctx: ToolContext, s: Session, view: JjView, table: JjTable, width: Int): Boolean {
        val tracker = s.tracker ?: return false
        if (view.phase == DdzPhase.Call) {
            // 都不叫重新发牌(真机 10:02):手一直没空,还当已经叫过,叫分超时进了托管
            if (s.bid && !table.handUnsure && table.hand.size == HAND_SIZE && table.handRanks.sorted() != s.bidHand) {
                trace(s, "redeal")
                newHand(s, table, view)
            }
            if (s.bid || table.handUnsure || table.hand.size != HAND_SIZE) return false
            val level = PhoneDdzTurn.adviseBid(s.advisor, table.handRanks)?.level ?: ConservativeDdzPolicy.callLevel(table.handRanks)
            // 按位置认几分(漏认一个按钮时按序号会错位:2026-11-21 想叫 3 分点成了不叫);想叫的分不能叫(灰的)就不叫
            val button = view.buttons.firstOrNull { level > 0 && JjDdzScreen.callLevel(it, width) == level && it.color == JjColor.Yellow }
                ?: view.buttons[0]
            trace(s, "call level=$level x=${button.x} buttons=${view.buttons.map { "${JjDdzScreen.callLevel(it, width)}${it.color.name.first()}" }}")
            tap(ctx, button.x, JjDdzScreen.TAP_Y)
            s.bid = true
            s.bidHand = table.handRanks.sorted()
            return true
        }
        if (view.onlyPass) {
            // 别家的牌还没读稳就记「我不出」,记牌会以为还没轮到我而断掉(真机第二手:not_my_turn);先等一会儿
            if (tracker.broken == null && !tracker.ready() && !settling(s)) return false
            s.settleSince = 0
            trace(s, "only_pass ready=${tracker.ready()}")
            think()
            tap(ctx, view.buttons[0].x, JjDdzScreen.TAP_Y)
            if (tracker.ready()) tracker.mine(emptyList())
            return true
        }
        if (tracker.broken == null && (!tracker.ready() || table.handUnsure)) {
            if (!settling(s)) return false
            tracker.fail(if (table.handUnsure) DdzSessionReason.UNREADABLE_HAND else "history_not_ready")
        }
        s.settleSince = 0
        val hand = table.handRanks
        if (tracker.broken == null && s.expectedHand?.sorted()?.let { it != hand.sorted() } == true) tracker.fail(DdzSessionReason.HAND_DRIFT)
        // 记牌认的地主和衣服对不上(真机 19:02:两家几乎同时亮出长顺子,认成了先出的是左家,一直在压队友):不能再问 DouZero
        // 「我是地主」是推出来的(左右都没红衣):特效挡住人物时也会这样,要看得更久
        val lord = s.lordSeen?.takeIf { s.lordFrames >= if (it == DdzSeat.Me) ME_LORD_FRAMES else LORD_FRAMES }
        if (tracker.broken == null && lord != null && tracker.landlord != null && tracker.landlord != lord) {
            trace(s, "landlord_mismatch tracker=${tracker.landlord} costume=$lord")
            tracker.fail("landlord_mismatch")
        }
        val asked = PhoneDdzTurn.clock()
        val play = if (tracker.broken == null) askDouZero(s, tracker, hand) else null
        trace(s, "${view.phase} hand=${hand.joinToString(" ")} history=${tracker.history.joinToString("/") { it.joinToString(" ") }} seat=${runCatching { tracker.seat() }.getOrNull()} play=$play")
        if (play == null) return byRules(ctx, s, view, table)
        // DouZero 自己想了够久(现场模拟最多 2.5 秒)就不再另外等,倒计时只有 15 秒
        if (PhoneDdzTurn.clock() - asked < THOUGHT_MS) think()
        if (play.isEmpty()) {
            if (view.phase != DdzPhase.Follow) {
                tracker.fail(DdzSessionReason.INVALID_ADVISOR_PLAY)
                return byRules(ctx, s, view, table)
            }
            tap(ctx, view.buttons[0].x, JjDdzScreen.TAP_Y)
            tracker.mine(emptyList())
            return true
        }
        val want = PhoneDdzTurn.pick(hand, play)
        val playX = if (want != null && select(ctx, s, want, table)) playable(ctx) else null
        if (playX == null) {
            trace(s, "select_failed want=$want")
            tracker.fail(DdzSessionReason.REJECTED_ACTION)
            return byRules(ctx, s, view, table)
        }
        tap(ctx, playX, JjDdzScreen.TAP_Y)
        tracker.mine(play)
        s.expectedHand = hand.toMutableList().apply { play.forEach { remove(it) } }
        return true
    }

    /**
     * 记不下去的这一手按简单规则打完:跟牌不出;先出出最小的一张单牌(手牌从大到小排,最右边那张)。
     * 先出时提示是灰的,点了没用。抬着的牌先放下。
     */
    /** 轮到我时别家的牌还没读稳:最多等 [SETTLE_MS];等够了为 true。 */
    private fun settling(s: Session): Boolean {
        val now = PhoneDdzTurn.clock()
        if (s.settleSince == 0L) s.settleSince = now
        return now - s.settleSince >= SETTLE_MS
    }

    private suspend fun byRules(ctx: ToolContext, s: Session, view: JjView, table: JjTable): Boolean {
        think()
        if (view.phase == DdzPhase.Follow) {
            if (beatsByHint(s, table) && view.buttons.size >= 2) {
                // 「提示」把能压的牌抬起来;抬不起来(没有能压的)或出牌没变绿就不出
                tap(ctx, view.buttons[1].x, JjDdzScreen.TAP_Y)
                val lifted = settled(ctx, s)
                val x = if (lifted != null && lifted.lifted.isNotEmpty()) playable(ctx) else null
                trace(s, "rules_hint lifted=${lifted?.lifted?.map { lifted.hand[it].rank }} playable=${x != null}")
                if (x != null) {
                    tap(ctx, x, JjDdzScreen.TAP_Y)
                    return true
                }
            }
            tap(ctx, view.buttons[0].x, JjDdzScreen.TAP_Y)
            return true
        }
        // 先出:有对子或三张先出最小的那组,没有才出最小的单张。记牌断了不知道别家剩几张,
        // 单张最容易被只剩一张的人接走(真机 15:13 地主剩一张,先出了单张 3)。炸弹不拆
        val want = smallestGroup(table) ?: setOf(table.hand.lastIndex)
        val x = if (select(ctx, s, want, table)) playable(ctx) else null
        trace(s, "rules_lead want=${want.map { table.hand[it].rank }} playable=${x != null}")
        tap(ctx, x ?: view.buttons[2].x, JjDdzScreen.TAP_Y)
        return true
    }

    /**
     * 别家出的牌认不准([JjCards.SURE] 以下)时,连着 [STEADY_MS] 点数都一样就当认准。
     * 真机 15:30:地主出小王,认牌一直只有 0.73–0.80,等了 4 秒记牌断掉,手里有大王也一路不出。
     */
    internal fun steady(s: Session, table: JjTable, side: DdzSide): DdzSeen {
        val seen = table.seen(side)
        val found = if (side == DdzSide.Left) table.leftCards else table.rightCards
        if (seen != DdzSeen.Unsure || found.isEmpty() || found.any { it.rank == "PASS" || it.score < STEADY_MIN_SCORE }) {
            s.unsure.remove(side)
            return seen
        }
        val ranks = found.sortedBy { it.cx }.map { it.rank }
        val now = PhoneDdzTurn.clock()
        val first = s.unsure[side]
        if (first == null || first.first != ranks) {
            s.unsure[side] = ranks to now
            return seen
        }
        if (now - first.second < STEADY_MS) return seen
        trace(s, "steady $side ${ranks.joinToString(" ")} scores=${found.sortedBy { it.cx }.map { "%.2f".format(it.score) }}")
        return DdzSeen.Cards(ranks)
    }

    /**
     * 记牌断了、轮到我跟牌:桌上是地主的牌就按游戏的「提示」压,是队友的牌就不出。
     * 最后出牌的是左家(他之后就轮到我);左家不出才看右家。地主按人物的红衣服认(不靠记牌),认不出就不压。
     */
    internal fun beatsByHint(s: Session, table: JjTable): Boolean {
        val lord = s.lordSeen?.takeIf { s.lordFrames >= if (it == DdzSeat.Me) ME_LORD_FRAMES else LORD_FRAMES } ?: return false
        val owner = when {
            table.left is DdzSeen.Cards -> DdzSeat.Left
            table.left == DdzSeen.Pass && table.right is DdzSeen.Cards -> DdzSeat.Right
            else -> return false
        }
        return lord == DdzSeat.Me || owner == lord
    }

    /**
     * 手牌少了几张、却不是工具刚出的那手(和 [Session.expectedHand] 对不上):是游戏替我出的(托管、终局自动出完)。
     * 只用来打日志(真机 16:32 我方剩下的牌每 2 秒出一手,工具没做决定,事后分不清是谁出的)。
     */
    internal fun playedWithoutMe(s: Session, hand: List<String>): List<String>? {
        val before = s.lastRanks ?: return null
        if (hand.size >= before.size || hand.sorted() == s.expectedHand?.sorted()) return null
        val removed = before.toMutableList()
        for (r in hand) if (!removed.remove(r)) return null
        return removed
    }

    /** 手里最小的一组对子或三张(序号);没有为 null。手牌从大到小排,从右往左找。 */
    internal fun smallestGroup(table: JjTable): Set<Int>? {
        val count = table.handRanks.groupingBy { it }.eachCount()
        val rank = table.handRanks.asReversed().firstOrNull { (count[it] ?: 0) in 2..3 } ?: return null
        return table.hand.indices.filterTo(LinkedHashSet()) { table.hand[it].rank == rank }
    }

    /**
     * 把要出的几张点起来、别的放下;等抬牌动画停了再认一遍核对,对不上再改,最多 [SELECT_ROUNDS] 轮。
     * 2026-11-21 真机:点完 120ms 就认,牌还在半空,当成没抬又点一下,把抬起的按了回去(「来回一下」);
     * 游戏还会把同点数的几张一起抬起来(点一张 7,四张 7 都起来),多起来的要放下。
     */
    internal suspend fun select(ctx: ToolContext, s: Session, wanted: Set<Int>, table: JjTable): Boolean {
        val ranks = wanted.map { table.hand[it].rank }
        var current = table
        repeat(SELECT_ROUNDS) {
            // 同点数的牌哪张都行:点一张 A,游戏抬起的可能是旁边那张(真机 11:13 三张 A 出对 A、10:00 三张 2 出对 2,
            // 按序号核对一直对不上,判成出不了,这一手剩下的都不出了)。已经抬起的、点数要的先算上
            val want = target(current, ranks) ?: return false
            // 先把多抬的(游戏自己提示抬起的牌)放下、读稳了再抬要的:一边放一边抬,游戏会退回只剩提示牌(真机 20:04、20:07)
            val drop = current.lifted - want
            val batch = drop.ifEmpty { want - current.lifted }
            if (batch.isEmpty()) return true
            for (i in batch.sorted()) {
                tap(ctx, JjCards.tapX(current, i), JjCards.HAND_TAP_Y)
                delay(TAP_GAP_MS)
            }
            val before = current.lifted
            current = settled(ctx, s) ?: return false
            println("JJTRACE select ${if (drop.isEmpty()) "lift" else "drop"} $batch lifted $before -> ${current.lifted} want $want")
            if (current.hand.size != table.hand.size) return false
        }
        return current.lifted == target(current, ranks)
    }

    /** 要抬起哪几张才是 [ranks]:抬着的点数对的先留着,不够的从左往右补;手里凑不齐为 null。 */
    private fun target(table: JjTable, ranks: List<String>): Set<Int>? {
        val need = ranks.groupingBy { it }.eachCount().toMutableMap()
        val out = LinkedHashSet<Int>()
        fun take(i: Int) {
            val r = table.hand[i].rank
            if ((need[r] ?: 0) > 0) { need[r] = need.getValue(r) - 1; out += i }
        }
        table.lifted.sorted().forEach(::take)
        table.hand.indices.filter { it !in out }.forEach(::take)
        return out.takeIf { need.values.all { it == 0 } }
    }

    /** 隔一会儿认一次,连着两次抬起的牌一样(动画停了)才算数;一直在变就用最后一次。 */
    private suspend fun settled(ctx: ToolContext, s: Session): JjTable? {
        var last: JjTable? = null
        repeat(SETTLE_READS) {
            delay(LIFT_MS)
            val jpeg = ctx.screen.quickShot() ?: return null
            val cards = detect(s.advisor, jpeg) ?: return null
            val frame = ctx.screen.rawFrame(JjDdzScreen.ROWS) ?: return null
            val now = JjCards.table(cards, frame.width)
            if (last?.lifted == now.lifted && last?.hand?.size == now.hand.size) return now
            last = now
        }
        return last
    }

    /** 选好牌后「出牌」要变绿才按得动(游戏判过牌型、压得住);等一会儿,一直不绿就是这几张出不了。 */
    private suspend fun playable(ctx: ToolContext): Int? {
        repeat(PLAYABLE_READS) {
            val view = ctx.screen.rawFrame(JjDdzScreen.ROWS)?.let(JjDdzScreen::read)
            val play = view?.buttons?.getOrNull(2)
            if (view != null && view.buttons.size == 3 && play?.color == JjColor.Green) return play.x
            delay(POLL_MS)
        }
        return null
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

    /** JJ 的牌局是 Unity,在 Mika 的屏上帧率低:按住 [HOLD_MS] 再抬,50ms 的点按会漏(2026-11-21 19:13 点牌全没反应)。 */
    private suspend fun tap(ctx: ToolContext, x: Int, y: Int) {
        if (!ctx.screen.pressAt(x, y, HOLD_MS)) println("JJTRACE tap_failed x=$x y=$y")
    }

    private fun pause(s: Session, reason: String): ToolExecution {
        // 停下也打一行:电脑上看 logcat 的监视才知道工具交还了模型
        trace(s, "pause reason=$reason hands=${s.handsPlayed}")
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
