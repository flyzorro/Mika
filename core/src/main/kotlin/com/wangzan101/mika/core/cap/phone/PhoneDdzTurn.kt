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
    /** 真人局每手 15 秒:等别家的牌读稳最多这么久(按时间,读一帧比 POLL_MS 慢),剩下的时间交给模型。 */
    private const val HUMAN_SETTLE_MS = 6_000L
    /** 真人牌桌切换/发牌时可能短暂读不到正面标记;重试一小段,别立刻交还模型。 */
    private const val HUMAN_SCENE_RETRY_POLLS = 12
    /** start(auto) 刚点完快速开始/继续:真人场匹配的倒计时就有 30 秒,最多等约 45 秒牌桌出来。 */
    private const val START_TABLE_POLLS = 300
    /** 弹窗(含约 30 秒的激励视频)最多处理这么多轮;每轮至少 [POLL_MS]。 */
    private const val POPUP_POLLS = 800
    private const val POPUP_TAP_MS = 1000L
    /** 再来一局几秒就发牌;过了这么久还没有多半是弹窗,交给模型看。 */
    private const val AGAIN_POLLS = 60
    /** Mika 屏上大厅右下角的快速开始(单机经典场,每局押金币)。 */
    private const val QUICK_START_X = 2140
    private const val QUICK_START_Y = 995
    /** Mika 屏上游戏主大厅中间橙色「真人对战」卡片的中心。 */
    private const val HUMAN_ENTRY_X = 1285
    private const val HUMAN_ENTRY_Y = 560
    /** Mika 屏上商店页左上角的返回箭头:金豆输光时冷启动会开在这一页,别处点哪儿都要花钻石,只点它。 */
    private const val HUMAN_SHOP_BACK_X = 127
    private const val HUMAN_SHOP_BACK_Y = 82
    /** 连点几次返回还停在商店页就交还模型,不再点。 */
    private const val HUMAN_SHOP_BACK_TAPS = 3
    /** Mika 屏上真人场次页绿色「普通场」卡片的中心(5 万-200 万金豆)。 */
    private const val HUMAN_NORMAL_ROOM_X = 872
    private const val HUMAN_NORMAL_ROOM_Y = 400
    /** 同一排青色「精英场」卡片的中心(200 万-6000 万金豆):豆豆过了普通场上限,点普通场只会弹「去高级房间」。 */
    private const val HUMAN_ELITE_ROOM_X = 1360
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
        var settleSince = 0L
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
        "room" to buildJsonObject {
            put("type", "string")
            put("enum", JsonArray(listOf("normal", "elite").map(::JsonPrimitive)))
            put("description", "start human mode with auto=true: which classic room to sit down in, normal (普通场, 5万-200万, default) or elite (精英场, 200万-6000万, much higher stakes).")
        },
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
        // 真人局只在经典牌桌:不判闯关(整手牌被点高时会误判)
        var initialView = DdzScreen.read(frame, classic = turn.mode == DdzMode.Human)
        if (turn.mode == DdzMode.Human && !DdzCards.classicHumanTable(frame)) {
            val next = if (humanHandoff(frame) != DdzSessionReason.LOBBY_RETURN) recoverHumanScene(ctx, turn) else frame
            if (next == null || !DdzCards.classicHumanTable(next)) {
                turn.state = DdzSessionState.Paused
                turn.reason = next?.let(::humanHandoff) ?: DdzSessionReason.UNKNOWN_SCENE
                turn.humanEligible = false
                return paused(turn)
            }
            frame = next
            initialView = DdzScreen.read(next, classic = true)
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
        // 漏了一手、或这一局已由工具按简单规则打:模型慢了一步(轮次已过)之类的错误照实报回去,不暂停,模型接着 wait / 出牌
        val playsByRules = turn.mode == DdzMode.Human && finishByRules(turn.tracker?.broken)
        if (!turn.stopped && turn.state != DdzSessionState.Paused && turn.mode == DdzMode.Human && move.error != null && !playsByRules) {
            turn.state = DdzSessionState.Paused
            turn.reason = pauseReason(move.error)
        } else if (playsByRules && !turn.stopped && turn.state != DdzSessionState.Paused && next.phase == DdzPhase.Other) {
            turn.phase = next.phase
            turn.state = DdzSessionState.Playing
            turn.reason = null
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
     * 只在确认是单机的入口点开始,并记下这是单机局;从这一局起 DouZero 替模型出牌。入口有三个:大厅里标着「单机模式」的
     * 快速开始(要金币)、单机「经典模式」场次页的快速开始(同样押金币,场次由游戏挑),和单机模式里闯关页的报名
     * (首次免费,过关奖励金币)。
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
        // 安卓的兼容小窗里游戏没铺满全屏,认牌的坐标全都不对:单独报出来,让模型先退出小窗
        if (letterboxed(page)) return refused("letterboxed")
        // 单机的「经典模式」场次页也有场次卡,但不是真人场:直接拒绝,一下都不点
        if (DdzCards.singleClassicRooms(page)) return refused("single_player_rooms")
        // Human mode may only attach to the current classic table. Stage tables and the
        // single-player lobby share the same button colors, so the explicit markers are a
        // required eligibility gate before any human-mode state is installed.
        // 选场先校验,认不得的场次一下都不点
        val roomX = when (a.str("room")?.trim()?.lowercase() ?: "normal") {
            "normal" -> HUMAN_NORMAL_ROOM_X
            "elite" -> HUMAN_ELITE_ROOM_X
            else -> throw ArgError("room", "must be normal or elite")
        }
        if (!DdzCards.classicHumanTable(page)) {
            if (!a.bool("auto", false)) return refused("not_human_game")
            // 在主大厅点「真人对战」、在真人场次页点选的场次(默认普通场),再等匹配和牌桌出来
            val entry = awaitHumanEntry(ctx, turn, page, roomX)
            page = entry.frame ?: return refused(entry.error ?: "not_human_game")
        }
        val view = DdzScreen.read(page, classic = true)
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
            // 单机的「经典模式」场次页:同样点右下角的快速开始,场次由游戏按当前金币挑,绝不点场次卡
            DdzCards.singleClassicRooms(page) -> QUICK_START_X to QUICK_START_Y
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
        val sent = if (error == null) submit(want!!, view, ctx, turn) else null
        if (sent == Sent.Played) return Move(true)
        // 出去的不是要的牌(或看不出出了什么):不能再保底出一手,照实报
        if (sent == Sent.Drift) return Move(false, "hand_drift")
        // 模型读错、出的牌不合规:还来得及就让它改一次,来不及或改过了就保底
        val reason = error ?: if (sent == Sent.SelectFailed) "select_failed" else "rejected"
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
        return when (confirm(view, after.lifted.size, null, ctx, turn)) {
            Sent.Played -> Move(true)
            Sent.Drift -> Move(false, "hand_drift")
            else -> press(view.buttons[0], ctx, turn)
        }
    }

    private suspend fun smallest(view: DdzView, ctx: ToolContext, turn: Turn): Move {
        return when (submit(setOf(view.handCount - 1), view, ctx, turn)) {
            Sent.Played -> Move(true)
            Sent.SelectFailed -> Move(false, "select_failed")
            Sent.Drift -> Move(false, "hand_drift")
            Sent.Rejected -> Move(false, "rejected")
        }
    }

    private enum class Sent { Played, SelectFailed, Rejected, Drift }

    /**
     * 出手牌里下标为 [want] 的几张:选牌,再按选牌前 [view] 里的「出牌」。所有出牌(DouZero 的、模型的、出最小的)都走这里。
     * 选好后读一次手牌,出完要看到手里正好少了这几张才算出去([confirm])。
     */
    private suspend fun submit(want: Set<Int>, view: DdzView, ctx: ToolContext, turn: Turn?): Sent {
        val selected = select(want, ctx, turn) ?: return Sent.SelectFailed
        if (selected.phase != view.phase || selected.handCount != view.handCount) return Sent.Drift
        val before = ctx.screen.rawFrame(FULL_ROWS)?.let { frame ->
            DdzScreen.read(frame, classic = turn?.mode == DdzMode.Human).takeIf { it.lifted == want && it.handCount == view.handCount }?.let { DdzCards.hand(frame, it) }
        }
        val left = before?.filterIndexed { index, _ -> index !in want }
        return confirm(view, want.size, left, ctx, turn)
    }

    /**
     * 按出牌,看手牌确认这 [count] 张出去了:张数少了 [count] 张、读得出手牌时剩下的正好是 [left] 才算出去。
     * 抬牌动画里按下去游戏可能不认,手牌一点没动就再按一次;还不动是不合规。认不出的帧接着看,一直看不出出了什么、
     * 或者少的不是这几张,是 [Sent.Drift]:不能当成出去了记进历史。
     * 用选牌前的 [view] 里的位置:按钮不动,而选完那一帧可能还在动画里、按钮认不全。跟牌要认出三个按钮、先出要一个,
     * 否则最后一个不一定是「出牌」(可能是「提示」),不按。
     */
    private suspend fun confirm(view: DdzView, count: Int, left: List<String>?, ctx: ToolContext, turn: Turn? = null): Sent {
        // 只剩不出时最后一个按钮就是不出,不能当出牌按
        if (DdzScreen.onlyPass(view)) return Sent.Rejected
        val buttons = if (view.phase == DdzPhase.Follow) 3 else 1
        if (view.buttons.size != buttons) return Sent.Rejected
        repeat(2) {
            if (!tap(ctx, turn, view.buttons.last().x, view.buttons.last().y)) return Sent.Rejected
            delay(VERDICT_MS)
            val verdict = verify(view, count, left, ctx)
            if (verdict != null) return verdict
        }
        return Sent.Rejected
    }

    /** 按了出牌之后看手牌:出去了、少的不对(Drift),或者一点没动(null,可以再按一次)。 */
    private suspend fun verify(view: DdzView, count: Int, left: List<String>?, ctx: ToolContext): Sent? {
        repeat(HUMAN_SCENE_RETRY_POLLS) {
            val frame = ctx.screen.rawFrame(FULL_ROWS)
            val after = frame?.let(DdzScreen::read)
            if (frame != null && after != null) {
                when {
                    // 出完最后几张,一局结束;真人场直接换成离开/换桌/继续的结算页,它认成 Other(那一页也会数出一排「手牌」)
                    after.phase == DdzPhase.Result || DdzCards.humanResult(frame) -> return Sent.Played
                    after.handCount == view.handCount - count -> {
                        if (left == null) return Sent.Played
                        // 牌还在动的那几帧认不出,接着看
                        DdzCards.hand(frame, after)?.let { return if (it.sorted() == left.sorted()) Sent.Played else Sent.Drift }
                    }
                    // 认得出的画面上手牌一张没少、还轮到我:这一下游戏没认,再按一次
                    after.handCount == view.handCount && after.phase == view.phase -> return null
                }
            }
            delay(POLL_MS)
        }
        // 一直看不出出了什么:不能当成出去了
        return Sent.Drift
    }

    /** 把抬起的牌改成 [want];点完看画面,没到位再补一次。 */
    private suspend fun select(want: Set<Int>, ctx: ToolContext, turn: Turn? = null): DdzView? {
        repeat(2) {
            val view = decisionView(ctx, turn) ?: return null
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

    /** 选牌的起点:出牌/跟牌的画面。真人局等过几帧过渡(刚当地主插底牌之类),单机局照旧只看一帧。 */
    private suspend fun decisionView(ctx: ToolContext, turn: Turn?): DdzView? {
        val tries = if (turn?.mode == DdzMode.Human) HUMAN_SCENE_RETRY_POLLS else 1
        repeat(tries) { i ->
            read(ctx, turn)?.takeIf { it.phase == DdzPhase.Follow || it.phase == DdzPhase.Lead }?.let { return it }
            if (turn?.state == DdzSessionState.Paused || turn?.stopped == true) return null
            if (i < tries - 1) delay(POLL_MS)
        }
        return null
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
        var popupBudget = POPUP_POLLS
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
                    // 真人场结算页(离开/换桌/继续):这一局打完了,不是弹窗
                    if (DdzCards.humanResult(frame)) return view.copy(phase = DdzPhase.Result)
                    if (!DdzCards.classicHumanTable(frame)) {
                        val handoff = humanHandoff(frame)
                        // 牌桌上盖了广告(进桌、结算时):工具自己关,不停下
                        if (handoff != DdzSessionReason.LOBBY_RETURN && (handoff != null || humanSceneMisses >= 2) && popupBudget-- > 0 && clearPopup(ctx, turn, frame)) {
                            humanSceneMisses = 0
                            waitOrSteer { delay(POLL_MS) } ?: return last
                            continue
                        }
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
                        turn.settleSince = now()
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
    }

    private enum class Auto { Played, Settling, Off }

    /** Human mode keeps the same advisor protocol as single-player while refusing to act on an
     * unconfirmed table, hand, or visible history. */
    private suspend fun byHuman(
        ctx: ToolContext, turn: Turn, frame: com.wangzan101.mika.core.uistate.RawFrame, view: DdzView,
    ): Auto {
        val tracker = turn.tracker ?: return humanPause(turn, DdzSessionReason.HISTORY_MISMATCH).let { Auto.Off }
        val advisor = turn.advisor ?: return humanPause(turn, DdzSessionReason.SERVICE_UNAVAILABLE).let { Auto.Off }
        // 漏了一手、或别家的一手一直读不到/读不准:这一局剩下的由工具按简单规则打完(模型常常比 15 秒慢);
        // 下一局叫分时历史重来,又交给 DouZero
        if (finishByRules(tracker.broken)) return fallback(ctx, turn, tracker, frame, view)
        if (tracker.broken != null) return humanPause(turn, pauseReason(tracker.broken!!)).let { Auto.Off }
        val hand = DdzCards.hand(frame, view)
        // 别家的牌还没读到:等它读稳,用满了就是漏了一手
        if (!tracker.ready()) {
            if (turn.humanSettling++ < HUMAN_SETTLE_POLLS && now() - turn.settleSince < HUMAN_SETTLE_MS) return Auto.Settling
            // 别家的这一手一直读不稳:不干等到游戏托管,和漏了一手一样按简单规则打完这一局
            tracker.fail(DdzSessionReason.MISSED_PLAY)
            return fallback(ctx, turn, tracker, frame, view)
        }
        // 自己的手牌一时认不出:上一手是 DouZero 出的、张数又对得上,就按该剩下的牌接着问 DouZero
        val expected = turn.expectedHand?.takeIf { it.isNotEmpty() && it.size == view.handCount }
        val played = hand ?: expected
        if (played == null) {
            if (turn.humanSettling++ < HUMAN_SETTLE_POLLS && now() - turn.settleSince < HUMAN_SETTLE_MS) return Auto.Settling
            // 别家的牌读到了、自己的手牌一直认不出:这一局交给简单规则,不记成漏了一手
            tracker.fail(DdzSessionReason.UNREAD_HAND)
            return fallback(ctx, turn, tracker, frame, view)
        }
        turn.humanSettling = 0
        if (hand != null && turn.expectedHand?.sorted() != null && turn.expectedHand!!.sorted() != hand.sorted()) {
            // 认到的牌和上一手该剩的不一样:这一局剩下的按简单规则打完,不停下把这一手交给托管
            tracker.fail(DdzSessionReason.HAND_DRIFT)
            return fallback(ctx, turn, tracker, frame, view)
        }
        // A transient service failure gets one quick retry; a replay mismatch is deterministic.
        val play = askDouZero(advisor, tracker, played).let { first ->
            if (first.exceptionOrNull()?.message in TRANSIENT_ADVISOR_ERRORS) askDouZero(advisor, tracker, played) else first
        }
        val cards = play.getOrElse {
            // DouZero 连不上、不认这段历史:这一局剩下的按简单规则打完,下一局再交给它
            tracker.fail(it.message ?: "douzero_unreachable")
            return fallback(ctx, turn, tracker, frame, view)
        }
        if (cards.any { it !in DdzCards.RANKS }) {
            tracker.fail("douzero_bad_reply")
            return fallback(ctx, turn, tracker, frame, view)
        }
        // An empty answer means pass only when following. A lead requires an explicit advisor move;
        // guessing a card here can silently play the wrong hand.
        val chosen = cards
        if (chosen.isEmpty()) {
            if (view.phase != DdzPhase.Follow) {
                // 先出时 DouZero 没给明确的牌:猜一张可能出成别的牌,这一局剩下的按简单规则打
                tracker.fail(DdzSessionReason.INVALID_ADVISOR_PLAY)
                return fallback(ctx, turn, tracker, frame, view)
            }
            val button = view.buttons.firstOrNull() ?: return humanPause(turn, DdzSessionReason.REJECTED_ACTION).let { Auto.Off }
            if (!press(button, ctx, turn).moved) {
                // 「不出」按不下去:这一局剩下的按简单规则打完
                tracker.fail("tap_failed")
                return fallback(ctx, turn, tracker, frame, view)
            }
            tracker.mine(emptyList())
            turn.expectedHand = played
            return Auto.Played
        }
        val want = pick(played, chosen) ?: run {
            tracker.fail("douzero_move_failed_pick")
            return fallback(ctx, turn, tracker, frame, view)
        }
        when (submit(want, view, ctx, turn)) {
            Sent.Played -> Unit
            Sent.SelectFailed -> {
                tracker.fail("select_failed")
                return fallback(ctx, turn, tracker, frame, view)
            }
            Sent.Drift -> {
                tracker.fail(DdzSessionReason.HAND_DRIFT)
                return fallback(ctx, turn, tracker, frame, view)
            }
            Sent.Rejected -> {
                tracker.fail("confirm_failed")
                return fallback(ctx, turn, tracker, frame, view)
            }
        }
        tracker.mine(chosen)
        turn.expectedHand = played.toMutableList().apply { chosen.forEach { remove(it) } }
        return Auto.Played
    }

    /**
     * 这一局的历史记不全、DouZero 用不上时的出法:跟牌时桌上最大的是队友的就不出,是对手的就按游戏的「提示」
     * (压不了就不出);先出就出最小的一张。出牌照样走 [submit] 核对,出不去就停下。
     */
    private suspend fun fallback(
        ctx: ToolContext, turn: Turn, tracker: DdzTracker, frame: com.wangzan101.mika.core.uistate.RawFrame, view: DdzView,
    ): Auto {
        val move = when (view.phase) {
            DdzPhase.Lead -> smallest(view, ctx, turn)
            DdzPhase.Follow -> {
                // 轮到我跟:左家出了牌,桌上最大的就是他的;左家不出,就是右家的
                val top = when (DdzCards.play(frame, DdzSide.Left)) {
                    DdzSeen.Pass, DdzSeen.Empty -> DdzSeat.Right
                    else -> DdzSeat.Left
                }
                val lord = tracker.landlord
                val teammate = lord != null && lord != DdzSeat.Me && top != lord
                if (teammate) press(view.buttons[0], ctx, turn) else hint(view, ctx, turn)
            }
            else -> return Auto.Settling
        }
        if (!move.moved) {
            // 前台换了之类的暂停已经记下了,别用 unknown_scene 盖掉
            if (turn.state == DdzSessionState.Paused) return Auto.Off
            return humanPause(turn, pauseReason(move.error ?: "rejected")).let { Auto.Off }
        }
        turn.expectedHand = null
        return Auto.Played
    }

    private fun humanPause(turn: Turn, reason: String) {
        turn.state = DdzSessionState.Paused
        turn.reason = reason
    }

    /** 从大厅/场次页走进真人牌桌的结果:[frame] 是牌桌;[error] 是要交还模型的明确画面。 */
    private class HumanEntry(val frame: com.wangzan101.mika.core.uistate.RawFrame?, val error: String? = null)

    /**
     * 从游戏主大厅点中间的「真人对战」、在真人场次页点横坐标 [roomX] 的场次卡(普通场或精英场),然后等匹配和牌桌;单机的经典场次页不点。
     * 路上真人场的领金豆弹窗自己领(只点免费的),单机救济金、看视频领取、广告由 [clearPopup] 收拾;领完回到场次页再点一次同一个场次。
     * 最多等约 45 秒(真人场匹配的倒计时就有 30 秒),弹窗另算。只认 Mika 的屏,认不出的画面一律不点。
     */
    private suspend fun awaitHumanEntry(
        ctx: ToolContext, turn: Turn, first: com.wangzan101.mika.core.uistate.RawFrame, roomX: Int,
    ): HumanEntry {
        var page: com.wangzan101.mika.core.uistate.RawFrame? = first
        var lobbyTapped = false
        var roomsTapped = false
        var resultTapped = false
        var shopTapped = 0
        var polls = 0
        var popupPolls = 0
        while (polls < START_TABLE_POLLS && popupPolls < POPUP_POLLS) {
            if (turn.stopped) return HumanEntry(null)
            val frame = page
            if (frame != null && DdzCards.classicHumanTable(frame)) return HumanEntry(frame)
            // 单机的「经典模式」场次页:不是真人场,绝不点
            if (frame != null && DdzCards.singleClassicRooms(frame)) return HumanEntry(null, "single_player_rooms")
            // 豆豆太多点了低级场:只点右上角的叉关弹窗(绝不点「立即前往」),然后交还模型换更高的场
            val tooMany = frame?.let { DdzCards.humanBeansTooMany(it) }
            if (tooMany != null) {
                tapOnTheWay(ctx, turn, tooMany.first, tooMany.second)
                return HumanEntry(null, DdzSessionReason.BEANS_TOO_MANY)
            }
            // 金豆输光后冷启动开在商店的金豆页:这页只有左上角的返回箭头不花钱,只点它,别的一概不点;
            // 连点几次还在这页就交还模型
            if (frame != null && DdzCards.humanShop(frame)) {
                if (shopTapped++ >= HUMAN_SHOP_BACK_TAPS) return HumanEntry(null)
                if (!tapOnTheWay(ctx, turn, HUMAN_SHOP_BACK_X, HUMAN_SHOP_BACK_Y)) return HumanEntry(null)
                polls++
                waitOrSteer { delay(POLL_MS) } ?: return HumanEntry(null)
                page = ctx.screen.rawFrame(FULL_ROWS)
                continue
            }
            shopTapped = 0
            // 真人场金豆不够:弹窗点「领取」,「恭喜获得」页点「普通领取」(绝不点带视频图标的「双倍领取」);
            // 弹窗盖在场次页上,要先于场次页认。领完回到场次页再点一次普通场
            val relief = frame?.let { DdzCards.humanReliefPopup(it) ?: DdzCards.humanReliefClaimed(it) }
            if (relief != null) {
                if (!tapOnTheWay(ctx, turn, relief.first, relief.second)) return HumanEntry(null)
                delay(POPUP_TAP_MS)
                popupPolls++
                polls = 0
                roomsTapped = false
                waitOrSteer { delay(POLL_MS) } ?: return HumanEntry(null)
                page = ctx.screen.rawFrame(FULL_ROWS)
                continue
            }
            // 真人场结算页(绿「离开」、蓝「换桌」、黄「继续」):只点一次绿色「离开」,绝不点「换桌」「继续」,
            // 点完接着认场次页;同一画面之后不再点
            if (frame != null && DdzCards.humanResult(frame)) {
                if (!resultTapped) {
                    val leave = DdzCards.humanResultLeave(frame) ?: return HumanEntry(null, DdzSessionReason.UNKNOWN_SCENE)
                    if (!tapOnTheWay(ctx, turn, leave.first, leave.second)) return HumanEntry(null)
                    resultTapped = true
                }
            } else if (frame != null && DdzCards.humanMainLobby(frame)) {
                if (!lobbyTapped) {
                    if (!tapOnTheWay(ctx, turn, HUMAN_ENTRY_X, HUMAN_ENTRY_Y)) return HumanEntry(null)
                    lobbyTapped = true
                }
            } else if (frame != null && DdzCards.humanClassicRooms(frame)) {
                if (!roomsTapped) {
                    if (!tapOnTheWay(ctx, turn, roomX, HUMAN_NORMAL_ROOM_Y)) return HumanEntry(null)
                    roomsTapped = true
                }
            } else if (clearPopup(ctx, turn, frame)) {
                // 救济金、看视频领取、广告:工具自己点完(模型一步十几秒,倒计时还会被浮层卡住);领完回场次页要再点普通场
                popupPolls++
                polls = 0
                roomsTapped = false
                waitOrSteer { delay(POLL_MS) } ?: return HumanEntry(null)
                page = ctx.screen.rawFrame(FULL_ROWS)
                continue
            } else if (frame != null && DdzCards.unknownPopup(frame)) {
                // 认不出的弹窗:不点,立刻交还模型
                return HumanEntry(null, DdzSessionReason.UNKNOWN_POPUP)
            }
            polls++
            waitOrSteer { delay(POLL_MS) } ?: return HumanEntry(null)
            page = ctx.screen.rawFrame(FULL_ROWS)
        }
        return HumanEntry(null)
    }

    /** start 途中自己点的入口卡:上次暂停留下的真人会话也允许点这一下,点完立刻失效。 */
    private suspend fun tapOnTheWay(ctx: ToolContext, turn: Turn, x: Int, y: Int): Boolean {
        turn.humanEligible = true
        return tap(ctx, turn, x, y)
    }

    /** 工具认得的弹窗:点一下,或者视频还在倒计时就等着;处理了为 true,调用方接着等。 */
    private suspend fun clearPopup(ctx: ToolContext, turn: Turn, frame: com.wangzan101.mika.core.uistate.RawFrame?): Boolean {
        val screen = ctx.screen.peek()
        if (screen.packageName != PACKAGE) return false
        // 广告是原生控件,看无障碍树就够;游戏里画的弹窗才要 OCR
        val native = screen.elements.any { it.role != "SurfaceView" }
        val texts = if (native) null else ctx.screen.readVisibleText()
        return when (val step = DdzPopups.next(screen, texts, frame)) {
            null -> false
            DdzPopups.Step.Wait -> true
            is DdzPopups.Step.Tap -> {
                if (turn.stopped || ctx.screen.peek().packageName != PACKAGE) return false
                ctx.screen.quickTap(step.x, step.y)
                delay(POPUP_TAP_MS)
                true
            }
        }
    }


    /**
     * Tracker failures that mean this hand's history can no longer be trusted, so the tool plays the rest of the hand
     * by simple rules instead of pausing: the game's 托管 or the turn clock would lose far more beans. A hand joined
     * mid-way too: the game keeps 托管 on into the next deal, so sitting it out never ends. DouZero comes back at the
     * next deal, when the tracker starts over at the call.
     */
    private fun finishByRules(broken: String?) = broken != null &&
        (broken == DdzSessionReason.MISSED_PLAY || broken == DdzSessionReason.UNREAD_HAND ||
            broken == DdzSessionReason.JOINED_MID_GAME || broken == DdzSessionReason.HAND_DRIFT ||
            broken == DdzSessionReason.INVALID_ADVISOR_PLAY ||
            broken.startsWith("unread_") || broken.startsWith("unsure_") ||
            broken.startsWith("douzero_") ||
            broken == "select_failed" || broken == "confirm_failed" || broken == "tap_failed")

    /** Known non-table scenes that end automatic human play; null when the scene is just unrecognised. */
    private fun humanHandoff(frame: com.wangzan101.mika.core.uistate.RawFrame): String? = when {
        DdzCards.humanResult(frame) -> null
        DdzCards.singleLobby(frame) -> DdzSessionReason.LOBBY_RETURN
        DdzCards.unknownPopup(frame) -> DdzSessionReason.UNKNOWN_POPUP
        else -> null
    }

    /** Wait out one short transition before calling a human-table scene unknown. Returns the first
     * readable table frame, the first lobby/popup frame (nothing left to wait for), or null. */
    private suspend fun recoverHumanScene(ctx: ToolContext, turn: Turn): com.wangzan101.mika.core.uistate.RawFrame? {
        var misses = 0
        var popupPolls = 0
        while (misses < HUMAN_SCENE_RETRY_POLLS && popupPolls < POPUP_POLLS) {
            waitOrSteer { delay(POLL_MS) } ?: return null
            val frame = ctx.screen.rawFrame(FULL_ROWS)
            if (frame != null && DdzCards.classicHumanTable(frame)) {
                if (DdzScreen.read(frame, classic = true).phase != DdzPhase.Other) return frame
            } else if (frame != null && humanHandoff(frame) != DdzSessionReason.LOBBY_RETURN && clearPopup(ctx, turn, frame)) {
                // 牌桌上盖着的广告:工具自己关掉再接着认
                popupPolls++
                misses = 0
                continue
            } else if (frame != null && humanHandoff(frame) != null) {
                return frame
            }
            misses++
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
                    else -> when (submit(want, view, ctx, turn)) {
                        Sent.Played -> null
                        Sent.SelectFailed -> "select"
                        Sent.Rejected -> "confirm"
                        Sent.Drift -> "hand_drift"
                    }
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
        val reply = runCatching { advise(url, body) }.getOrNull()?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
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
        return DdzScreen.read(frame, classic = turn?.mode == DdzMode.Human)
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
    private val FULL_ROWS = (DdzScreen.ROWS + DdzCards.ROWS + DdzCards.LOBBY_ROWS + DdzPopups.ROWS).distinct()
    private val TRANSIENT_ADVISOR_ERRORS = setOf("douzero_unreachable", "douzero_bad_reply")
    private val SNAPSHOT_PHASES = setOf("call", "double", "lead", "follow")
    private val SNAPSHOT_SEATS = setOf("landlord", "landlord_up", "landlord_down")

    private fun wrongPhase(phase: DdzPhase) = Move(false, "wrong_phase", phase.name.lowercase())

    private fun refused(code: String) = ToolExecution(buildJsonObject {
        put("ok", false); put("error", code); put("recoverable", true)
    })

    /** 游戏没铺满 2412×1080(安卓的兼容小窗),所有按主屏量出来的坐标都对不上。 */
    private fun letterboxed(frame: com.wangzan101.mika.core.uistate.RawFrame) =
        frame.width != 2412 || frame.height != 1080

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
