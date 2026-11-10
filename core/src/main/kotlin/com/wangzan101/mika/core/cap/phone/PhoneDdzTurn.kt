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
    /** Human play recognition gets a longer bounded settling window than single-player animation. */
    private const val HUMAN_SETTLE_POLLS = 48
    /** 真人牌桌切换/发牌时可能短暂读不到正面标记;重试一小段,别立刻交还模型。 */
    private const val HUMAN_SCENE_RETRY_POLLS = 12
    /** start(auto) 刚点完快速开始/继续:匹配、发牌要十几秒,最多等约 20 秒牌桌出来。 */
    private const val START_TABLE_POLLS = 134
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
        /** TEMP diagnostics: last DDZTRACE line, to log only changes. */
        var lastTrace: String? = null
        var seenAt: Long? = null
        var rejected = 0
        var single = false
        var mode: DdzMode? = null
        var state = DdzSessionState.Idle
        var phase = DdzPhase.Other
        var reason: String? = null
        var stopped = false
        var snapshot: DdzSnapshot? = null
        /** start 之后进过牌桌没有:刚点完快速开始那几帧还是大厅,不能当成回了大厅。 */
        var atTable = false
        /** 上次交还模型时动作清单记到第几个;再来时多了,就是模型用别的工具动过屏幕。 */
        var actionsSeen = -1L
        /** 轮到自己时别家的牌还没落下,又等了几帧。 */
        var settling = 0
        /** Human opponents may take longer to finish a visible play. */
        var humanSettling = 0
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
        /** Human mode has acted on the currently visible decision; prevents repeated taps while it settles. */
        var humanActionSeen = false
        /** The latest human frame passed the positive classic-table gate and may be tapped once. */
        var humanEligible = false
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
        turn.joining = false
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
        "auto" to buildJsonObject { put("type", "boolean"); put("description", "start human mode: keep call, double, and DouZero play inside this tool until a pause or result.") },
        "advisor" to buildJsonObject { put("type", "string"); put("description", "start: DouZero service URL, required for both modes.") },
        "hand" to ranks("play: your whole hand; resume: the complete current hand."),
        "play" to ranks("The cards to play."),
        "bottom" to ranks("resume: the three visible bottom cards."),
        "history" to buildJsonObject {
            put("type", "array")
            put("description", "resume: visible plays in order; an empty array is a pass.")
            put("items", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string"); put("enum", JsonArray(DdzCards.RANKS.map(::JsonPrimitive))) }) })
        },
        "phase" to buildJsonObject { put("type", "string"); put("description", "resume: call, double, lead, or follow."); put("enum", JsonArray(listOf("call", "double", "lead", "follow").map(::JsonPrimitive))) },
        "seat" to buildJsonObject { put("type", "string"); put("description", "resume: landlord, landlord_up, or landlord_down."); put("enum", JsonArray(listOf("landlord", "landlord_up", "landlord_down").map(::JsonPrimitive))) },
        "snapshot" to snapshotSchema(),
        "level" to buildJsonObject { put("type", "integer"); put("description", "call: 0-3.") },
        "hands" to buildJsonObject { put("type", "integer"); put("description", "start: how many 闯关 hands to play in a row before returning, 1-10 (default 1).") },
    ), listOf("action"))

    private fun ranks(text: String) = buildJsonObject {
        put("type", "array"); put("description", text)
        put("items", buildJsonObject { put("type", "string"); put("enum", JsonArray(DdzCards.RANKS.map(::JsonPrimitive))) })
    }

    private fun snapshotSchema() = buildJsonObject {
        put("type", "object")
        put("description", "resume: complete current visible state; recovery validates every field before installing it.")
        put("additionalProperties", false)
        put("required", JsonArray(listOf("hand", "bottom", "history", "phase", "seat").map(::JsonPrimitive)))
        put("properties", buildJsonObject {
            put("hand", snapshotRanks("complete current hand", 1, 20))
            put("bottom", snapshotRanks("the three visible bottom cards", 3, 3))
            put("history", buildJsonObject {
                put("type", "array")
                put("description", "visible plays in order; an empty entry is a pass")
                put("items", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string"); put("enum", JsonArray(DdzCards.RANKS.map(::JsonPrimitive))) }) })
            })
            put("phase", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("call", "double", "lead", "follow").map(::JsonPrimitive))) })
            put("seat", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("landlord", "landlord_up", "landlord_down").map(::JsonPrimitive))) })
        })
    }

    private fun snapshotRanks(description: String, min: Int, max: Int) = buildJsonObject {
        put("type", "array"); put("description", description); put("minItems", min); put("maxItems", max)
        put("items", buildJsonObject { put("type", "string"); put("enum", JsonArray(DdzCards.RANKS.map(::JsonPrimitive))) })
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
                turn.state = DdzSessionState.Paused
                turn.reason = DdzSessionReason.APP_SWITCH
                return ToolExecution(buildJsonObject {
                    put("ok", false)
                    put("error", "paused")
                    put("recoverable", true)
                    put("mode", DdzMode.Human.wire)
                    put("state", DdzSessionState.Paused.wire)
                    put("reason", DdzSessionReason.APP_SWITCH)
                })
            }
            return refused("wrong_app")
        }
        if (turn.stopped && action != "start") return stopped(turn)
        // 模型在两次调用之间自己点过屏幕(关弹窗,也可能回大厅进了真人场):这里看不到它去过哪,单机标记作废,要再 start
        if (turn.single && ctx.evidence.actions != turn.actionsSeen) turn.single = false
        if (action == "start") return start(a, ctx, turn).also { turn.actionsSeen = ctx.evidence.actions }
        if (action == "resume") return resume(a, ctx, turn)
        if (turn.state == DdzSessionState.Paused) return paused(turn)
        var frame = ctx.screen.rawFrame(if (turn.advisor != null) FULL_ROWS else DdzScreen.ROWS) ?: return refused("no_frame")
        // 闯关牌桌只在单机模式里有:模型关了弹窗(广告)回到这桌上,还是单机,不用再 start;打了一半的这手照旧交给模型
        if (turn.mode != DdzMode.Human && !turn.single && turn.advisor != null && DdzCards.singleStageTable(frame)) {
            markSingle(ctx, turn.advisor!!)
            turn.tracker = null
            turn.expectedHand = null
            turn.joining = true
        }
        var initialView = DdzScreen.read(frame)
        if (turn.mode == DdzMode.Human && !DdzCards.classicHumanTable(frame)) {
            val next = if (humanHandoff(frame) == null) recoverHumanScene(ctx) else frame
            if (next == null || !DdzCards.classicHumanTable(next)) {
                turn.state = DdzSessionState.Paused
                turn.reason = next?.let(::humanHandoff) ?: DdzSessionReason.UNKNOWN_SCENE
                turn.humanEligible = false
                return paused(turn)
            }
            frame = next
            initialView = DdzScreen.read(next)
        }
        if (turn.mode == DdzMode.Human) turn.humanEligible = true
        val view = takeBackControl(initialView, ctx, turn).also { dealt(turn, it) }
        if (turn.mode == DdzMode.Human && turn.snapshot != null && !snapshotMatches(turn.snapshot!!, frame, view, turn)) {
            humanPause(turn, DdzSessionReason.SNAPSHOT_MISMATCH)
            return paused(turn)
        }
        val thinkMs = turn.seenAt?.let { now() - it } ?: 0
        val tapStart = now()
        val move = move(action, a, view, ctx, turn, thinkMs)
        val tapMs = now() - tapStart
        if (turn.mode == DdzMode.Human && move.moved) turn.snapshot = null
        val waitStart = now()
        val next = when {
            move.moved || action == "wait" -> awaitDecision(
                ctx, turn, view.phase,
                moved = move.moved || (action == "wait" && turn.mode == DdzMode.Human),
                polls = if (action == "again") AGAIN_POLLS else MAX_POLLS,
            )
            else -> read(ctx, turn) ?: view
        }
        val waitMs = now() - waitStart
        if (move.moved) turn.rejected = 0
        if (!turn.stopped && turn.state != DdzSessionState.Paused && turn.mode == DdzMode.Human && move.error != null) {
            turn.state = DdzSessionState.Paused
            turn.reason = pauseReason(move.error)
        } else settle(turn, next)
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
            recoveryHint(turn)?.let { put("recovery", it) }
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
            turn.tracker?.let { t ->
                t.broken?.let { put("douzeroOff", it); put("douzeroTrace", t.trace.joinToString(" ")) }
                if (turn.mode == DdzMode.Human) put("historyCount", t.history.size)
            }
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
        turn.stopped = false
        return when (mode) {
            DdzMode.Human.wire -> startHuman(a, ctx, turn, advisor)
            DdzMode.Single.wire -> startSingle(a, ctx, turn, advisor)
            else -> throw ArgError("mode", "must be single or human")
        }
    }

    private suspend fun startHuman(a: Args, ctx: ToolContext, turn: Turn, advisor: String): ToolExecution {
        var page = ctx.screen.rawFrame(FULL_ROWS) ?: return refused("no_frame")
        // Human mode may only attach to the current classic table. Stage tables and the
        // single-player lobby share the same button colors, so the explicit markers are a
        // required eligibility gate before any human-mode state is installed.
        if (!DdzCards.classicHumanTable(page)) {
            if (!a.bool("auto", false)) return refused("not_human_game")
            // 模型点完快速开始就调 start:在这里等牌桌出来(不点任何东西),免得模型自己等、错过叫分
            page = awaitHumanTable(ctx, turn) ?: return refused("not_human_game")
        }
        val view = DdzScreen.read(page)
        // 同一局里重复 start 才保留历史:上一局已结算、或历史已作废(含中途接手)都按新接手处理
        val recoveringSameGame = turn.mode == DdzMode.Human && turn.atTable && turn.advisor == advisor &&
            turn.tracker?.broken == null && turn.tracker != null && turn.state != DdzSessionState.Result
        turn.single = false
        turn.mode = DdzMode.Human
        turn.phase = view.phase
        turn.state = stateFor(view.phase)
        turn.reason = null
        turn.stopped = false
        turn.advisor = advisor
        turn.atTable = true
        turn.actionsSeen = ctx.evidence.actions
        if (!recoveringSameGame) {
            turn.tracker = null
            turn.expectedHand = null
            turn.snapshot = null
            // 第一帧牌桌之前不知道这一局打到哪了:由 track 按第一帧的阶段定接不接
            turn.joining = true
        }
        if (!a.bool("auto", false)) {
            return ToolExecution(buildJsonObject {
                put("ok", true)
                put("mode", DdzMode.Human.wire)
                put("state", turn.state.wire)
                put("phase", view.phase.name.lowercase())
                put("handCount", view.handCount)
            }, image = if (ctx.config.visionEnabled) ctx.screen.quickShot() else null, changed = false)
        }
        // Human tables have the same hard turn clock as single-player tables. Keep the
        // automatic loop inside the tool so the model cannot spend a turn between call,
        // double, and DouZero play decisions.
        val next = awaitDecision(ctx, turn, DdzPhase.Other, moved = true, polls = MAX_POLLS)
        settle(turn, next)
        turn.seenAt = now()
        return ToolExecution(buildJsonObject {
            put("ok", true)
            put("mode", DdzMode.Human.wire)
            put("state", turn.state.wire)
            turn.reason?.let { put("reason", it) }
            recoveryHint(turn)?.let { put("recovery", it) }
            put("phase", next.phase.name.lowercase())
            put("handCount", next.handCount)
            if (turn.decisions.isNotEmpty()) {
                put("autoPlayed", turn.decisions.size)
                put("autoMs", JsonArray(turn.decisions.map(::JsonPrimitive)))
                turn.decisions.clear()
            }
            turn.tracker?.let { tracker ->
                tracker.broken?.let { put("douzeroOff", it) }
                put("historyCount", tracker.history.size)
            }
        }, image = if (ctx.config.visionEnabled) ctx.screen.quickShot() else null, changed = true)
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
        if (tap != null && !tap(ctx, turn, tap.first, tap.second)) return if (turn.stopped) stopped(turn) else refused("tap_failed")
        if (turn.stopped) return stopped(turn)
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
        turn.phase = next.phase
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
        turn.phase = DdzPhase.Other
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
        recoveryHint(turn)?.let { put("recovery", it) }
    })

    /** After the wait loop: keep a pause the loop set (and its reason); otherwise report the phase it reached. */
    private fun settle(turn: Turn, next: DdzView) {
        if (turn.stopped || turn.state == DdzSessionState.Paused) return
        if (turn.mode == DdzMode.Human && next.phase == DdzPhase.Other) {
            turn.state = DdzSessionState.Paused
            turn.reason = turn.reason ?: DdzSessionReason.UNKNOWN_SCENE
            return
        }
        turn.phase = next.phase
        turn.state = stateFor(next.phase)
        turn.reason = null
    }

    /** Points a paused human game at snapshot resume. Lobby return and app switch end the game
     * (there is no table to read), and a game joined mid-way has plays nobody saw, so they get no hint. */
    private fun recoveryHint(turn: Turn): JsonObject? {
        if (turn.mode != DdzMode.Human || turn.state != DdzSessionState.Paused) return null
        if (turn.reason in setOf(DdzSessionReason.LOBBY_RETURN, DdzSessionReason.APP_SWITCH, DdzSessionReason.JOINED_MID_GAME)) return null
        return buildJsonObject {
            put("action", "resume")
            put("required", JsonArray(listOf("hand", "bottom", "history", "phase", "seat").map(::JsonPrimitive)))
            put("instruction", "Read the current screenshot, build the complete visible snapshot, then call resume. Do not call start or play while paused.")
        }
    }

    private fun resume(a: Args, ctx: ToolContext, turn: Turn): ToolExecution {
        if (turn.mode != DdzMode.Human) return refused("resume_requires_human")
        val snapshot = try {
            parseSnapshot(a)
        } catch (error: ArgError) {
            return invalidRecovery(turn, recoveryReason(error))
        }
        return when (val recovered = DdzSnapshotRecovery.recover(snapshot)) {
            is DdzRecoveryResult.Invalid -> invalidRecovery(turn, recovered.failure.reason)
            is DdzRecoveryResult.Valid -> {
                // Install only after every field and every historical move has validated.
                turn.snapshot = recovered.value.snapshot
                turn.tracker = recovered.value.tracker
                turn.joining = false
                turn.humanSettling = 0
                turn.stopped = false
                turn.phase = phaseOf(snapshot.phase)
                turn.state = stateFor(snapshot.phase)
                turn.reason = null
                turn.atTable = true
                turn.actionsSeen = ctx.evidence.actions
                ToolExecution(buildJsonObject {
                    put("ok", true)
                    put("mode", DdzMode.Human.wire)
                    put("state", turn.state.wire)
                    put("phase", snapshot.phase)
                    put("seat", snapshot.seat)
                    put("handCount", snapshot.hand.size)
                    put("historyCount", snapshot.history.size)
                })
            }
        }
    }

    private fun invalidRecovery(turn: Turn, reason: String) = ToolExecution(buildJsonObject {
        put("ok", false)
        put("error", DdzSessionReason.INVALID_SNAPSHOT)
        put("recoverable", true)
        turn.mode?.let { put("mode", it.wire) }
        put("state", turn.state.wire)
        put("reason", reason)
        turn.phase.takeIf { it != DdzPhase.Other }?.let { put("phase", it.name.lowercase()) }
    })

    private fun recoveryReason(error: ArgError): String = when {
        error.message?.contains("ambiguous_card") == true -> "ambiguous_card"
        error.message?.contains("invalid_seat") == true -> "seat"
        error.message?.contains("invalid_phase") == true -> "phase"
        error.field == "hand" && error.message?.contains("must have") == true -> "hand_count"
        error.field == "bottom" && error.message?.contains("must have") == true -> "bottom_count"
        else -> "invalid_snapshot"
    }

    private fun phaseOf(phase: String) = when (phase) {
        "call" -> DdzPhase.Call
        "double" -> DdzPhase.Double
        "lead" -> DdzPhase.Lead
        else -> DdzPhase.Follow
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
                if (text.startsWith("?")) throw ArgError(name, "ambiguous_card")
                if (text !in DdzCards.RANKS) throw ArgError(name, "contains an invalid rank")
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
                if (rank.startsWith("?")) throw ArgError("history", "ambiguous_card")
                if (rank !in DdzCards.RANKS) throw ArgError("history", "contains an invalid rank")
                rank
            }
        }
        val phase = (required("phase") as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
            ?: throw ArgError("phase", "must be a string")
        if (phase !in SNAPSHOT_PHASES) throw ArgError("phase", "invalid_phase")
        val seat = (required("seat") as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
            ?: throw ArgError("seat", "must be a string")
        if (seat !in SNAPSHOT_SEATS) throw ArgError("seat", "invalid_seat")
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
        "douzero_history_mismatch", "douzero_hand_count_mismatch", "douzero_card_count_mismatch",
        "douzero_ambiguous_card", "history_not_ready" -> DdzSessionReason.HISTORY_MISMATCH
        "douzero_unreachable", "douzero_bad_reply" -> DdzSessionReason.SERVICE_UNAVAILABLE
        "rejected", "select_failed", "confirm_failed", "douzero_move_failed_pick" -> DdzSessionReason.REJECTED_ACTION
        else -> DdzSessionReason.UNKNOWN_SCENE
    }

    private suspend fun move(action: String, a: Args, view: DdzView, ctx: ToolContext, turn: Turn, thinkMs: Long): Move {
        val phase = view.phase
        return when (action) {
            "wait" -> Move(false)
            "again" -> if (phase == DdzPhase.Result) press(view.buttons[0], ctx, turn) else wrongPhase(phase)
            "call" -> if (phase != DdzPhase.Call) wrongPhase(phase) else {
                val level = a.int("level", 0, 3) ?: 0
                // 灰的是比别人叫得低、点不了的分:按不叫算
                val button = view.buttons[level].takeIf { it.color != DdzColor.Gray } ?: view.buttons[0]
                // 按完叫分按钮还留几帧,单机局里接着等时别再按一次
                press(button, ctx, turn).also { if (it.moved) turn.bid = true }
            }
            "pass" -> when (phase) {
                DdzPhase.Follow -> press(view.buttons[0], ctx, turn)
                DdzPhase.Lead -> smallest(view, ctx, turn)
                else -> wrongPhase(phase)
            }
            "hint" -> when (phase) {
                DdzPhase.Follow -> hint(view, ctx, turn)
                DdzPhase.Lead -> smallest(view, ctx, turn)
                else -> wrongPhase(phase)
            }
            "play" -> if (phase != DdzPhase.Follow && phase != DdzPhase.Lead) wrongPhase(phase) else play(a, view, ctx, turn, thinkMs)
            else -> throw ArgError("action", "unknown action")
        }
    }

    private suspend fun play(a: Args, view: DdzView, ctx: ToolContext, turn: Turn, thinkMs: Long): Move {
        if (DdzScreen.onlyPass(view)) return press(view.buttons[0], ctx, turn).let { Move(it.moved, it.error, "cannot_beat") }
        val hand = strings(a, "hand")
        val want = pick(hand, strings(a, "play"))
        val error = when {
            hand.size != view.handCount -> "hand_mismatch"
            want == null || want.isEmpty() -> "card_not_in_hand"
            else -> null
        }
        val selected = if (error == null) select(want!!, ctx, turn) else null
        if (selected != null) {
            if (confirm(view, ctx, turn)) return Move(true)
        }
        // 模型读错、出的牌不合规:还来得及就让它改一次,来不及或改过了就保底
        val reason = error ?: if (selected == null) "select_failed" else "rejected"
        if (turn.rejected == 0 && thinkMs < RETRY_BUDGET_MS) {
            turn.rejected++
            return Move(false, reason)
        }
        val now = read(ctx, turn) ?: view
        val fallback = if (now.phase == DdzPhase.Follow) press(now.buttons[0], ctx, turn) else smallest(now, ctx, turn)
        return Move(fallback.moved, fallback.error, "fallback_after_$reason")
    }

    private suspend fun hint(view: DdzView, ctx: ToolContext, turn: Turn): Move {
        if (DdzScreen.onlyPass(view)) return press(view.buttons[0], ctx, turn)
        if (!tap(ctx, turn, view.buttons[1].x, view.buttons[1].y)) return Move(false, "tap_failed")
        delay(SETTLE_MS)
        val after = read(ctx, turn) ?: return Move(false, "no_frame")
        // 按钮没了就是这一手已经过去;提示抬不起牌就是要不起
        if (after.phase != DdzPhase.Follow) return Move(true)
        if (after.lifted.isEmpty()) return press(view.buttons[0], ctx, turn)
        return if (confirm(view, ctx, turn)) Move(true) else press(view.buttons[0], ctx, turn)
    }

    private suspend fun smallest(view: DdzView, ctx: ToolContext, turn: Turn): Move {
        select(setOf(view.handCount - 1), ctx, turn) ?: return Move(false, "select_failed")
        return if (confirm(view, ctx, turn)) Move(true) else Move(false, "rejected")
    }

    /**
     * 按出牌,看画面确认牌出去了。抬牌动画里按下去游戏可能不认,没出去就再按一次;还不走才算不合规。
     * 用选牌前的 [view] 里的位置:按钮不动,而选完这一帧可能已经超时、没有按钮了。
     */
    private suspend fun confirm(view: DdzView, ctx: ToolContext, turn: Turn? = null): Boolean {
        // 只剩不出时最后一个按钮就是不出,不能当出牌按
        if (DdzScreen.onlyPass(view)) return false
        repeat(2) {
            if (!tap(ctx, turn, view.buttons.last().x, view.buttons.last().y)) return false
            delay(VERDICT_MS)
            val after = read(ctx, turn)
            if (after == null || after.phase != view.phase || after.handCount != view.handCount) return true
        }
        return false
    }

    /** 把抬起的牌改成 [want];点完看画面,没到位再补一次。 */
    private suspend fun select(want: Set<Int>, ctx: ToolContext, turn: Turn? = null): DdzView? {
        repeat(2) {
            val view = read(ctx, turn)?.takeIf { it.phase == DdzPhase.Follow || it.phase == DdzPhase.Lead } ?: return null
            val toggle = (want - view.lifted) + (view.lifted - want)
            if (toggle.isEmpty()) return view
            for (index in toggle) {
                if (!tap(ctx, turn, view.cardX(index), DdzScreen.CARD_Y)) return null
                delay(TAP_GAP_MS)
                if (turn?.mode == DdzMode.Human && readTable(ctx, turn) == null) return null
            }
            // 抬牌有动画:还没抬到位就再点一下,会把它点回去。多看几帧,真没抬起来才重点
            repeat(LIFT_POLLS) {
                delay(SETTLE_MS)
                read(ctx, turn)?.takeIf { it.lifted == want }?.let { return it }
            }
        }
        return read(ctx, turn)?.takeIf { it.lifted == want }
    }

    private suspend fun press(button: DdzButton, ctx: ToolContext, turn: Turn? = null) =
        if (tap(ctx, turn, button.x, button.y)) Move(true) else Move(false, "tap_failed")

    /** Every automatic human tap rechecks the stop latch and foreground app immediately before input. */
    private suspend fun tap(ctx: ToolContext, turn: Turn?, x: Int, y: Int): Boolean {
        if (turn?.stopped == true) return false
        if (turn != null && ctx.screen.peek().packageName != PACKAGE) {
            turn.state = DdzSessionState.Paused
            turn.reason = DdzSessionReason.APP_SWITCH
            return false
        }
        if (turn?.mode == DdzMode.Human && !turn.humanEligible) return false
        if (turn?.mode == DdzMode.Human) turn.humanEligible = false
        return ctx.screen.quickTap(x, y)
    }

    /** 模型想的时候超时进了托管:先取消,再按取消后的画面做。 */
    private suspend fun takeBackControl(view: DdzView, ctx: ToolContext, turn: Turn): DdzView {
        // 真人局刚接手、还没确认这一局接得上:托管留着,让游戏自己打完
        if (turn.mode == DdzMode.Human && turn.joining) return view
        if (view.phase != DdzPhase.AutoPlay || !tap(ctx, turn, view.buttons[0].x, view.buttons[0].y)) return view
        delay(SETTLE_MS)
        return read(ctx, turn) ?: view
    }

    /**
     * 等到又该玩家决定;[moved] 刚动过手时,同一阶段要先离开过才算新的一手。托管了就取消。
     * 单机局里每一帧也喂给出牌历史;轮到自己出牌、历史又对得上时不交还模型,由 DouZero 出完接着等。
     */
    private suspend fun awaitDecision(ctx: ToolContext, turn: Turn, from: DdzPhase, moved: Boolean, polls: Int): DdzView {
        var left = !moved
        var last = DdzView(DdzPhase.Other, emptyList(), 0, 0.0, 0, emptySet())
        var budget = polls
        var humanPhase: DdzPhase? = null
        var humanSceneMisses = 0
        turn.humanActionSeen = false
        // 一直看不到手牌就不是在牌桌上(大厅、选关页、弹窗),干等等不来:发牌用不了这么久
        var empty = 0
        while (budget-- > 0) {
            if (turn.stopped) return last
            if (turn.mode == DdzMode.Human && turn.state == DdzSessionState.Paused) return last
            val frame = ctx.screen.rawFrame(if (turn.single || turn.mode == DdzMode.Human) FULL_ROWS else DdzScreen.ROWS)
            val view = frame?.let(DdzScreen::read)
            if (view != null) {
                last = view
                if (turn.mode == DdzMode.Human) {
                    if (!DdzCards.classicHumanTable(frame)) {
                        val handoff = humanHandoff(frame)
                        if (handoff != null) {
                            turn.humanEligible = false
                            turn.state = DdzSessionState.Paused
                            turn.reason = handoff
                            return view
                        }
                        if (++humanSceneMisses > HUMAN_SCENE_RETRY_POLLS) {
                            turn.humanEligible = false
                            turn.state = DdzSessionState.Paused
                            turn.reason = DdzSessionReason.UNKNOWN_SCENE
                            return view
                        }
                        waitOrSteer { delay(POLL_MS) } ?: return last
                        continue
                    }
                    humanSceneMisses = 0
                    turn.humanEligible = true
                    // 牌桌上的 Other 是别家在叫分、出牌(一家最多 15 秒):不停,照常往下记历史、接着等,总时长由 polls 管
                    if (humanPhase != view.phase) {
                        humanPhase = view.phase
                        turn.humanActionSeen = false
                        // 每个新的决定都有完整的等待:上一手(上一局)用掉的不算到这一手
                        turn.humanSettling = 0
                    }
                }
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
                    if (!tap(ctx, turn, next.first, next.second)) return last
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
                if (turn.single || turn.mode == DdzMode.Human) track(turn, frame, view)
                if (turn.mode == DdzMode.Human && turn.tracker?.broken == DdzSessionReason.JOINED_MID_GAME) {
                    humanPause(turn, DdzSessionReason.JOINED_MID_GAME)
                    return view
                }
                when (view.phase) {
                    // 单机局叫分按牌力定,不等模型(叫分只有十来秒);灰的分数点不了,按不叫算
                    DdzPhase.Call -> when {
                        turn.mode == DdzMode.Human && !turn.humanActionSeen -> {
                            val hand = DdzCards.hand(frame, view)?.takeIf { it.size == 17 }
                            val level = hand?.let { cards ->
                                turn.advisor?.let { url -> adviseBid(url, cards) } ?: bidLevel(cards)
                            }
                            val button = level?.let { view.buttons.getOrNull(it) }
                            when {
                                button == null -> humanPause(turn, DdzSessionReason.UNKNOWN_SCENE)
                                button.color == DdzColor.Gray -> humanPause(turn, DdzSessionReason.REJECTED_ACTION)
                                press(button, ctx, turn).moved -> {
                                    turn.bid = true
                                    turn.humanActionSeen = true
                                }
                                else -> humanPause(turn, DdzSessionReason.REJECTED_ACTION)
                            }
                            left = true
                        }
                        !turn.single -> if ((left || view.phase != from) && !(turn.mode == DdzMode.Human && turn.humanActionSeen)) return view
                        !turn.bid -> {
                            // 叫分按钮出来时牌可能还在发(手里才十几张)、认不全:等下一帧,不交还模型
                            val hand = DdzCards.hand(frame, view)?.takeIf { it.size == 17 }
                            if (hand != null) {
                                // 先问 DouZero 当地主的赢面;服务连不上就按牌力的老规则
                                val level = turn.advisor?.let { adviseBid(it, hand) } ?: bidLevel(hand)
                                val button = view.buttons[level].takeIf { it.color != DdzColor.Gray } ?: view.buttons[0]
                                if (press(button, ctx, turn).moved) turn.bid = true
                            }
                            left = true
                        }
                    }
                    DdzPhase.AutoPlay -> {
                        left = true
                        // 等的时候前台可能换了,点之前再认一次;点了等它退出托管,别连着点
                        if (tap(ctx, turn, view.buttons[0].x, view.buttons[0].y)) delay(AUTO_CANCEL_MS)
                    }
                    // 加倍只有约 5 秒,模型来不及:单机局里自己点最左边蓝色的不加倍(加倍要看视频,绝不点);不是单机就让它走完
                    DdzPhase.Double -> {
                        left = true
                        if (turn.mode == DdzMode.Human && !turn.humanActionSeen) {
                            val hand = DdzCards.hand(frame, view)
                            // Bottom cards are often still face-down during doubling. Unknown bottom
                            // cards conservatively mean "do not double"; they must not block the safe pass.
                            val bottom = DdzCards.bottom(frame).orEmpty()
                            val double = hand != null && HumanDdzPolicy.shouldDouble(hand, bottom)
                            val button = if (double) {
                                view.buttons.lastOrNull { it.color == DdzColor.Green || it.color == DdzColor.Yellow }
                            } else view.buttons.firstOrNull()
                            // 地主拿了底牌的 20 张常认不出:认不出就不加倍(和底牌认不出一样保守),不停下
                            when {
                                button == null -> humanPause(turn, DdzSessionReason.UNKNOWN_SCENE)
                                button.color == DdzColor.Gray -> humanPause(turn, DdzSessionReason.REJECTED_ACTION)
                                press(button, ctx, turn).moved -> {
                                    turn.declined = !double
                                    turn.humanActionSeen = true
                                }
                                else -> humanPause(turn, DdzSessionReason.REJECTED_ACTION)
                            }
                        } else {
                            val no = view.buttons.first()
                            if (turn.single && !turn.declined && no.color == DdzColor.Blue && press(no, ctx, turn).moved) turn.declined = true
                        }
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
                            if (!tap(ctx, turn, view.buttons[0].x, view.buttons[0].y)) return view
                            delay(CONTINUE_MS)
                            empty = 0
                            budget = polls
                            left = true
                            continue
                        }
                        if (turn.mode == DdzMode.Human) return view
                        if (left || view.phase != from) { if (turn.single && !turn.stage) turn.played++; return view }
                    }
                    else -> if (left || view.phase != from || (turn.mode == DdzMode.Human && !turn.humanActionSeen)) {
                        if (turn.mode == DdzMode.Human) {
                            when (byHuman(ctx, turn, frame, view)) {
                                Auto.Played -> { left = false; turn.humanActionSeen = true; budget = polls }
                                Auto.Settling -> Unit
                                Auto.Off -> return view
                            }
                        } else {
                            when (byDouZero(ctx, turn, frame, view)) {
                                Auto.Played -> { left = false; budget = polls }
                                Auto.Settling -> Unit
                                Auto.Off -> return view
                            }
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
        // 真人局的结算画面说明不了下一局:等下一个牌桌画面再定
        if (turn.joining && view.phase != DdzPhase.Other && !(turn.mode == DdzMode.Human && view.phase == DdzPhase.Result)) {
            turn.joining = false
            // 真人局读不出别家剩几张,托管时自己一直不出、手里照样 17 张:只有加倍时(还没人出过牌)才接得上
            val fresh = if (turn.mode == DdzMode.Human) view.phase == DdzPhase.Double else view.handCount == 17 || view.handCount == 20
            if (!fresh) turn.tracker = DdzTracker().apply { fail(DdzSessionReason.JOINED_MID_GAME) }
        }
        val tracker = turn.tracker ?: DdzTracker(if (turn.mode == DdzMode.Human) DdzSettlingPolicy.HUMAN else DdzSettlingPolicy.SINGLE).also { turn.tracker = it }
        val mine = view.phase == DdzPhase.Follow || view.phase == DdzPhase.Lead
        val seat = if (mine) DdzSeat.Me else when (DdzCards.clock(frame)) {
            DdzSide.Left -> DdzSeat.Left
            DdzSide.Right -> DdzSeat.Right
            null -> null
        }
        tracker.observe(seat, { DdzCards.play(frame, it) }, { DdzCards.bottom(frame) }, view.phase == DdzPhase.Lead && view.handCount == 20, view.phase == DdzPhase.Lead) {
            if (DdzCards.singleStageTable(frame)) DdzCards.counts(frame) else emptyMap()
        }
        // TEMP diagnostics for the live human-mode run; remove after.
        if (turn.mode == DdzMode.Human) {
            val line = "phase=${view.phase} hand=${view.handCount} clock=${DdzCards.clock(frame)} seat=$seat " +
                "L=${DdzCards.play(frame, DdzSide.Left)} R=${DdzCards.play(frame, DdzSide.Right)} ${tracker.debugState()}"
            if (line != turn.lastTrace) { turn.lastTrace = line; println("DDZTRACE ${System.currentTimeMillis() % 100_000} $line") }
        }
    }

    private enum class Auto { Played, Settling, Off }

    /** Human mode keeps the same advisor protocol as single-player while refusing to act on an
     * unconfirmed table, hand, or visible history. */
    private suspend fun byHuman(
        ctx: ToolContext, turn: Turn, frame: com.wangzan101.mika.core.uistate.RawFrame, view: DdzView,
    ): Auto {
        val tracker = turn.tracker ?: return humanPause(turn, DdzSessionReason.HISTORY_MISMATCH).let { Auto.Off }
        val advisor = turn.advisor ?: return humanPause(turn, DdzSessionReason.SERVICE_UNAVAILABLE).let { Auto.Off }
        if (tracker.broken != null) return humanPause(turn, pauseReason(tracker.broken!!)).let { Auto.Off }
        val hand = DdzCards.hand(frame, view)
        if (!tracker.ready() || hand == null) {
            if (turn.humanSettling++ < HUMAN_SETTLE_POLLS) return Auto.Settling
            tracker.fail("history_not_ready")
            return humanPause(turn, DdzSessionReason.HISTORY_MISMATCH).let { Auto.Off }
        }
        turn.humanSettling = 0
        if (turn.expectedHand?.sorted() != null && turn.expectedHand!!.sorted() != hand.sorted()) {
            tracker.fail("hand_drift")
            return humanPause(turn, DdzSessionReason.HAND_DRIFT).let { Auto.Off }
        }
        // A transient service failure gets one quick retry; a replay mismatch is deterministic.
        val play = askDouZero(advisor, tracker, hand).let { first ->
            if (first.exceptionOrNull()?.message in TRANSIENT_ADVISOR_ERRORS) askDouZero(advisor, tracker, hand) else first
        }
        val cards = play.getOrElse {
            tracker.fail(it.message ?: "douzero_unreachable")
            return humanPause(turn, pauseReason(it.message ?: "douzero_unreachable")).let { Auto.Off }
        }
        if (cards.any { it !in DdzCards.RANKS }) {
            tracker.fail("douzero_bad_reply")
            return humanPause(turn, DdzSessionReason.SERVICE_UNAVAILABLE).let { Auto.Off }
        }
        // An empty answer means pass only when following. A lead requires an explicit advisor move;
        // guessing a card here can silently play the wrong hand.
        val chosen = cards
        if (chosen.isEmpty()) {
            if (view.phase != DdzPhase.Follow) return humanPause(turn, DdzSessionReason.INVALID_ADVISOR_PLAY).let { Auto.Off }
            val button = view.buttons.firstOrNull() ?: return humanPause(turn, DdzSessionReason.REJECTED_ACTION).let { Auto.Off }
            if (!press(button, ctx, turn).moved) return humanPause(turn, DdzSessionReason.REJECTED_ACTION).let { Auto.Off }
            tracker.mine(emptyList())
            turn.expectedHand = hand
            return Auto.Played
        }
        val want = pick(hand, chosen) ?: run {
            tracker.fail("douzero_move_failed_pick")
            return humanPause(turn, DdzSessionReason.REJECTED_ACTION).let { Auto.Off }
        }
        val selected = select(want, ctx, turn)
            ?: return humanPause(turn, DdzSessionReason.REJECTED_ACTION).let { Auto.Off }
        if (selected.phase != view.phase || selected.handCount != hand.size) {
            tracker.fail("hand_drift")
            return humanPause(turn, DdzSessionReason.HAND_DRIFT).let { Auto.Off }
        }
        if (!confirm(selected, ctx, turn)) {
            tracker.fail("confirm_failed")
            return humanPause(turn, DdzSessionReason.REJECTED_ACTION).let { Auto.Off }
        }
        tracker.mine(chosen)
        turn.expectedHand = hand.toMutableList().apply { chosen.forEach { remove(it) } }
        return Auto.Played
    }

    private fun humanPause(turn: Turn, reason: String) {
        println("DDZTRACE pause $reason ${turn.tracker?.debugState()}") // TEMP diagnostics; remove after.
        turn.state = DdzSessionState.Paused
        turn.reason = reason
    }

    /** Poll (without tapping) until the classic human table is on screen; null on timeout, stop, or steer. */
    private suspend fun awaitHumanTable(ctx: ToolContext, turn: Turn): com.wangzan101.mika.core.uistate.RawFrame? {
        repeat(START_TABLE_POLLS) {
            waitOrSteer { delay(POLL_MS) } ?: return null
            if (turn.stopped) return null
            val frame = ctx.screen.rawFrame(FULL_ROWS) ?: return@repeat
            if (DdzCards.classicHumanTable(frame)) return frame
        }
        return null
    }

    /** Known non-table scenes that end automatic human play; null when the scene is just unrecognised. */
    private fun humanHandoff(frame: com.wangzan101.mika.core.uistate.RawFrame): String? = when {
        DdzCards.singleLobby(frame) -> DdzSessionReason.LOBBY_RETURN
        DdzCards.unknownPopup(frame) -> DdzSessionReason.UNKNOWN_POPUP
        else -> null
    }

    /** Wait out one short transition before calling a human-table scene unknown. Returns the first
     * readable table frame, the first lobby/popup frame (nothing left to wait for), or null. */
    private suspend fun recoverHumanScene(ctx: ToolContext): com.wangzan101.mika.core.uistate.RawFrame? {
        repeat(HUMAN_SCENE_RETRY_POLLS) {
            waitOrSteer { delay(POLL_MS) } ?: return null
            val frame = ctx.screen.rawFrame(FULL_ROWS) ?: return@repeat
            if (DdzCards.classicHumanTable(frame)) {
                if (DdzScreen.read(frame).phase != DdzPhase.Other) return frame
            } else if (humanHandoff(frame) != null) {
                return frame
            }
        }
        return null
    }

    /** Check the recovered state against the next visible screen before any automatic input. */
    private fun snapshotMatches(snapshot: DdzSnapshot, frame: com.wangzan101.mika.core.uistate.RawFrame, view: DdzView, turn: Turn): Boolean {
        if (view.phase != phaseOf(snapshot.phase)) return false
        val hand = DdzCards.hand(frame, view) ?: return false
        if (hand.sorted() != snapshot.hand.sorted() || hand.size != snapshot.hand.size) return false
        if (DdzCards.bottom(frame) != snapshot.bottom) return false
        val tracker = turn.tracker ?: return false
        if (tracker.seat() != snapshot.seat || tracker.history.map { it.toList() } != snapshot.history) return false
        if (!tracker.visibleHistoryMatches { side -> DdzCards.play(frame, side) }) return false
        return true
    }

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
                cards.isEmpty() -> if (view.phase == DdzPhase.Follow && press(view.buttons[0], ctx, turn).moved) null else "pass"
                else -> when (val want = pick(hand!!, cards)) {
                    null -> "pick"
                    else -> if (select(want, ctx, turn) == null) "select" else if (!confirm(view, ctx, turn)) "confirm" else null
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

    private suspend fun read(ctx: ToolContext, turn: Turn? = null): DdzView? {
        val frame = ctx.screen.rawFrame(if (turn?.mode == DdzMode.Human) FULL_ROWS else DdzScreen.ROWS) ?: return null
        if (turn?.mode == DdzMode.Human) {
            if (!DdzCards.classicHumanTable(frame)) {
                turn.humanEligible = false
                // 大厅、弹窗是确定离开了牌桌,停下;认不出的单帧(出牌动画等)只报 null,停不停交给等待循环按帧数定
                humanHandoff(frame)?.let { turn.reason = it; turn.state = DdzSessionState.Paused }
                return null
            }
            turn.humanEligible = true
        }
        return DdzScreen.read(frame)
    }

    /** Human mode: wait out a few unrecognised frames (animations) before giving up on the table; null if it never comes back or the session paused. */
    private suspend fun readTable(ctx: ToolContext, turn: Turn): DdzView? {
        repeat(HUMAN_SCENE_RETRY_POLLS) {
            read(ctx, turn)?.let { return it }
            if (turn.state == DdzSessionState.Paused || turn.stopped) return null
            delay(POLL_MS)
        }
        return null
    }

    /** 单机局里每一帧都要认牌,一次把这些行读全。 */
    private val FULL_ROWS = (DdzScreen.ROWS + DdzCards.ROWS + DdzCards.LOBBY_ROWS).distinct()
    private val TRANSIENT_ADVISOR_ERRORS = setOf("douzero_unreachable", "douzero_bad_reply")
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
