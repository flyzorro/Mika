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
    /** Mika 屏上大厅右下角的快速开始。 */
    private const val QUICK_START_X = 2140
    private const val QUICK_START_Y = 995
    /** Mika 屏上闯关页的报名。 */
    private const val STAGE_BUTTON_Y = 920
    private const val AUTO_CANCEL_MS = 600L
    /** 轮到自己时别家的牌还没落下最多再等几帧(约 1.2 秒,倒计时有 15 秒)。 */
    private const val SETTLE_POLLS = 8
    private const val NO_HAND_POLLS = 60
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
        var advisor: String? = null
        var tracker: DdzTracker? = null
        val decisions = mutableListOf<Long>()
    }

    /** 记下这是单机局、DouZero 在哪;回到大厅或换了 App 由调用方清掉。 */
    internal fun markSingle(ctx: ToolContext, advisor: String) {
        val turn = ctx[STATE] ?: Turn().also { ctx[STATE] = it }
        turn.single = true
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

    override val verb = Verb.PhoneGame
    override val risk = Risk.Write
    override val def = ToolDef("phone_ddz_turn", Prompts.tool("phone_ddz_turn"), mapOf(
        "action" to buildJsonObject {
            put("type", "string")
            put("enum", JsonArray(listOf("start", "play", "pass", "hint", "call", "again", "wait").map(::JsonPrimitive)))
        },
        "advisor" to buildJsonObject { put("type", "string"); put("description", "start: DouZero service URL, e.g. http://host:3426.") },
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
        val turn = ctx[STATE] ?: Turn().also { ctx[STATE] = it }
        if (ctx.screen.peek().packageName != PACKAGE) {
            turn.single = false
            return refused("wrong_app")
        }
        val action = a.str("action") ?: throw ArgError("action", "required")
        // 模型在两次调用之间自己点过屏幕(关弹窗,也可能回大厅进了真人场):这里看不到它去过哪,单机标记作废,要再 start
        if (turn.single && ctx.evidence.actions != turn.actionsSeen) turn.single = false
        if (action == "start") return start(a, ctx, turn).also { turn.actionsSeen = ctx.evidence.actions }
        val view = takeBackControl(read(ctx) ?: return refused("no_frame"), ctx)
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
        val shotStart = now()
        val needsLook = next.phase !in setOf(DdzPhase.Result, DdzPhase.AutoPlay) && ctx.config.visionEnabled
        val shot = if (needsLook) ctx.screen.quickShot() else null
        val shotMs = now() - shotStart
        turn.seenAt = now()
        turn.actionsSeen = ctx.evidence.actions
        return ToolExecution(buildJsonObject {
            put("ok", move.error == null)
            move.error?.let { put("error", it); put("recoverable", true) }
            move.note?.let { put("note", it) }
            put("phase", next.phase.name.lowercase())
            put("handCount", next.handCount)
            if (turn.decisions.isNotEmpty()) {
                // DouZero 这次替模型打了几手、每手从轮到自己到牌出去花了多久
                put("autoPlayed", turn.decisions.size)
                put("autoMs", JsonArray(turn.decisions.map(::JsonPrimitive)))
                turn.decisions.clear()
            }
            turn.tracker?.broken?.let { put("douzeroOff", it) }
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
        val page = ctx.screen.rawFrame(DdzCards.LOBBY_ROWS + DdzScreen.ROWS) ?: return refused("no_frame")
        // 已经坐在闯关的牌桌上(报完名才问到这个工具):还在叫分、加倍就从这里接,出过牌了历史接不上
        val seated = DdzCards.singleStageTable(page)
        if (seated && DdzScreen.read(page).phase !in setOf(DdzPhase.Call, DdzPhase.Double)) return refused("game_already_started")
        val stage = if (DdzCards.singleStage(page)) DdzCards.stageButton(page) ?: return refused("no_stage_button") else null
        val tap = when {
            seated -> null
            DdzCards.singleLobby(page) -> QUICK_START_X to QUICK_START_Y
            stage != null -> stage.first to STAGE_BUTTON_Y
            else -> return refused("not_single_player_lobby")
        }
        if (tap != null && !ctx.screen.quickTap(tap.first, tap.second)) return refused("tap_failed")
        markSingle(ctx, advisor)
        turn.atTable = seated
        turn.tracker = null
        // 继续闯关可能回到打了一半的一手:头一帧看手牌张数再定
        turn.joining = stage?.second == true
        val next = awaitDecision(ctx, turn, DdzPhase.Other, moved = true, polls = AGAIN_POLLS)
        turn.seenAt = now()
        return ToolExecution(buildJsonObject {
            put("ok", true)
            put("phase", next.phase.name.lowercase())
            put("handCount", next.handCount)
            turn.tracker?.broken?.let { put("douzeroOff", it) }
        }, image = if (ctx.config.visionEnabled) ctx.screen.quickShot() else null, changed = true)
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
                empty = if (view.handCount == 0 && view.phase == DdzPhase.Other) empty + 1 else 0
                if (empty > NO_HAND_POLLS) return view
                if (view.phase != DdzPhase.Other) turn.atTable = true
                // 进过牌桌又回了大厅:下一局可能进的是真人场,单机标记作废,要再 start 一次
                if (turn.single && turn.atTable && view.phase == DdzPhase.Other && DdzCards.singleLobby(frame)) { turn.single = false; return view }
                if (turn.single) track(turn, frame, view)
                when (view.phase) {
                    DdzPhase.AutoPlay -> {
                        left = true
                        // 等的时候前台可能换了,点之前再认一次;点了等它退出托管,别连着点
                        if (ctx.screen.peek().packageName == PACKAGE && ctx.screen.quickTap(view.buttons[0].x, view.buttons[0].y)) delay(AUTO_CANCEL_MS)
                    }
                    // 加倍只有约 5 秒,模型来不及,让它走完就是不加倍
                    DdzPhase.Other, DdzPhase.Double -> left = true
                    DdzPhase.Result -> {
                        turn.tracker = null
                        if (left || view.phase != from) return view
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

    /** 把这一帧喂给这一局的出牌历史;叫分时还没开局,换一局重新记。 */
    private fun track(turn: Turn, frame: com.wangzan101.mika.core.uistate.RawFrame, view: DdzView) {
        if (view.phase == DdzPhase.Call) { turn.tracker = null; turn.expectedHand = null; turn.joining = false; return }
        // 农民 17 张、地主 20 张说明自己还没出过牌,在那之前的出牌都还摆在桌上,接得上;别的张数是打了一半,这一手不交给 DouZero
        if (turn.joining && view.handCount > 0) {
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
        tracker.observe(seat, { DdzCards.play(frame, it) }, { DdzCards.bottom(frame) }, view.phase == DdzPhase.Lead && view.handCount == 20)
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
        if (!tracker.ready() && tracker.broken == null && turn.settling++ < SETTLE_POLLS) return Auto.Settling
        turn.settling = 0
        val started = now()
        val hand = DdzCards.hand(frame, view)
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
            val done = if (cards.isEmpty()) view.phase == DdzPhase.Follow && press(view.buttons[0], ctx).moved
            else pick(hand!!, cards)?.let { select(it, ctx) }?.let { confirm(view, ctx) } == true
            if (done) {
                tracker.mine(cards)
                turn.expectedHand = hand!!.toMutableList().apply { cards.forEach { remove(it) } }
                turn.decisions += now() - started
                return Auto.Played
            }
            tracker.fail("douzero_move_failed")
        } else tracker.fail(failure ?: "douzero_unknown")
        // 这一手保底,剩下的交还模型
        if (view.phase == DdzPhase.Follow) hint(view, ctx) else smallest(view, ctx)
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
