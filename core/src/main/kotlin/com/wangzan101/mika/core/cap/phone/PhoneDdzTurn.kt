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
    /** Mika 屏上大厅右下角的快速开始(单机经典场,每局押金币)。 */
    private const val QUICK_START_X = 2140
    private const val QUICK_START_Y = 995
    /** Mika 屏上闯关页的报名。 */
    private const val STAGE_BUTTON_Y = 920
    private const val AUTO_CANCEL_MS = 600L
    /** 轮到自己时别家的牌还没落下、或动画盖着手牌,最多再等几帧(约 3 秒,倒计时有 15 秒)。 */
    private const val SETTLE_POLLS = 16
    private const val NO_HAND_POLLS = 60
    private const val NEW_HAND_GAP = 8
    private const val LIFT_POLLS = 4
    /** 点了结算的继续,等它关掉、开始发牌。 */
    private const val CONTINUE_MS = 1500L

    /**
     * 叫几分:大王 3、小王 2、每个 2 算 1、每个炸弹 2、每两个 A 算 1;7 分以上叫三分,5 分以上叫二分,不然不叫。
     * 闯关里输了扣分、低于 3 分淘汰,牌一般就不当地主。
     */
    internal fun bidLevel(hand: List<String>): Int = ConservativeDdzPolicy.callLevel(hand)
    private const val TAP_GAP_MS = 60L
    private const val SETTLE_MS = 250L
    private const val VERDICT_MS = 800L
    /** 这一手过了这么久就不再问模型,直接保底,免得倒计时走完。 */
    private const val RETRY_BUDGET_MS = 8_000L

    /**
     * [single]:这一局是确认过的单机局(见 [markSingle]),只有它为真才让 DouZero 出牌,真人局不用。
     * [advisor]:DouZero 服务地址。[tracker]:这一局的出牌历史;记不下去了这一局剩下的交还模型。
     */
    private class Turn {
        var seenAt: Long? = null
        var rejected = 0
        var single = false
        var mode: DdzMode? = null
        var state = DdzSessionState.Idle
        var reason: String? = null
        var stopped = false
        var snapshot: DdzSnapshot? = null
        /** start 之后进过牌桌没有:刚点完快速开始那几帧还是大厅,不能当成回了大厅。 */
        var atTable = false
        /** 上次交还模型时动作清单记到第几个;再来时多了,就是模型用别的工具动过屏幕。 */
        var actionsSeen = -1L
        /** 轮到自己时别家的牌还没落下,又等了几帧。 */
        var settling = 0
        /** DouZero 出完这一手后手里该剩的牌;下一手认到的不一样,就是有张牌认错、点到了别的牌。 */
        var expectedHand: List<String>? = null
        /** 刚点了继续闯关,还没看到这一手的手牌。 */
        var joining = false
        /** 连着几帧没有手牌:一手打完了,下一次看到手牌就是新发的一手。 */
        var noHand = 0
        /** 这次还要连着打几手(闯关赢了自己点继续);赢了几手。 */
        var handsLeft = 1
        var won = 0
        var lost = 0
        /** 单机经典场这一轮打完了几手(经典场的结算分不出输赢)。 */
        var played = 0
        /** start 刚在结算上点了继续,结算还没退。 */
        var leaving = false
        /** 这一轮见过闯关的牌桌:之后不是牌桌的画面不认。 */
        var stage = false
        /** 这一轮叫分已经按过了:按完按钮还会留几帧。 */
        var bid = false
        /** 这一轮已经点了不加倍。 */
        var declined = false
        var advisor: String? = null
        var tracker: DdzTracker? = null
        val decisions = mutableListOf<Long>()
    }

    /** 记下这是单机局、DouZero 在哪;回到大厅或换了 App 由调用方清掉。 */
    internal fun markSingle(ctx: ToolContext, advisor: String) {
        val turn = ctx[STATE] ?: Turn().also { ctx[STATE] = it }
        turn.single = true
        turn.mode = DdzMode.Single
        turn.state = DdzSessionState.Playing
        turn.reason = null
        turn.stopped = false
        turn.snapshot = null
        turn.atTable = true
        turn.advisor = advisor
        turn.actionsSeen = ctx.evidence.actions
    }

    /** 测试用:换上一段已经记好的出牌历史,[expectedHand] 是上一手出完后手里该剩的牌。 */
    internal fun useTracker(ctx: ToolContext, tracker: DdzTracker, expectedHand: List<String>? = null) {
        val turn = ctx[STATE] ?: Turn().also { ctx[STATE] = it }
        turn.tracker = tracker
        turn.expectedHand = expectedHand
    }

    /** 问 DouZero;测试里换掉。返回响应正文,连不上为 null。 */
    internal var advise: suspend (url: String, body: String) -> String? = { url, body -> DdzAdvisor.post(url, body) }

    /** 问 DouZero 服务这手牌叫几分(它自己跟自己打几十局看当地主的赢面);测试里换掉。连不上为 null。 */
    internal var adviseBid: suspend (url: String, hand: List<String>) -> Int? = { url, hand ->
        DdzAdvisor.post(url, buildJsonObject { put("hand", JsonArray(hand.map(::JsonPrimitive))) }.toString(), "bid")
            ?.let { runCatching { (kotlinx.serialization.json.Json.parseToJsonElement(it) as? JsonObject)?.get("level") as? JsonPrimitive }.getOrNull() }
            ?.contentOrNull?.toIntOrNull()?.takeIf { it in 0..3 }
    }

    override val verb = Verb.PhoneGame
    override val risk = Risk.Write
    override val def = ToolDef("phone_ddz_turn", Prompts.tool("phone_ddz_turn"), mapOf(
        "action" to buildJsonObject {
            put("type", "string")
            put("enum", JsonArray(listOf("start", "play", "pass", "hint", "call", "again", "wait", "stop", "resume").map(::JsonPrimitive)))
        },
        "mode" to buildJsonObject {
            put("type", "string")
            put("enum", JsonArray(listOf("single", "human").map(::JsonPrimitive)))
            put("description", "start: explicit session mode; omitted keeps the existing single-player default.")
        },
        "advisor" to buildJsonObject { put("type", "string"); put("description", "start: DouZero service URL, required for both modes.") },
        "hand" to ranks("play: your whole hand; resume: the complete current hand."),
        "play" to ranks("The cards to play."),
        "bottom" to ranks("resume: the three visible bottom cards."),
        "history" to buildJsonObject {
            put("type", "array")
            put("description", "resume: visible plays in order; an empty array is a pass.")
            put("items", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }) })
        },
        "phase" to buildJsonObject { put("type", "string"); put("description", "resume: call, double, lead, or follow.") },
        "seat" to buildJsonObject { put("type", "string"); put("description", "resume: landlord, landlord_up, or landlord_down.") },
        "snapshot" to buildJsonObject { put("type", "object"); put("description", "resume: alternatively provide hand, bottom, history, phase, and seat inside this object.") },
        "level" to buildJsonObject { put("type", "integer"); put("description", "call: 0-3.") },
        "hands" to buildJsonObject { put("type", "integer"); put("description", "start: how many 闯关 hands to play in a row before returning, 1-10 (default 1).") },
    ), listOf("action"))

    private fun ranks(text: String) = buildJsonObject {
        put("type", "array"); put("description", text)
        put("items", buildJsonObject { put("type", "string") })
    }

    /** 工具列表在任务开始时就定了,那时游戏还没打开,所以这里只看主模型能不能看图;是不是这款游戏在执行时查。 */
    override fun available(ctx: ToolContext) = ctx.config.visionEnabled

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val turn = ctx[STATE] ?: Turn().also { ctx[STATE] = it }
        val action = a.str("action") ?: throw ArgError("action", "required")
        // stop is deliberately usable even after the app was switched: it must prevent a delayed
        // polling continuation from tapping when the user takes control.
        if (action == "stop") return stop(ctx, turn)
        if (ctx.screen.peek().packageName != PACKAGE) {
            turn.single = false
            if (turn.mode == DdzMode.Human) {
                turn.mode = null
                turn.state = DdzSessionState.Paused
                turn.reason = DdzSessionReason.UNKNOWN_SCENE
            }
            return refused("wrong_app")
        }
        if (turn.stopped && action != "start") return stopped(turn)
        // 模型在两次调用之间自己点过屏幕(关弹窗,也可能回大厅进了真人场):这里看不到它去过哪,单机标记作废,要再 start
        if (turn.single && ctx.evidence.actions != turn.actionsSeen) turn.single = false
        if (action == "start") return start(a, ctx, turn).also { turn.actionsSeen = ctx.evidence.actions }
        if (action == "resume") return resume(a, ctx, turn)
        if (turn.state == DdzSessionState.Paused) return paused(turn)
        val frame = ctx.screen.rawFrame(if (turn.advisor != null) FULL_ROWS else DdzScreen.ROWS) ?: return refused("no_frame")
        // 闯关牌桌只在单机模式里有:模型关了弹窗(广告)回到这桌上,还是单机,不用再 start;打了一半的这手照旧交给模型
        if (!turn.single && turn.advisor != null && DdzCards.singleStageTable(frame)) {
            markSingle(ctx, turn.advisor!!)
            turn.tracker = null
            turn.expectedHand = null
            turn.joining = true
        }
        val view = takeBackControl(DdzScreen.read(frame), ctx).also { dealt(turn, it) }
        val thinkMs = turn.seenAt?.let { now() - it } ?: 0
        val tapStart = now()
        val move = move(action, a, view, ctx, turn, thinkMs)
        val tapMs = now() - tapStart
        val waitStart = now()
        val next = when {
            move.moved || action == "wait" -> awaitDecision(ctx, turn, view.phase, move.moved, if (action == "again") AGAIN_POLLS else MAX_POLLS)
            else -> read(ctx) ?: view
        }
        val waitMs = now() - waitStart
        if (move.moved) turn.rejected = 0
        if (turn.mode == DdzMode.Human && move.error != null) {
            turn.state = DdzSessionState.Paused
            turn.reason = pauseReason(move.error)
        } else if (turn.mode == DdzMode.Human && next.phase == DdzPhase.Other) {
            turn.state = DdzSessionState.Paused
            turn.reason = DdzSessionReason.UNKNOWN_SCENE
        } else if (turn.state != DdzSessionState.Paused) {
            turn.state = stateFor(next.phase)
            turn.reason = null
        }
        val shotStart = now()
        val needsLook = next.phase !in setOf(DdzPhase.Result, DdzPhase.AutoPlay) && ctx.config.visionEnabled
        val shot = if (needsLook) ctx.screen.quickShot() else null
        val shotMs = now() - shotStart
        turn.seenAt = now()
        turn.actionsSeen = ctx.evidence.actions
        return ToolExecution(buildJsonObject {
            put("ok", move.error == null)
            turn.mode?.let { put("mode", it.wire) }
            put("state", turn.state.wire)
            turn.reason?.let { put("reason", it) }
            move.error?.let { put("error", it); put("recoverable", true) }
            move.note?.let { put("note", it) }
            put("phase", next.phase.name.lowercase())
            put("handCount", next.handCount)
            if (turn.won > 0) put("handsWon", turn.won)
            if (turn.lost > 0) put("handsLost", turn.lost)
            if (turn.played > 0) put("handsPlayed", turn.played)
            if (turn.decisions.isNotEmpty()) {
                // DouZero 这次替模型打了几手、每手从轮到自己到牌出去花了多久
                put("autoPlayed", turn.decisions.size)
                put("autoMs", JsonArray(turn.decisions.map(::JsonPrimitive)))
                turn.decisions.clear()
            }
            turn.tracker?.let { t -> t.broken?.let { put("douzeroOff", it); put("douzeroTrace", t.trace.joinToString(" ")) } }
            put("timing", buildJsonObject {
                put("thinkMs", thinkMs); put("tapMs", tapMs); put("waitMs", waitMs); put("shotMs", shotMs)
            })
        }, image = shot, frame = shot, changed = move.moved)
    }

    private class Move(val moved: Boolean, val error: String? = null, val note: String? = null)

    /**
     * 只在确认是单机的入口点开始,并记下这是单机局;从这一局起 DouZero 替模型出牌。入口有两个:大厅里标着「单机模式」的
     * 快速开始(要金币),和单机模式里的闯关页的报名(首次免费,过关奖励金币)。
     */
    private suspend fun start(a: Args, ctx: ToolContext, turn: Turn): ToolExecution {
        val advisor = a.str("advisor") ?: throw ArgError("advisor", "required")
        val mode = a.str("mode")?.trim()?.lowercase() ?: DdzMode.Single.wire
        return when (mode) {
            DdzMode.Human.wire -> startHuman(ctx, turn, advisor)
            DdzMode.Single.wire -> startSingle(a, ctx, turn, advisor)
            else -> throw ArgError("mode", "must be single or human")
        }
    }

    private suspend fun startHuman(ctx: ToolContext, turn: Turn, advisor: String): ToolExecution {
        val page = ctx.screen.rawFrame(FULL_ROWS) ?: return refused("no_frame")
        val view = DdzScreen.read(page)
        if (view.phase == DdzPhase.Other) return refused("not_human_game")
        turn.single = false
        turn.mode = DdzMode.Human
        turn.state = stateFor(view.phase)
        turn.reason = null
        turn.stopped = false
        turn.advisor = advisor
        turn.atTable = true
        turn.actionsSeen = ctx.evidence.actions
        turn.tracker = null
        turn.expectedHand = null
        turn.snapshot = null
        return ToolExecution(buildJsonObject {
            put("ok", true)
            put("mode", DdzMode.Human.wire)
            put("state", turn.state.wire)
            put("phase", view.phase.name.lowercase())
            put("handCount", view.handCount)
        }, image = if (ctx.config.visionEnabled) ctx.screen.quickShot() else null, changed = false)
    }

    private suspend fun startSingle(a: Args, ctx: ToolContext, turn: Turn, advisor: String): ToolExecution {
        val page = ctx.screen.rawFrame(FULL_ROWS) ?: return refused("no_frame")
        // 已经坐在闯关的牌桌上(模型自己点了下一关、继续闯关):从这里接,下面按头一帧的手牌张数看是不是打了一半
        val seated = DdzCards.singleStageTable(page)
        val stage = if (DdzCards.singleStage(page)) DdzCards.stageButton(page) ?: return refused("no_stage_button") else null
        // 闯关一手打完的结算(赢了或输了):点黄色的继续,发下一手
        val result = if (DdzCards.stageWin(page) || DdzCards.stageLoss(page)) DdzCards.stageContinue(page) else null
        val tap = when {
            result != null -> result
            seated -> null
            // 单机大厅的快速开始:经典场,每局押金币;金币不够弹的救济金交给模型(只点免费的直接领取)
            DdzCards.singleLobby(page) -> QUICK_START_X to QUICK_START_Y
            stage != null -> stage.first to STAGE_BUTTON_Y
            else -> return refused("not_single_player_lobby")
        }
        if (tap != null && !ctx.screen.quickTap(tap.first, tap.second)) return refused("tap_failed")
        // 结算点了继续,画面还要一会儿才退:退之前还看得到的结算是这一手的,不再算
        turn.leaving = result != null
        markSingle(ctx, advisor)
        // 上一手剩下的牌不算数:不清掉,新发的一手会被当成手牌对不上
        turn.expectedHand = null
        turn.handsLeft = a.int("hands", 1, 10) ?: 1
        turn.won = 0
        turn.lost = 0
        turn.played = 0
        turn.stage = seated || result != null
        turn.atTable = seated
        turn.tracker = null
        // 继续闯关、或者已经坐在桌上,可能是打了一半的一手:头一帧看手牌张数再定
        turn.joining = seated || stage?.second == true
        // 叫分、别家出牌都可能要等一阵:和 wait 一样等;不在牌桌上的话十来秒没手牌就回来
        val next = awaitDecision(ctx, turn, DdzPhase.Other, moved = true, polls = MAX_POLLS)
        turn.state = stateFor(next.phase)
        turn.seenAt = now()
        return ToolExecution(buildJsonObject {
            put("ok", true)
            turn.mode?.let { put("mode", it.wire) }
            put("state", turn.state.wire)
            turn.reason?.let { put("reason", it) }
            put("phase", next.phase.name.lowercase())
            put("handCount", next.handCount)
            if (turn.won > 0) put("handsWon", turn.won)
            if (turn.lost > 0) put("handsLost", turn.lost)
            if (turn.played > 0) put("handsPlayed", turn.played)
            turn.tracker?.let { t -> t.broken?.let { put("douzeroOff", it); put("douzeroTrace", t.trace.joinToString(" ")) } }
        }, image = if (ctx.config.visionEnabled) ctx.screen.quickShot() else null, changed = true)
    }

    private fun stop(ctx: ToolContext, turn: Turn): ToolExecution {
        turn.single = false
        turn.stopped = true
        turn.state = DdzSessionState.Stopped
        turn.reason = DdzSessionReason.USER_STOP
        turn.atTable = false
        turn.tracker = null
        turn.expectedHand = null
        turn.snapshot = null
        turn.advisor = null
        turn.decisions.clear()
        turn.actionsSeen = ctx.evidence.actions
        return ToolExecution(buildJsonObject {
            put("ok", true)
            turn.mode?.let { put("mode", it.wire) }
            put("state", turn.state.wire)
            put("reason", turn.reason!!)
            put("phase", DdzPhase.Other.name.lowercase())
        })
    }

    private fun stopped(turn: Turn) = ToolExecution(buildJsonObject {
        put("ok", false)
        put("error", "stopped")
        put("recoverable", true)
        turn.mode?.let { put("mode", it.wire) }
        put("state", DdzSessionState.Stopped.wire)
        put("reason", turn.reason ?: DdzSessionReason.USER_STOP)
    })

    private fun paused(turn: Turn) = ToolExecution(buildJsonObject {
        put("ok", false)
        put("error", "paused")
        put("recoverable", true)
        turn.mode?.let { put("mode", it.wire) }
        put("state", DdzSessionState.Paused.wire)
        turn.reason?.let { put("reason", it) }
    })

    private fun resume(a: Args, ctx: ToolContext, turn: Turn): ToolExecution {
        if (turn.mode != DdzMode.Human) return refused("resume_requires_human")
        val snapshot = parseSnapshot(a)
        turn.snapshot = snapshot
        turn.stopped = false
        turn.state = stateFor(snapshot.phase)
        turn.reason = null
        turn.atTable = true
        turn.actionsSeen = ctx.evidence.actions
        return ToolExecution(buildJsonObject {
            put("ok", true)
            put("mode", DdzMode.Human.wire)
            put("state", turn.state.wire)
            put("phase", snapshot.phase)
            put("seat", snapshot.seat)
            put("handCount", snapshot.hand.size)
            put("historyCount", snapshot.history.size)
        })
    }

    private fun parseSnapshot(a: Args): DdzSnapshot {
        val source = when (val value = a.o["snapshot"]) {
            null -> a.o
            is JsonObject -> value
            else -> throw ArgError("snapshot", "must be an object")
        }
        fun required(name: String): kotlinx.serialization.json.JsonElement =
            source[name] ?: throw ArgError(name, "required for resume")
        fun strings(name: String, min: Int, max: Int): List<String> {
            val value = required(name) as? JsonArray ?: throw ArgError(name, "must be an array")
            if (value.size !in min..max) throw ArgError(name, "must have $min-$max items")
            return value.map { item ->
                val text = (item as? JsonPrimitive)?.contentOrNull?.trim()?.uppercase()
                    ?: throw ArgError(name, "items must be strings")
                if (text !in SNAPSHOT_RANKS) throw ArgError(name, "contains an invalid rank")
                text
            }
        }
        val hand = strings("hand", 1, 20)
        val bottom = strings("bottom", 3, 3)
        val historyValue = required("history") as? JsonArray ?: throw ArgError("history", "must be an array")
        val history = historyValue.mapIndexed { index, item ->
            val cards = item as? JsonArray ?: throw ArgError("history", "entry $index must be an array")
            cards.map { card ->
                val rank = (card as? JsonPrimitive)?.contentOrNull?.trim()?.uppercase()
                    ?: throw ArgError("history", "entries must contain strings")
                if (rank !in SNAPSHOT_RANKS) throw ArgError("history", "contains an invalid rank")
                rank
            }
        }
        val phase = (required("phase") as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
            ?: throw ArgError("phase", "must be a string")
        if (phase !in SNAPSHOT_PHASES) throw ArgError("phase", "must be call, double, lead, or follow")
        val seat = (required("seat") as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
            ?: throw ArgError("seat", "must be a string")
        if (seat !in SNAPSHOT_SEATS) throw ArgError("seat", "must identify the player's seat")
        return DdzSnapshot(hand, bottom, history, phase, seat)
    }

    private fun stateFor(phase: DdzPhase) = when (phase) {
        DdzPhase.Call -> DdzSessionState.Calling
        DdzPhase.Double -> DdzSessionState.Doubling
        DdzPhase.Follow, DdzPhase.Lead -> DdzSessionState.Playing
        DdzPhase.Result -> DdzSessionState.Result
        DdzPhase.Other, DdzPhase.AutoPlay -> DdzSessionState.Lobby
    }

    private fun stateFor(phase: String) = when (phase) {
        "call" -> DdzSessionState.Calling
        "double" -> DdzSessionState.Doubling
        "lead", "follow" -> DdzSessionState.Playing
        else -> DdzSessionState.Paused
    }

    private fun pauseReason(error: String) = when (error) {
        "hand_mismatch", "hand_drift" -> DdzSessionReason.HAND_DRIFT
        "douzero_history_mismatch", "history_not_ready" -> DdzSessionReason.HISTORY_MISMATCH
        "douzero_unreachable" -> DdzSessionReason.SERVICE_UNAVAILABLE
        "rejected", "select_failed", "confirm_failed" -> DdzSessionReason.REJECTED_ACTION
        else -> DdzSessionReason.UNKNOWN_SCENE
    }

    private suspend fun move(action: String, a: Args, view: DdzView, ctx: ToolContext, turn: Turn, thinkMs: Long): Move {
        val phase = view.phase
        return when (action) {
            "wait" -> Move(false)
            "again" -> if (phase == DdzPhase.Result) press(view.buttons[0], ctx) else wrongPhase(phase)
            "call" -> if (phase != DdzPhase.Call) wrongPhase(phase) else {
                val level = a.int("level", 0, 3) ?: 0
                // 灰的是比别人叫得低、点不了的分:按不叫算
                val button = view.buttons[level].takeIf { it.color != DdzColor.Gray } ?: view.buttons[0]
                // 按完叫分按钮还留几帧,单机局里接着等时别再按一次
                press(button, ctx).also { if (it.moved) turn.bid = true }
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
        if (DdzScreen.onlyPass(view)) return press(view.buttons[0], ctx).let { Move(it.moved, it.error, "cannot_beat") }
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
        if (DdzScreen.onlyPass(view)) return press(view.buttons[0], ctx)
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
        // 只剩不出时最后一个按钮就是不出,不能当出牌按
        if (DdzScreen.onlyPass(view)) return false
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
            // 抬牌有动画:还没抬到位就再点一下,会把它点回去。多看几帧,真没抬起来才重点
            repeat(LIFT_POLLS) {
                delay(SETTLE_MS)
                read(ctx)?.takeIf { it.lifted == want }?.let { return it }
            }
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

    /**
     * 等到又该玩家决定;[moved] 刚动过手时,同一阶段要先离开过才算新的一手。托管了就取消。
     * 单机局里每一帧也喂给出牌历史;轮到自己出牌、历史又对得上时不交还模型,由 DouZero 出完接着等。
     */
    private suspend fun awaitDecision(ctx: ToolContext, turn: Turn, from: DdzPhase, moved: Boolean, polls: Int): DdzView {
        var left = !moved
        var last = DdzView(DdzPhase.Other, emptyList(), 0, 0.0, 0, emptySet())
        var budget = polls
        // 一直看不到手牌就不是在牌桌上(大厅、选关页、弹窗),干等等不来:发牌用不了这么久
        var empty = 0
        while (budget-- > 0) {
            val frame = ctx.screen.rawFrame(if (turn.single) FULL_ROWS else DdzScreen.ROWS)
            val view = frame?.let(DdzScreen::read)
            if (view != null) {
                last = view
                // 见过闯关牌桌以后,没有「闯关模式」字样的画面(广告、弹窗)一律不认:广告的按钮和白块会被认成轮到我出、手里一张。
                // 和没手牌一样计数,一直这样就交还模型去关;但不算发新牌的空档
                val table = turn.single && DdzCards.singleStageTable(frame)
                if (table) turn.stage = true
                val offTable = turn.single && turn.stage && !table
                empty = if (offTable || (view.handCount == 0 && view.phase == DdzPhase.Other)) empty + 1 else 0
                if (empty > NO_HAND_POLLS) return if (offTable) view.copy(phase = DdzPhase.Other) else view
                if (view.phase != DdzPhase.Other) turn.atTable = true
                // 进过牌桌又回了大厅:下一局可能进的是真人场,单机标记作废,要再 start 一次
                if (turn.single && turn.atTable && view.phase == DdzPhase.Other && DdzCards.singleLobby(frame)) { turn.single = false; return view }
                // 闯关打完一手(赢或输):还要接着打就自己点黄色的继续(不点蓝色的离开、不点看视频的),马上发下一手;否则交回模型
                val won = turn.single && DdzCards.stageWin(frame)
                val settled = won || (turn.single && DdzCards.stageLoss(frame))
                if (settled && turn.leaving) { waitOrSteer { delay(POLL_MS) } ?: return last; continue }
                turn.leaving = false
                if (settled) {
                    turn.tracker = null
                    turn.expectedHand = null
                    if (turn.handsLeft <= 1) { if (won) turn.won++ else turn.lost++; return view.copy(phase = DdzPhase.Result) }
                    // 「胜利」先出来,「继续」按钮晚一点才画上:没有就再等一帧
                    val next = DdzCards.stageContinue(frame)
                    if (next == null || ctx.screen.peek().packageName != PACKAGE) { waitOrSteer { delay(POLL_MS) } ?: return last; continue }
                    if (won) turn.won++ else turn.lost++
                    turn.handsLeft--
                    ctx.screen.quickTap(next.first, next.second)
                    delay(CONTINUE_MS)
                    empty = 0
                    budget = polls
                    left = true
                    continue
                }
                if (offTable) { waitOrSteer { delay(POLL_MS) } ?: return last; continue }
                if (view.phase != DdzPhase.Call) turn.bid = false
                if (view.phase != DdzPhase.Double) turn.declined = false
                dealt(turn, view)
                if (turn.single) track(turn, frame, view)
                when (view.phase) {
                    // 单机局叫分按牌力定,不等模型(叫分只有十来秒);灰的分数点不了,按不叫算
                    DdzPhase.Call -> when {
                        !turn.single -> if (left || view.phase != from) return view
                        !turn.bid -> {
                            // 叫分按钮出来时牌可能还在发(手里才十几张)、认不全:等下一帧,不交还模型
                            val hand = DdzCards.hand(frame, view)?.takeIf { it.size == 17 }
                            if (hand != null) {
                                // 先问 DouZero 当地主的赢面;服务连不上就按牌力的老规则
                                val level = turn.advisor?.let { adviseBid(it, hand) } ?: bidLevel(hand)
                                val button = view.buttons[level].takeIf { it.color != DdzColor.Gray } ?: view.buttons[0]
                                if (press(button, ctx).moved) turn.bid = true
                            }
                            left = true
                        }
                    }
                    DdzPhase.AutoPlay -> {
                        left = true
                        // 等的时候前台可能换了,点之前再认一次;点了等它退出托管,别连着点
                        if (ctx.screen.peek().packageName == PACKAGE && ctx.screen.quickTap(view.buttons[0].x, view.buttons[0].y)) delay(AUTO_CANCEL_MS)
                    }
                    // 加倍只有约 5 秒,模型来不及:单机局里自己点最左边蓝色的不加倍(加倍要看视频,绝不点);不是单机就让它走完
                    DdzPhase.Double -> {
                        left = true
                        val no = view.buttons.first()
                        if (turn.single && !turn.declined && no.color == DdzColor.Blue && press(no, ctx).moved) turn.declined = true
                    }
                    DdzPhase.Other -> left = true
                    DdzPhase.Result -> {
                        turn.tracker = null
                        turn.expectedHand = null
                        // 单机经典场打完一手:还要接着打就自己点黄色的再来一局(结算上那个宽的,不是救济金里看视频的双倍领取)
                        if (turn.single && !turn.stage && turn.handsLeft > 1 && (left || view.phase != from)) {
                            if (ctx.screen.peek().packageName != PACKAGE) return view
                            turn.played++
                            turn.handsLeft--
                            press(view.buttons[0], ctx)
                            delay(CONTINUE_MS)
                            empty = 0
                            budget = polls
                            left = true
                            continue
                        }
                        if (left || view.phase != from) { if (turn.single && !turn.stage) turn.played++; return view }
                    }
                    else -> if (left || view.phase != from) {
                        when (byDouZero(ctx, turn, frame, view)) {
                            Auto.Played -> { left = false; budget = polls }
                            Auto.Settling -> Unit
                            Auto.Off -> return view
                        }
                    }
                }
            }
            // 用户插了话就不等了:这一手已经做完,模型接着读那句话
            waitOrSteer { delay(POLL_MS) } ?: return last
        }
        return last
    }

    /**
     * 叫分、加倍可能发生在模型两次调用之间,工具没看见;连着几帧没手牌、又出现手牌,就当新发了一手,
     * 上一手的历史(和打了一半不接的标记)作废,按头一帧的张数重新定接不接。
     */
    private fun dealt(turn: Turn, view: DdzView) {
        if (view.handCount == 0 && view.phase == DdzPhase.Other) { turn.noHand++; return }
        if (turn.noHand >= NEW_HAND_GAP) {
            turn.tracker = null
            turn.expectedHand = null
            turn.joining = true
        }
        turn.noHand = 0
    }

    /** 把这一帧喂给这一局的出牌历史;叫分时还没开局,换一局重新记。 */
    private fun track(turn: Turn, frame: com.wangzan101.mika.core.uistate.RawFrame, view: DdzView) {
        if (view.phase == DdzPhase.Call) { turn.tracker = null; turn.expectedHand = null; turn.joining = false; return }
        // 农民 17 张、地主 20 张说明自己还没出过牌,在那之前的出牌都还摆在桌上,接得上;别的张数是打了一半,这一手不交给 DouZero。
        // 发牌时手牌一张张多起来,要等到加倍或者轮到自己才数
        if (turn.joining && view.phase != DdzPhase.Other) {
            turn.joining = false
            if (view.handCount != 17 && view.handCount != 20) turn.tracker = DdzTracker().apply { fail("joined_mid_game") }
        }
        val tracker = turn.tracker ?: DdzTracker().also { turn.tracker = it }
        val mine = view.phase == DdzPhase.Follow || view.phase == DdzPhase.Lead
        val seat = if (mine) DdzSeat.Me else when (DdzCards.clock(frame)) {
            DdzSide.Left -> DdzSeat.Left
            DdzSide.Right -> DdzSeat.Right
            null -> null
        }
        tracker.observe(seat, { DdzCards.play(frame, it) }, { DdzCards.bottom(frame) }, view.phase == DdzPhase.Lead && view.handCount == 20, view.phase == DdzPhase.Lead) {
            if (DdzCards.singleStageTable(frame)) DdzCards.counts(frame) else emptyMap()
        }
    }

    private enum class Auto { Played, Settling, Off }

    /**
     * 轮到自己出牌:历史对得上就问 DouZero 出这一手([Auto.Played],接着等);别家的牌还在飞、没读到,
     * 再等几帧([Auto.Settling]);对不上(读漏、认不准、DouZero 不认这段历史)这一手用提示保底、
     * 这一局剩下的交还模型([Auto.Off])。
     */
    private suspend fun byDouZero(ctx: ToolContext, turn: Turn, frame: com.wangzan101.mika.core.uistate.RawFrame, view: DdzView): Auto {
        val tracker = turn.tracker ?: return Auto.Off
        val advisor = turn.advisor ?: return Auto.Off
        if (!turn.single || tracker.broken != null) return Auto.Off
        val hand = DdzCards.hand(frame, view)
        // 别家的牌还在飞、或者动画(顺子、炸弹的特效)正盖着手牌:再等几帧
        if ((!tracker.ready() || hand == null) && tracker.broken == null && turn.settling++ < SETTLE_POLLS) return Auto.Settling
        turn.settling = 0
        val started = now()
        val reason = when {
            tracker.broken != null -> tracker.broken
            !tracker.ready() -> "history_not_ready"
            hand == null -> "hand_unreadable"
            turn.expectedHand?.let { it.sorted() != hand.sorted() } == true -> "hand_drift"
            tracker.landlord == DdzSeat.Me && tracker.history.isEmpty() && hand.size != 20 -> "landlord_mismatch"
            else -> null
        }
        val play = if (reason == null) askDouZero(advisor, tracker, hand!!) else null
        val failure = reason ?: play?.exceptionOrNull()?.message
        if (failure == null && play != null) {
            val cards = play.getOrThrow()
            // 哪一步没成:没认出要的牌、抬不起来,还是按了出牌游戏不认
            val stuck = when {
                cards.isEmpty() -> if (view.phase == DdzPhase.Follow && press(view.buttons[0], ctx).moved) null else "pass"
                else -> when (val want = pick(hand!!, cards)) {
                    null -> "pick"
                    else -> if (select(want, ctx) == null) "select" else if (!confirm(view, ctx)) "confirm" else null
                }
            }
            val done = stuck == null
            if (done) {
                tracker.mine(cards)
                turn.expectedHand = hand!!.toMutableList().apply { cards.forEach { remove(it) } }
                turn.decisions += now() - started
                return Auto.Played
            }
            tracker.fail("douzero_move_failed_$stuck")
        } else tracker.fail(failure ?: "douzero_unknown")
        // 这一手起交还模型自己出:提示只会挑压得过的,分不清压的是不是队友
        return Auto.Off
    }

    private suspend fun askDouZero(url: String, tracker: DdzTracker, hand: List<String>): Result<List<String>> {
        val body = buildJsonObject {
            put("seat", tracker.seat())
            put("hand", JsonArray(hand.map(::JsonPrimitive)))
            put("bottom", JsonArray(tracker.bottom.orEmpty().map(::JsonPrimitive)))
            put("history", JsonArray(tracker.history.map { m -> JsonArray(m.map(::JsonPrimitive)) }))
        }.toString()
        val reply = advise(url, body)?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            ?: return Result.failure(IllegalStateException("douzero_unreachable"))
        (reply["error"] as? JsonPrimitive)?.contentOrNull?.let { return Result.failure(IllegalStateException("douzero_$it")) }
        val play = (reply["play"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: return Result.failure(IllegalStateException("douzero_bad_reply"))
        return Result.success(play)
    }

    private suspend fun read(ctx: ToolContext): DdzView? = ctx.screen.rawFrame(DdzScreen.ROWS)?.let(DdzScreen::read)

    /** 单机局里每一帧都要认牌,一次把这些行读全。 */
    private val FULL_ROWS = (DdzScreen.ROWS + DdzCards.ROWS + DdzCards.LOBBY_ROWS).distinct()
    private val SNAPSHOT_RANKS = setOf("3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K", "A", "2", "SJ", "BJ")
    private val SNAPSHOT_PHASES = setOf("call", "double", "lead", "follow")
    private val SNAPSHOT_SEATS = setOf("landlord", "landlord_up", "landlord_down")

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
