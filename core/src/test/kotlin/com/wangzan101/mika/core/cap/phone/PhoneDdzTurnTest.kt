package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.run.SteerSignal
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.uistate.RawFrame
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.uistate.VisibleTextLine
import com.wangzan101.mika.core.uistate.toPx
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.imageio.ImageIO

class PhoneDdzTurnTest {
    /** 叫分默认不连网:服务连不上,按牌力的老规则叫。 */
    @org.junit.Before fun noBidService() { PhoneDdzTurn.adviseBid = { _, _ -> null } }

    private fun frame(name: String): RawFrame {
        val image = ImageIO.read(javaClass.getResourceAsStream("/ddz/$name.jpg")!!)
        // Same rows the phone reads (FULL_ROWS): submitting a play reads the hand back to verify it.
        return RawFrame(image.width, image.height, (DdzScreen.ROWS + DdzCards.ROWS + DdzCards.LOBBY_ROWS).distinct().associateWith { image.getRGB(0, it, image.width, 1, null, 0, image.width) })
    }

    private val blank = RawFrame(2412, 1080, (DdzScreen.ROWS + DdzCards.ROWS + DdzCards.LOBBY_ROWS).distinct().associateWith { IntArray(2412) })

    private open class Game(val app: String, val frames: ArrayDeque<RawFrame>, val base: FakeScreen = FakeScreen()) : ScreenPort by base {
        val taps = mutableListOf<Pair<Int, Int>>()
        var last: RawFrame? = null
        open override suspend fun peek() = ScreenSnapshot("p1", app, emptyList(), 2412, 1080)
        override suspend fun rawFrame(rows: List<Int>): RawFrame? { last = frames.removeFirstOrNull() ?: last; return last }
        open override suspend fun quickTap(x: Int, y: Int): Boolean { taps += x to y; return true }
    }

    /** After the first button tap (not 取消托管) it is an opponent's turn, then the hand ends on the human result screen. */
    private fun playsThenEnds(frames: List<RawFrame>) = object : Game(PhoneDdzTurn.PACKAGE, ArrayDeque(frames)) {
        override suspend fun quickTap(x: Int, y: Int): Boolean {
            taps += x to y
            if (y != 760 && taps.count { it.second != 760 } == 1) {
                this.frames.clear()
                this.frames.addAll(List(3) { full("human_right_landlord_joker") } + full("human_result_leave_change_continue"))
            }
            return true
        }
    }

    private suspend fun run(game: Game, json: String): ToolExecution {
        val dispatcher = toolDispatcher(game)
        return PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement(json).jsonObject), dispatcher.ctx)
    }

    private fun ToolExecution.text(key: String) = content[key]?.jsonPrimitive?.content

    @Test fun refusesEveryOtherApp() = runTest {
        val game = Game("com.tencent.mm", ArrayDeque(listOf(frame("call"))))
        val result = run(game, """{"action":"call","level":3}""")
        assertEquals("wrong_app", result.text("error"))
        assertTrue(game.taps.isEmpty())
    }

    @Test fun unrecognisedScreenIsNeverTapped() = runTest {
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(blank)))
        val result = run(game, """{"action":"hint"}""")
        assertEquals("wrong_phase", result.text("error"))
        assertTrue(game.taps.isEmpty())
    }

    @Test fun bidsThenSitsOutDoublingUntilTheNextDecision() = runTest {
        val double = frame("double")
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(frame("call"), blank, double, double, frame("lead"))))
        val result = run(game, """{"action":"call","level":0}""")
        assertEquals("lead", result.text("phase"))
        assertEquals("加倍阶段一下都不点", 1, game.taps.size)
    }

    @Test fun cancelsAutoPlayFirstWhenTheModelWasTooSlow() = runTest {
        val auto = frame("auto")
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(auto, frame("follow_lifted"), blank, frame("lead"))))
        val result = run(game, """{"action":"pass"}""")
        assertEquals("lead", result.text("phase"))
        assertEquals(DdzScreen.read(auto).buttons[0].let { it.x to it.y }, game.taps.first())
    }

    @Test fun samePhaseOnlyCountsAfterLeavingIt() = runTest {
        val follow = frame("follow_lifted")
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(follow, follow, follow, blank, frame("lead"))))
        val result = run(game, """{"action":"pass"}""")
        assertEquals("lead", result.text("phase"))
    }

    @Test fun handMismatchAsksTheModelOnceWithoutTapping() = runTest {
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(frame("lead"))))
        val result = run(game, """{"action":"play","hand":["3","3"],"play":["3"]}""")
        assertEquals("hand_mismatch", result.text("error"))
        assertTrue(game.taps.isEmpty())
    }

    @Test fun playTogglesOnlyTheCardsThatDiffer() = runTest {
        val lifted = frame("lead_lifted") // 第 14 张抬着
        val hand = List(15) { "3" }.toMutableList().also { it[10] = "4" }
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(lifted, lifted, blank, blank, frame("lead"))))
        val json = """{"action":"play","hand":${hand.joinToString(",", "[", "]") { "\"$it\"" }},"play":["4"]}"""
        run(game, json)
        val view = DdzScreen.read(lifted)
        assertTrue(game.taps.contains(view.cardX(10) to DdzScreen.CARD_Y))
        assertTrue(game.taps.contains(view.cardX(14) to DdzScreen.CARD_Y))
    }

    @Test fun cardsTheGameLiftedAsAHintAreNotPressedBackDown() = runTest {
        // 实测 2026-11-11 13:38:48:轮到我先出,游戏自己把 Q Q Q 7 抬起一截;要出 Q Q Q 10,只该点 10(抬)和 7(按下去)
        val hint = shot("human_hand_hint_raised_qqq107")
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(hint, hint, hint, fullBlank, fullBlank)))
        run(game, """{"action":"play","hand":["BJ","SJ","K","Q","Q","Q","10","7"],"play":["Q","Q","Q","10"]}""")
        val view = DdzScreen.read(hint)
        val cards = game.taps.filter { it.second == DdzScreen.CARD_Y }.map { it.first }.toSet()
        assertEquals(setOf(view.cardX(6), view.cardX(7)), cards)
    }

    @Test fun jokersTheGameLiftedAsAHintAreSeenAndPlayedWithoutTapping() = runTest {
        // 13:39:04:手里 BJ SJ 7 要王炸,游戏先把两张王抬了一截,点下去反而按回(三张里两张抬着,原来还当成了闯关牌桌)。
        // 用平放那一帧拼出来:两张王那一条往上挪 17 像素,7 接在后面,再往右是桌布。第二张王被 7 压住的那段露在 7 上方,
        // 连同 7 牌顶的阴影,照抄提示那一帧里抬起的 Q 压着没抬的 10 那一块(同样抬高 17)
        val image = ImageIO.read(javaClass.getResourceAsStream("/ddz/human_hand_jokers_flat.png")!!)
        val hintImage = ImageIO.read(javaClass.getResourceAsStream("/ddz/human_hand_hint_raised_qqq107.png")!!)
        val rows = (DdzScreen.ROWS + DdzCards.ROWS + DdzCards.LOBBY_ROWS + DdzPopups.ROWS).distinct()
        val raised = RawFrame(image.width, image.height, rows.associateWith { y ->
            IntArray(image.width) { x ->
                when {
                    y < 560 -> image.getRGB(x, y)
                    x in 744 until 946 -> image.getRGB(x, minOf(y + 17, image.height - 1))
                    x in 946 until 1067 && y < 644 -> hintImage.getRGB(x + 404, y)
                    x in 946 until 1168 -> image.getRGB(x + 505, y)
                    x >= 1168 -> image.getRGB(100 + (x - 1168) % 600, y)
                    else -> image.getRGB(x, y)
                }
            }
        })
        assertEquals(setOf(0, 1), DdzScreen.read(raised).lifted)
        assertEquals("BJ SJ 7".split(' '), DdzCards.hand(raised, DdzScreen.read(raised)))
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(raised, raised, raised, fullBlank, fullBlank)))
        run(game, """{"action":"play","hand":["BJ","SJ","7"],"play":["BJ","SJ"]}""")
        assertTrue(game.taps.toString(), game.taps.none { it.second == DdzScreen.CARD_Y })
    }

    @Test fun againWaitsForTheResultScreenToLeave() = runTest {
        val result = frame("result")
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(result, result, result, blank, frame("call"))))
        assertEquals("call", run(game, """{"action":"again"}""").text("phase"))
        assertEquals(1, game.taps.size)
    }

    @Test fun waitBlocksUntilTheNextDecisionWithoutTapping() = runTest {
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(blank, blank, blank, frame("lead"))))
        assertEquals("lead", run(game, """{"action":"wait"}""").text("phase"))
        assertTrue(game.taps.isEmpty())
    }

    @Test fun waitReturnsAtOnceWhenItIsAlreadyYourTurn() = runTest {
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(frame("lead"))))
        assertEquals("lead", run(game, """{"action":"wait"}""").text("phase"))
    }

    @Test fun userMessageEndsTheWaitButKeepsTheMove() = runTest {
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(frame("follow_lifted"), blank, blank)))
        val result = withContext(SteerSignal(MutableStateFlow(true))) { run(game, """{"action":"pass"}""") }
        assertEquals("other", result.text("phase"))
        assertEquals(1, game.taps.size)
    }

    @Test fun cancelsAutoPlayAndCountsTheSameTurnAsNew() = runTest {
        val follow = frame("follow_lifted")
        val auto = frame("auto")
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(follow, follow, auto, follow)))
        assertEquals("follow", run(game, """{"action":"pass"}""").text("phase"))
        assertTrue(game.taps.contains(DdzScreen.read(auto).buttons[0].let { it.x to it.y }))
    }

    @Test fun leavesAutoPlayAloneOnceAnotherAppIsInFront() = runTest {
        val auto = frame("auto")
        val game = object : ScreenPort by Game(PhoneDdzTurn.PACKAGE, ArrayDeque()) {
            val taps = mutableListOf<Pair<Int, Int>>()
            var peeks = 0
            override suspend fun peek() = ScreenSnapshot("p", if (peeks++ == 0) PhoneDdzTurn.PACKAGE else "com.other", emptyList(), 2412, 1080)
            var reads = 0
            override suspend fun rawFrame(rows: List<Int>) = if (reads++ == 0) blank else auto
            override suspend fun quickTap(x: Int, y: Int): Boolean { taps += x to y; return true }
        }
        val dispatcher = toolDispatcher(game)
        PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
        assertTrue(game.taps.isEmpty())
    }

    @Test fun turnEndingWhileSelectingDoesNotCrash() = runTest {
        val lead = frame("lead")
        val hand = List(15) { "3" }.joinToString(",", "[", "]") { "\"$it\"" }
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(lead, lead, blank, blank, blank, frame("call"))))
        val result = run(game, """{"action":"play","hand":$hand,"play":["3"]}""")
        assertEquals("select_failed", result.text("error"))
        assertEquals("只点了牌还在时的那一下", 1, game.taps.size)
    }

    @Test fun holdsTheScreenGateLikeOtherTaps() {
        assertFalse(PhoneDdzTurn.composite)
    }

    @Test fun aPlayIsOnlyDoneWhenExactlyThoseCardsLeftTheHand() = runTest {
        // Live 2026-11-10 run 14: the screen changed after 出牌 but a different card had left the
        // hand; the move was recorded as played and the history went wrong (hand_drift one turn later).
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(frame("lead"), frame("lead_lifted"), frame("lead_lifted"), frame("lead_pressed"))))
        // Lead 3 (the smallest); the next frame shows three 3s gone, not one.
        val result = run(game, """{"action":"hint"}""")
        assertEquals(result.toString(), "hand_drift", result.text("error"))
    }

    @Test fun pressesPlayAgainWhenTheFirstPressWasIgnored() = runTest {
        val lead = frame("lead")
        val hand = (List(14) { "4" } + "3").joinToString(",", "[", "]") { "\"$it\"" }
        val lifted = frame("lead_lifted") // 最后一张已抬起,不用再选
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(lifted, lifted, lifted, blank, lead)))
        val result = run(game, """{"action":"play","hand":$hand,"play":["3"]}""")
        assertEquals("lead", result.text("phase"))
        assertEquals("两次出牌", 2, game.taps.count { it.second != DdzScreen.CARD_Y })
    }

    @Test fun playingTheLastCardsOntoTheHumanResultScreenIsAPlay() = runTest {
        // 实测 2026-11-11 15:52:最后一张 BJ 出去这局就完了,真人场直接换成离开/换桌/继续的结算页;
        // 那一页认成 Other、手牌 15 张,一直看不出出了什么,报成 hand_drift 停下
        val hand = (List(14) { "4" } + "3").joinToString(",", "[", "]") { "\"$it\"" }
        val lifted = frame("lead_lifted") // 最后一张已抬起,不用再选
        val result = full("human_result_leave_change_continue")
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(lifted, lifted, lifted) + List(20) { result }))
        val played = run(game, """{"action":"play","hand":$hand,"play":["3"]}""")
        assertNull(played.toString(), played.text("error"))
    }

    @Test fun againNeverTapsTheReliefPopup() = runTest {
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(frame("vd_relief"))))
        assertEquals("wrong_phase", run(game, """{"action":"again"}""").text("error"))
        assertTrue(game.taps.isEmpty())
    }

    @Test fun againHandsBackToTheModelSoonWhenAPopupAppears() = runTest {
        val relief = frame("vd_relief")
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(frame("vd_result_bust"), relief)))
        val started = testScheduler.currentTime
        assertEquals("other", run(game, """{"action":"again"}""").text("phase"))
        assertTrue(testScheduler.currentTime - started <= 10_000)
        assertEquals(1, game.taps.size)
    }

    /** 左家是地主、出了连对 10 10 9 9 8 8,我是他的下家,轮到我跟。 */
    private fun leftLedPairs() = DdzTracker().apply {
        val pairs = DdzSeen.Cards("10 10 9 9 8 8".split(' '))
        // 一手牌要连着几帧读得一样才算
        repeat(3) { observe(DdzSeat.Me, { if (it == DdzSide.Left) pairs else DdzSeen.Empty }, { listOf("J", "7", "4") }) }
    }

    private fun full(name: String): RawFrame {
        val image = ImageIO.read((javaClass.getResourceAsStream("/ddz/$name.jpg") ?: javaClass.getResourceAsStream("/ddz/$name.png"))!!)
        return RawFrame(image.width, image.height, (DdzScreen.ROWS + DdzCards.ROWS + DdzCards.LOBBY_ROWS + DdzPopups.ROWS).distinct().associateWith { image.getRGB(0, it, image.width, 1, null, 0, image.width) })
    }

    /** 无损 .png 的新截图(主大厅、场次页、匹配、弹窗);玩家认色和字形要用准色。 */
    private fun shot(name: String): RawFrame {
        val image = ImageIO.read((javaClass.getResourceAsStream("/ddz/$name.png") ?: javaClass.getResourceAsStream("/ddz/$name.jpg"))!!)
        return RawFrame(image.width, image.height, (DdzScreen.ROWS + DdzCards.ROWS + DdzCards.LOBBY_ROWS + DdzPopups.ROWS).distinct().associateWith { image.getRGB(0, it, image.width, 1, null, 0, image.width) })
    }

    /** 把 Mika 屏上手牌那段整体点高 30 像素(和 DdzScreenTest 一样):620 行大半是牌面,默认读法会认成闯关牌桌。 */
    private fun classicHandLifted(name: String): RawFrame {
        val image = ImageIO.read(javaClass.getResourceAsStream("/ddz/$name.png")!!)
        return RawFrame(image.width, image.height, (DdzScreen.ROWS + DdzCards.ROWS + DdzCards.LOBBY_ROWS + DdzPopups.ROWS).distinct().associateWith { y ->
            IntArray(image.width) { x -> if (y >= 560 && x in 744..1673) image.getRGB(x, y + 30) else image.getRGB(x, y) }
        })
    }

    private val fullBlank = RawFrame(2412, 1080, (DdzScreen.ROWS + DdzCards.ROWS + DdzCards.LOBBY_ROWS + DdzPopups.ROWS).distinct().associateWith { IntArray(2412) })

    /** hand_t1 这一手,从大到小;上一手 DouZero 出完后手里该剩的牌。 */
    private val handT1 = "SJ 2 2 2 A A A A Q J 10 9 9 8 7 3 3".split(' ')

    /** 在真实帧上改几笔,做出测试用的画面。 */
    private fun editedFrame(name: String, edit: (RawFrame, MutableMap<Int, IntArray>, DdzView) -> Unit): RawFrame {
        val image = ImageIO.read(javaClass.getResourceAsStream("/ddz/$name.jpg")!!)
        val rows = (DdzScreen.ROWS + DdzCards.ROWS + DdzCards.LOBBY_ROWS).distinct()
            .associateWith { image.getRGB(0, it, image.width, 1, null, 0, image.width) }.toMutableMap()
        val base = RawFrame(image.width, image.height, rows)
        edit(base, rows, DdzScreen.read(base))
        return RawFrame(image.width, image.height, rows)
    }

    /**
     * 手牌认不出的一帧:第一张牌的点数抹成牌面白,整手认牌返回 null,张数和按钮照旧。
     * [liftIndex] 抬起那张牌(只靠下标和几何,不靠点数);[noPlays] 抹掉两家的出牌区,让跟踪器一直认不出地主。
     */
    private fun unreadableHand(name: String = "hand_t1", liftIndex: Int? = null, noPlays: Boolean = false): RawFrame =
        editedFrame(name) { base, rows, view ->
            val bar = DdzScreen.blackBar(base)
            val k = (base.width - bar) / 2292.0
            // 牌顶以上抹成桌布、以下抹成牌面:炸弹火苗压在上面,会被当成这几张抬起了
            val handEnd = minOf(base.width, view.handLeft + ((view.handCount - 1) * view.pitch).roundToInt() + 222)
            val flatTop = if (bar > 0) 669 else 644
            for ((y, px) in rows) if (y in 600..680) for (x in view.handLeft until handEnd) px[x] = if (y < flatTop) 0xFF0B2B4A.toInt() else -1
            // 第一张牌的点数抹平:整手认牌返回 null(牌面还是白的,张数不变)
            for ((y, px) in rows) if (y in flatTop..716) for (x in view.handLeft until view.handLeft + 80) px[x] = -1
            if (liftIndex != null) {
                val x = view.handLeft + (liftIndex * view.pitch).roundToInt()
                // 这一张露出来的那一条往上多出 20 像素牌面:牌顶高出平放的就算抬起
                for ((y, px) in rows) if (y in flatTop - 20 until flatTop) for (i in x until x + view.pitch.toInt()) px[i] = -1
            }
            if (noPlays) {
                val spans = listOf(
                    (bar + 280 * k).toInt() until (bar + 880 * k).toInt(),
                    (bar + 1360 * k).toInt() until (bar + 1990 * k).toInt(),
                )
                for (y in 220..420) rows[y]?.let { px -> for (span in spans) for (x in span) px[x] = 0 }
            }
        }

    /** 同一帧上第一张牌没了:手牌少一张,用来核对出牌。 */
    private fun oneCardLess(name: String = "hand_t1"): RawFrame = editedFrame(name) { _, rows, view ->
        val gap = (view.pitch * 0.72).roundToInt()
        rows[DdzScreen.HAND_ROW]?.let { px -> for (x in view.handLeft until view.handLeft + gap) px[x] = 0xFF0B2B4A.toInt() }
    }

    /** 最后一张(最小)抬起来:出最小的一张时选牌对得上。 */
    private fun liftedLastCard(name: String = "lead"): RawFrame = editedFrame(name) { base, rows, view ->
        val flatTop = if (DdzScreen.blackBar(base) > 0) 669 else 644
        val x = view.handLeft + ((view.handCount - 1) * view.pitch).roundToInt()
        for ((y, px) in rows) if (y in flatTop - 20 until flatTop) for (i in x until x + view.pitch.toInt()) px[i] = -1
    }

    /** 同一帧上最后一张(最小)没了:手牌少一张,用来核对出了最小的一张。 */
    private fun withoutLastCard(name: String = "lead"): RawFrame = editedFrame(name) { _, rows, view ->
        val end = view.handLeft + ((view.handCount - 1) * view.pitch).roundToInt() + DdzScreen.CARD_WIDTH
        val from = end - view.pitch.roundToInt()
        for ((_, px) in rows) for (i in from until minOf(px.size, end + 4)) px[i] = 0xFF0B2B4A.toInt()
    }

    private fun readyFollowTracker() = DdzTracker(DdzSettlingPolicy.HUMAN).apply {
        restore(DdzSeat.Left, listOf("J", "7", "4"), listOf(listOf("10", "10", "9", "9", "8", "8")), current = DdzSeat.Me)
    }

    /** 左家是队友、右家是地主,轮到我跟:简单规则跟牌时就是不出。 */
    private fun teammateFollowTracker() = DdzTracker(DdzSettlingPolicy.HUMAN).apply {
        restore(DdzSeat.Right, listOf("J", "7", "4"), emptyList(), current = DdzSeat.Me)
    }

    @Test fun aSingleGameLetsDouZeroPlayAndKeepsGoing() = runTest {
        val asked = mutableListOf<String>()
        val saved = PhoneDdzTurn.advise
        PhoneDdzTurn.advise = { _, body -> asked += body; """{"play":[]}""" }
        try {
            val follow = full("hand_t1")
            val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(follow, follow, fullBlank, full("result"))))
            val dispatcher = toolDispatcher(game)
            PhoneDdzTurn.markSingle(dispatcher.ctx, "http://mac:3426")
            PhoneDdzTurn.useTracker(dispatcher.ctx, leftLedPairs())
            val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
            assertEquals("result", result.text("phase"))
            assertEquals("1", result.text("autoPlayed"))
            val body = Json.parseToJsonElement(asked.single()).jsonObject
            assertEquals("landlord_down", body["seat"]!!.jsonPrimitive.content)
            assertEquals("SJ 2 2 2 A A A A Q J 10 9 9 8 7 3 3".split(' '), body["hand"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(1, body["history"]!!.jsonArray.size)
            assertEquals("DouZero 说不出就按不出", DdzScreen.read(follow).buttons[0].let { it.x to it.y }, game.taps.single())
        } finally {
            PhoneDdzTurn.advise = saved
        }
    }

    @Test fun withoutTheSingleMarkDouZeroIsNeverAsked() = runTest {
        val saved = PhoneDdzTurn.advise
        PhoneDdzTurn.advise = { _, _ -> error("must not ask") }
        try {
            val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(full("hand_t1"))))
            val dispatcher = toolDispatcher(game)
            PhoneDdzTurn.useTracker(dispatcher.ctx, leftLedPairs())
            val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
            assertEquals("follow", result.text("phase"))
            assertTrue(game.taps.isEmpty())
        } finally {
            PhoneDdzTurn.advise = saved
        }
    }

    @Test fun whenDouZeroRefusesTheHistoryTheModelTakesOverThisVeryTurn() = runTest {
        val saved = PhoneDdzTurn.advise
        PhoneDdzTurn.advise = { _, _ -> """{"error":"history_mismatch"}""" }
        try {
            val follow = full("hand_t1")
            val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(follow, follow, follow)))
            val dispatcher = toolDispatcher(game)
            PhoneDdzTurn.markSingle(dispatcher.ctx, "http://mac:3426")
            PhoneDdzTurn.useTracker(dispatcher.ctx, leftLedPairs())
            val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
            assertEquals("douzero_history_mismatch", result.text("douzeroOff"))
            assertEquals("follow", result.text("phase"))
            assertTrue("不替模型按提示", game.taps.isEmpty())
        } finally {
            PhoneDdzTurn.advise = saved
        }
    }

    private fun lobbyFrame(name: String): RawFrame {
        val image = ImageIO.read(javaClass.getResourceAsStream("/ddz/$name.jpg")!!)
        return RawFrame(image.width, image.height, (DdzScreen.ROWS + DdzCards.ROWS + DdzCards.LOBBY_ROWS).distinct().associateWith { image.getRGB(0, it, image.width, 1, null, 0, image.width) })
    }

    @Test fun theSinglePlayerLobbyStartsAClassicGame() = runTest {
        // 单机大厅右下角的快速开始:经典场
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(lobbyFrame("vd_lobby"), fullBlank, full("call"))))
        assertEquals("true", run(game, """{"action":"start","advisor":"http://mac:3426"}""").text("ok"))
        assertEquals(2140 to 995, game.taps.first())
    }

    @Test fun theSinglePlayerClassicRoomsPageStartsFromItsQuickStart() = runTest {
        // 单机「经典模式」场次页的右下角快速开始:场次由游戏按当前金币挑,一下都不点场次卡
        for (name in listOf("single_classic_rooms", "single_classic_rooms_newbie")) {
            val page = shot(name)
            assertTrue(name, DdzCards.singleClassicRooms(page))
            val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(page, page, fullBlank, full("call"))))
            val result = run(game, """{"action":"start","advisor":"http://mac:3426"}""")
            assertEquals(name, "true", result.text("ok"))
            assertEquals(name, listOf(2140 to 995), game.taps.take(1))
            assertEquals(name, 1, game.taps.count { it == 2140 to 995 })
        }
    }

    @Test fun aSinglePlayerClassicHandLiftedByOurTapIsNotReadAsTheStageTable() = runTest {
        // 线上 2026-11-11 20:44:经典场(快速开始 新手场)6 张里 4 张点高 30 像素,620 行大半是牌面,
        // 默认读法把手牌行错到 644、抬起的牌读空:select 把已经抬起的牌又点下去,最后 select_failed。
        val lifted = classicHandLifted("human_hand_hint_raised_qqq107")
        assertEquals("默认读法当成闯关牌桌", emptySet<Int>(), DdzScreen.read(lifted).lifted)
        val classic = DdzScreen.read(lifted, classic = true)
        assertEquals((0 until classic.handCount).toSet(), classic.lifted)
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(List(4) { lifted }))
        val dispatcher = toolDispatcher(game)
        PhoneDdzTurn.markSingle(dispatcher.ctx, "http://mac:3426")
        val hand = List(classic.handCount) { "\"3\"" }.joinToString(",")
        val result = PhoneDdzTurn.execute(
            Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"play","hand":[$hand],"play":[$hand]}""").jsonObject),
            dispatcher.ctx,
        )
        assertNotEquals("select_failed", result.text("error"))
        assertTrue(game.taps.toString(), game.taps.none { it.second == DdzScreen.CARD_Y })
    }

    @Test fun aStageSessionKeepsReadingTheStageTableAsStage() = runTest {
        // 闯关牌桌的手牌整体高 32 像素:按经典牌桌读会把整手牌都当成抬起的;闯关局照旧按闯关读
        val call = lobbyFrame("vd_stage_call")
        val lead = lobbyFrame("vd_stage_lead")
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(call)))
        val dispatcher = toolDispatcher(game)
        PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        game.frames.addAll(List(4) { lead })
        val view = DdzScreen.read(lead)
        assertEquals(setOf(19), view.lifted)
        val hand = List(view.handCount) { "\"3\"" }.joinToString(",")
        PhoneDdzTurn.execute(
            Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"play","hand":[$hand],"play":["3"]}""").jsonObject),
            dispatcher.ctx,
        )
        assertTrue(game.taps.toString(), game.taps.contains(view.cardX(0) to DdzScreen.CARD_Y))
    }

    @Test fun aFinishedClassicHandIsPlayedAgainButTheReliefPopupGoesToTheModel() = runTest {
        // 打完一手的结算点再来一局;接着弹的救济金(双倍领取要看视频)不点,交给模型
        val bust = lobbyFrame("vd_result_bust")
        val relief = lobbyFrame("vd_relief")
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(lobbyFrame("vd_lobby"), bust) + List(5) { relief }))
        val result = run(game, """{"action":"start","advisor":"http://mac:3426","hands":3}""")
        assertEquals("1", result.text("handsPlayed"))
        assertEquals(listOf(2140 to 995, DdzScreen.read(bust).buttons[0].let { it.x to it.y }), game.taps)
    }

    @Test fun theLobbyLingeringRightAfterStartDoesNotCancelSinglePlayer() = runTest {
        val asked = mutableListOf<String>()
        val saved = PhoneDdzTurn.advise
        PhoneDdzTurn.advise = { _, body -> asked += body; """{"play":[]}""" }
        try {
            val lobby = lobbyFrame("vd_stage")
            val follow = full("hand_t1")
            val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(lobby, lobby, lobby, fullBlank, full("call"))))
            val dispatcher = toolDispatcher(game)
            val start = Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","advisor":"http://mac:3426"}""").jsonObject)
            assertEquals("call", PhoneDdzTurn.execute(start, dispatcher.ctx).text("phase"))
            // 叫完地主到了出牌:单机标记还在,DouZero 照样被问到
            game.frames.addAll(listOf(follow, follow, fullBlank, full("result")))
            PhoneDdzTurn.useTracker(dispatcher.ctx, leftLedPairs())
            PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
            assertEquals(1, asked.size)
        } finally {
            PhoneDdzTurn.advise = saved
        }
    }

    @Test fun startAlsoSignsUpOnTheSinglePlayerStagePage() = runTest {
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(lobbyFrame("vd_stage"), fullBlank, full("call"))))
        assertEquals("true", run(game, """{"action":"start","advisor":"http://mac:3426"}""").text("ok"))
        // 头一下是报名;后面的是进了牌桌自己叫的分
        assertTrue(game.taps[0].first in 1150..1250 && game.taps[0].second == 920)
    }

    @Test fun startContinuesAStageButNeverGivesItUp() = runTest {
        assertTrue(DdzCards.singleStage(lobbyFrame("vd_stage_continue")))
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(lobbyFrame("vd_stage_continue"), fullBlank, full("call"))))
        assertEquals("true", run(game, """{"action":"start","advisor":"http://mac:3426"}""").text("ok"))
        // 绿色的继续闯关在 710-1144,蓝色的放弃闯关从 1250 起;后面的是自己叫的分
        assertTrue(game.taps[0].first in 850..1000)
        assertTrue(game.taps.none { (x, y) -> x in 1250..1680 && y == 920 })
    }

    @Test fun waitingInTheLobbyReturnsAtOnce() = runTest {
        // 大厅一直在:等十来秒没有手牌就回来,不会等满一分多钟;后面那一帧牌桌不会被读到
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(List(62) { full("vd_lobby") } + full("lead")))
        assertEquals("other", run(game, """{"action":"wait"}""").text("phase"))
        assertTrue(game.taps.isEmpty())
    }

    @Test fun startTakesOverAtAStageTableThatIsStillBidding() = runTest {
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(lobbyFrame("vd_stage_call"), full("vd_stage_call"))))
        val result = run(game, """{"action":"start","advisor":"http://mac:3426"}""")
        assertEquals("call", result.text("phase"))
        // 已经在牌桌上,不点报名;叫分按牌力自己叫:双王、两个 2、QQQQ 炸弹、两个 A,叫三分(最右边的黄色按钮),只按一次
        assertEquals(1, game.taps.size)
        assertTrue(game.taps[0].first > 1700)
        assertTrue(DdzCards.singleStageTable(lobbyFrame("vd_stage_call")))
        assertFalse(DdzCards.singleStageTable(lobbyFrame("vd_hand_follow")))
    }

    @Test fun onTheStageTheToolDeclinesToDoubleAndNeverTapsTheVideoButton() = runTest {
        val double = full("vd_stage_double")
        val view = DdzScreen.read(double)
        assertEquals(DdzPhase.Double, view.phase)
        assertEquals(DdzColor.Blue, view.buttons.first().color)
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(double, double, double, fullBlank)))
        val dispatcher = toolDispatcher(game)
        PhoneDdzTurn.markSingle(dispatcher.ctx, "http://mac:3426")
        PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
        // 只点一次,点的是左边蓝色的不加倍
        assertEquals(listOf(view.buttons.first().let { it.x to it.y }), game.taps)
    }

    @Test fun theBidFollowsWhatDouZeroSaysTheHandIsWorth() = runTest {
        // 牌力规则会叫三分的一手,DouZero 自己打了几十局说当地主赢不了:不叫(最左边的蓝色按钮)
        val asked = mutableListOf<List<String>>()
        PhoneDdzTurn.adviseBid = { _, hand -> asked += hand; 0 }
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(lobbyFrame("vd_stage_call"), full("vd_stage_call"))))
        run(game, """{"action":"start","advisor":"http://mac:3426"}""")
        assertEquals(17, asked.single().size)
        assertEquals(1, game.taps.size)
        assertTrue(game.taps[0].first < 1000)
    }

    @Test fun continuingIntoAHalfPlayedHandLeavesItToTheModel() = runTest {
        val saved = PhoneDdzTurn.advise
        try {
            PhoneDdzTurn.advise = { _, _ -> """{"play":[]}""" }
            // 回到的是打了一半的一手(手里 7 张):DouZero 不接
            val half = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(lobbyFrame("vd_stage_continue"), full("vd_stage_leftpass_rightQQQ6"), fullBlank)))
            assertEquals("joined_mid_game", run(half, """{"action":"start","advisor":"http://mac:3426"}""").text("douzeroOff"))
        } finally {
            PhoneDdzTurn.advise = saved
        }
    }

    @Test fun startAtAStageTableHalfwayThroughLeavesTheHandToTheModel() = runTest {
        val saved = PhoneDdzTurn.advise
        try {
            PhoneDdzTurn.advise = { _, _ -> """{"play":[]}""" }
            val table = full("vd_stage_leftpass_rightQQQ6")
            val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(table, table, fullBlank)))
            assertEquals("joined_mid_game", run(game, """{"action":"start","advisor":"http://mac:3426"}""").text("douzeroOff"))
        } finally {
            PhoneDdzTurn.advise = saved
        }
    }

    @Test fun aNewDealAfterAHalfPlayedHandIsFollowedAgain() = runTest {
        val saved = PhoneDdzTurn.advise
        try {
            PhoneDdzTurn.advise = { _, _ -> """{"play":[]}""" }
            val half = full("vd_stage_leftpass_rightQQQ6")
            val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(half, half, fullBlank)))
            val dispatcher = toolDispatcher(game)
            fun args(json: String) = Args(PhoneDdzTurn.def, Json.parseToJsonElement(json).jsonObject)
            assertEquals("joined_mid_game", PhoneDdzTurn.execute(args("""{"action":"start","advisor":"http://mac:3426"}"""), dispatcher.ctx).text("douzeroOff"))
            // 这一手打完、桌上空了一阵,叫分在两次调用之间过去了:新发的 17 张接得上
            game.frames.addAll(List(10) { fullBlank } + listOf(full("vd_hand_follow"), fullBlank))
            PhoneDdzTurn.execute(args("""{"action":"wait"}"""), dispatcher.ctx)
            val next = PhoneDdzTurn.execute(args("""{"action":"wait"}"""), dispatcher.ctx)
            assertFalse(next.toString().contains("joined_mid_game"))
        } finally {
            PhoneDdzTurn.advise = saved
        }
    }

    @Test fun startForgetsTheHandLeftFromTheLastGame() = runTest {
        val saved = PhoneDdzTurn.advise
        try {
            PhoneDdzTurn.advise = { _, _ -> """{"play":[]}""" }
            val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(lobbyFrame("vd_stage_continue"), full("vd_hand_follow"), fullBlank)))
            val dispatcher = toolDispatcher(game)
            // 上一手打到只剩王和三张 5
            PhoneDdzTurn.useTracker(dispatcher.ctx, DdzTracker(), expectedHand = listOf("BJ", "5", "5", "5"))
            val start = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
            assertFalse(start.toString().contains("hand_drift"))
        } finally {
            PhoneDdzTurn.advise = saved
        }
    }

    @Test fun continuingIntoAFreshHandLetsDouZeroFollow() = runTest {
        val saved = PhoneDdzTurn.advise
        try {
            PhoneDdzTurn.advise = { _, _ -> """{"play":[]}""" }
            // 新发的一手(农民 17 张):接得上;发牌时手里还没 17 张(闯关的牌桌上别人出牌时也是 other)不算
            val fresh = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(lobbyFrame("vd_stage_continue"), full("vd_stage_right_clock"), full("vd_hand_follow"), fullBlank)))
            assertFalse(run(fresh, """{"action":"start","advisor":"http://mac:3426"}""").toString().contains("joined_mid_game"))
        } finally {
            PhoneDdzTurn.advise = saved
        }
    }

    @Test fun aWonStageHandIsContinuedOnlyWhenMoreHandsAreWanted() = runTest {
        val win = full("vd_stage_win_leave_continue")
        assertTrue(DdzCards.stageWin(win))
        assertTrue(DdzCards.stageWin(full("vd_stage_win_cleared")))
        assertFalse(DdzCards.stageWin(full("vd_stage_call")))
        // 没过关:左边蓝色离开、右边黄色继续,点继续
        assertTrue(DdzCards.stageContinue(win)!!.first in 1400..1560)
        assertTrue(DdzCards.stageContinue(full("vd_stage_win_cleared"))!!.first in 1130..1290)
        // 别家在出牌,工具等着;然后这一手赢了
        val table = full("vd_stage_right_clock")
        val more = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(table, table, win, fullBlank)))
        val two = run(more, """{"action":"start","advisor":"http://mac:3426","hands":2}""")
        assertTrue("点了继续", more.taps.any { (x, y) -> x in 1400..1560 && y == 765 })
        assertEquals("1", two.text("handsWon"))
        val once = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(table, table, win, fullBlank)))
        assertEquals("result", run(once, """{"action":"start","advisor":"http://mac:3426"}""").text("phase"))
        assertFalse("只打一手就不点继续", once.taps.any { (_, y) -> y == 765 })
    }

    @Test fun aLostStageHandIsContinuedTooButNeverLeft() = runTest {
        val loss = full("vd_stage_loss")
        assertTrue(DdzCards.stageLoss(loss))
        assertFalse(DdzCards.stageWin(loss))
        assertFalse(DdzCards.stageLoss(full("vd_stage_win_leave_continue")))
        assertFalse(DdzCards.stageLoss(full("vd_stage_call")))
        // 输了的结算:左边蓝色离开、右边黄色继续,点继续(不点离开,也不点看视频免扣分)
        val table = full("vd_stage_right_clock")
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(table, table, loss, fullBlank)))
        val result = run(game, """{"action":"start","advisor":"http://mac:3426","hands":3}""")
        assertEquals("1", result.text("handsLost"))
        assertEquals(listOf(DdzCards.stageContinue(loss)!!), game.taps)
        assertTrue(game.taps.single().first in 1292..1659)
    }

    @Test fun startOnAResultScreenPressesContinue() = runTest {
        for (name in listOf("vd_stage_loss", "vd_stage_win_leave_continue")) {
            val screen = full(name)
            val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(screen, fullBlank)))
            assertEquals("true", run(game, """{"action":"start","advisor":"http://mac:3426","hands":3}""").text("ok"))
            assertEquals(name, DdzCards.stageContinue(screen)!!, game.taps.first())
        }
    }

    @Test fun anAdAfterAWinIsNotTakenForAHalfPlayedHand() = runTest {
        val saved = PhoneDdzTurn.advise
        val asked = mutableListOf<String>()
        PhoneDdzTurn.advise = { _, body -> asked += body; """{"play":[]}""" }
        try {
            // 赢了点继续,先弹了广告,关掉后发的新一手(地主 20 张)DouZero 照样接
            val win = full("vd_stage_win_leave_continue")
            val ad = full("vd_stage_ad_dialog")
            val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(full("vd_stage_right_clock"), win) + List(5) { ad } + listOf(full("vd_stage_lead"), fullBlank)))
            val result = run(game, """{"action":"start","advisor":"http://mac:3426","hands":3}""")
            assertFalse(result.toString(), result.toString().contains("joined_mid_game"))
            assertTrue(asked.isNotEmpty())
        } finally {
            PhoneDdzTurn.advise = saved
        }
    }

    @Test fun startOnTheWinScreenDoesNotCountThatWinAgain() = runTest {
        // 点了继续以后胜利画面还留了一帧:不能当成又赢了一手
        val win = full("vd_stage_win_leave_continue")
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(win, win, fullBlank)))
        val result = run(game, """{"action":"start","advisor":"http://mac:3426","hands":1}""")
        assertNull(result.text("handsWon"))
    }

    @Test fun anAdOverTheStageTableIsNeverTapped() = runTest {
        val ad = full("vd_stage_ad_dialog")
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(ad, ad, ad, fullBlank)))
        run(game, """{"action":"start","advisor":"http://mac:3426","hands":3}""")
        assertTrue("${game.taps}", game.taps.isEmpty())
    }

    @Test fun bidsFollowTheHandsStrength() {
        assertEquals(3, PhoneDdzTurn.bidLevel("BJ SJ 2 2 A A Q Q Q Q J J 10 9 9 5 4".split(' ')))
        assertEquals(2, PhoneDdzTurn.bidLevel("BJ 2 2 K K Q J 10 9 8 7 6 5 5 4 3 3".split(' ')))
        assertEquals(0, PhoneDdzTurn.bidLevel("2 K K Q J 10 9 8 7 6 5 5 4 4 3 3 3".split(' ')))
    }

    @Test fun theStagePageIsTellApartFromTheLobbyAndTheTable() {
        assertTrue(DdzCards.singleStage(lobbyFrame("vd_stage")))
        assertFalse(DdzCards.singleStage(lobbyFrame("vd_lobby")))
        assertFalse(DdzCards.singleStage(lobbyFrame("vd_hand_follow")))
        assertFalse(DdzCards.singleLobby(lobbyFrame("vd_stage")))
        // 救济金弹窗盖着的时候哪个都不算,start 不会点到弹窗上
        assertFalse(DdzCards.singleLobby(lobbyFrame("vd_relief")))
        assertFalse(DdzCards.singleStage(lobbyFrame("vd_relief")))
        assertFalse(DdzCards.singleStageTable(lobbyFrame("vd_relief")))
    }

    @Test fun startRefusesAnythingButThatLobby() = runTest {
        for (name in listOf("hand_t1", "table_left55")) {
            val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(lobbyFrame(name))))
            assertEquals("not_single_player_lobby", run(game, """{"action":"start","advisor":"http://mac:3426"}""").text("error"))
            assertTrue(game.taps.isEmpty())
        }
    }

    @Test fun theMainScreenLobbyIsNotTrustedBecauseMikasCapsuleCoversTheLabel() {
        assertFalse(DdzCards.singleLobby(lobbyFrame("lobby")))
        assertTrue(DdzCards.singleLobby(lobbyFrame("vd_lobby")))
    }

    @Test fun backInTheLobbyDouZeroStopsUntilStartedAgain() = runTest {
        val saved = PhoneDdzTurn.advise
        PhoneDdzTurn.advise = { _, _ -> error("must not ask after leaving the game") }
        try {
            val follow = full("hand_t1")
            val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(fullBlank, lobbyFrame("vd_lobby"), follow)))
            val dispatcher = toolDispatcher(game)
            PhoneDdzTurn.markSingle(dispatcher.ctx, "http://mac:3426")
            PhoneDdzTurn.useTracker(dispatcher.ctx, leftLedPairs())
            val wait = Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject)
            assertEquals("other", PhoneDdzTurn.execute(wait, dispatcher.ctx).text("phase"))
            assertEquals("follow", PhoneDdzTurn.execute(wait, dispatcher.ctx).text("phase"))
            assertTrue(game.taps.isEmpty())
        } finally {
            PhoneDdzTurn.advise = saved
        }
    }

    @Test fun cardsStillFlyingWhenMyTurnStartsAreWaitedForNotGivenUpOn() = runTest {
        val saved = PhoneDdzTurn.advise
        PhoneDdzTurn.advise = { _, _ -> error("history never completed") }
        try {
            // 我是地主先出了 4,右家出完了但牌一直没落下(这一帧右边是空的)
            val inFlight = DdzTracker().apply {
                observe(DdzSeat.Me, { DdzSeen.Empty }, { listOf("J", "7", "4") }, leadingWith20 = true)
                mine(listOf("4"))
                observe(DdzSeat.Left, { DdzSeen.Empty }, { null })
                observe(DdzSeat.Me, { DdzSeen.Empty }, { null })
            }
            val follow = full("hand_t1")
            val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(List(20) { follow }))
            val dispatcher = toolDispatcher(game)
            PhoneDdzTurn.markSingle(dispatcher.ctx, "http://mac:3426")
            PhoneDdzTurn.useTracker(dispatcher.ctx, inFlight)
            val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
            assertEquals("等满了才放弃,原因是漏读而不是没准备好", "unread_right", result.text("douzeroOff"))
        } finally {
            PhoneDdzTurn.advise = saved
        }
    }

    @Test fun aHandThatDoesNotMatchWhatWasLeftStopsDouZero() = runTest {
        val saved = PhoneDdzTurn.advise
        PhoneDdzTurn.advise = { _, _ -> error("must not ask once the hand has drifted") }
        try {
            val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(full("hand_t1"))))
            val dispatcher = toolDispatcher(game)
            PhoneDdzTurn.markSingle(dispatcher.ctx, "http://mac:3426")
            // 上一手 DouZero 出了一张 7,手里该剩下没有 7 的那些;认到的还有 7,说明点到的不是 7
            val left = "SJ 2 2 2 A A A A Q J 10 9 9 8 3 3".split(' ')
            PhoneDdzTurn.useTracker(dispatcher.ctx, leftLedPairs(), expectedHand = left)
            val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
            assertEquals("hand_drift", result.text("douzeroOff"))
        } finally {
            PhoneDdzTurn.advise = saved
        }
    }

    @Test fun anyOtherScreenActionBetweenCallsEndsSinglePlayer() = runTest {
        val saved = PhoneDdzTurn.advise
        PhoneDdzTurn.advise = { _, _ -> error("must not ask after the model drove the screen itself") }
        try {
            val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(full("hand_t1"))))
            val dispatcher = toolDispatcher(game)
            PhoneDdzTurn.markSingle(dispatcher.ctx, "http://mac:3426")
            PhoneDdzTurn.useTracker(dispatcher.ctx, leftLedPairs())
            // 模型自己点了一下(比如回大厅进了真人场)
            dispatcher.ctx.evidence.logAction("第 3 步", "phone_tap", "真人对战", null, ToolExecution(buildJsonObject { put("ok", true) }), wide = false)
            val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
            assertEquals("follow", result.text("phase"))
            assertTrue(game.taps.isEmpty())
        } finally {
            PhoneDdzTurn.advise = saved
        }
    }

    @Test fun backAtTheStageTableAfterClosingAPopupDouZeroPlaysAgain() = runTest {
        val saved = PhoneDdzTurn.advise
        val asked = mutableListOf<String>()
        PhoneDdzTurn.advise = { _, body -> asked += body; """{"play":[]}""" }
        try {
            val call = full("vd_stage_call")
            val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(call)))
            val dispatcher = toolDispatcher(game)
            PhoneDdzTurn.markSingle(dispatcher.ctx, "http://mac:3426")
            // 赢了以后弹了广告,模型点掉了它
            dispatcher.ctx.evidence.logAction("第 3 步", "phone_tap", "关闭广告", null, ToolExecution(buildJsonObject { put("ok", true) }), wide = false)
            // 回到闯关牌桌叫分:模型叫完,新的一手 DouZero 接着打;叫分按钮还留着的几帧不再按
            game.frames.addAll(listOf(call, call, full("vd_stage_lead"), fullBlank))
            PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"call","level":3}""").jsonObject), dispatcher.ctx)
            assertEquals(1, game.taps.count { it.first > 1700 })
            assertTrue(asked.isNotEmpty())
        } finally {
            PhoneDdzTurn.advise = saved
        }
    }

    @Test fun pickMapsRanksToDistinctCards() {
        assertEquals(setOf(1, 2), PhoneDdzTurn.pick(listOf("A", "3", "3"), listOf("3", "3")))
        assertNull(PhoneDdzTurn.pick(listOf("A", "3"), listOf("3", "3")))
    }

    @Test fun humanStartReportsAnExplicitModeAndDoesNotTapTheTable() = runTest {
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(full("call"))))
        val result = run(game, """{"action":"start","mode":"human","advisor":"http://mac:3426"}""")
        assertEquals("human", result.text("mode"))
        assertEquals("calling", result.text("state"))
        assertEquals("call", result.text("phase"))
        assertTrue(game.taps.isEmpty())
    }

    @Test fun humanStartKeepsTheTrackerWhenRecoveringTheSameGame() = runTest {
        val asked = mutableListOf<String>()
        val saved = PhoneDdzTurn.advise
        PhoneDdzTurn.advise = { _, body -> asked += body; """{"play":[]}""" }
        try {
            val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(full("call"), full("call"), full("hand_t1"))))
            val dispatcher = toolDispatcher(game)
            val start = Args(PhoneDdzTurn.def, Json.parseToJsonElement(
                """{"action":"start","mode":"human","advisor":"http://mac:3426"}"""
            ).jsonObject)
            PhoneDdzTurn.execute(start, dispatcher.ctx)
            PhoneDdzTurn.useTracker(dispatcher.ctx, leftLedPairs())

            val recovered = PhoneDdzTurn.execute(start, dispatcher.ctx)
            assertEquals("calling", recovered.text("state"))

            PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
            assertTrue("the recovered session must keep its confirmed history", asked.isNotEmpty())
            val body = Json.parseToJsonElement(asked.single()).jsonObject
            assertTrue(body["history"]!!.jsonArray.isNotEmpty())
        } finally {
            PhoneDdzTurn.advise = saved
        }
    }

    @Test fun humanStartRequiresItsAdvisor() = runTest {
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(full("call"))))
        var thrown = false
        try {
            run(game, """{"action":"start","mode":"human"}""")
        } catch (_: ArgError) {
            thrown = true
        }
        assertTrue(thrown)
        assertTrue(game.taps.isEmpty())
    }

    @Test fun stopIsIdempotentAndBlocksLaterAutomaticWork() = runTest {
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(full("call"))))
        val dispatcher = toolDispatcher(game)
        val stop = Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"stop"}""").jsonObject)
        val first = PhoneDdzTurn.execute(stop, dispatcher.ctx)
        val second = PhoneDdzTurn.execute(stop, dispatcher.ctx)
        assertEquals("stopped", first.text("state"))
        assertEquals("user_stop", first.text("reason"))
        assertEquals("stopped", second.text("state"))
        assertEquals("user_stop", second.text("reason"))
        val wait = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
        assertEquals("stopped", wait.text("state"))
        assertEquals("stopped", wait.text("error"))
        assertTrue(game.taps.isEmpty())
    }

    @Test fun resumeAcceptsACompleteHumanSnapshotWithoutClicking() = runTest {
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(full("call"))))
        val dispatcher = toolDispatcher(game)
        PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        val resume = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""
            {"action":"resume","hand":["7","8","9","10","J","Q","K","A","2","SJ","BJ","7","8","9","10","J","Q"],"bottom":["3","4","5"],"history":[["6","6"],[]],"phase":"follow","seat":"landlord_up"}
        """.trimIndent()).jsonObject), dispatcher.ctx)
        assertEquals("human", resume.text("mode"))
        assertEquals("playing", resume.text("state"))
        assertEquals("follow", resume.text("phase"))
        assertTrue(game.taps.isEmpty())
    }

    @Test fun conservativePolicyIsDeterministicForCallingAndDoubling() {
        val strong = "BJ SJ 2 2 A A Q Q Q Q J J 10 9 9 5 4".split(' ')
        val weak = "2 K K Q J 10 9 8 7 6 5 5 4 4 3 3 3".split(' ')
        assertEquals(3, HumanDdzPolicy.callLevel(strong))
        assertEquals(0, HumanDdzPolicy.callLevel(weak))
        assertFalse(HumanDdzPolicy.shouldDouble(weak, listOf("3", "4", "5")))
        assertEquals(
            HumanDdzPolicy.shouldDouble(strong, listOf("3", "4", "5")),
            HumanDdzPolicy.shouldDouble(strong, listOf("3", "4", "5")),
        )
    }

    @Test fun inTheToolListFrozenAtTaskStartWhenTheModelCanSee() {
        fun names(vision: Boolean) = toolDispatcher(FakeScreen(), visionEnabled = vision).offeredNow(all = true).map { it.name }
        assertTrue("phone_ddz_turn" in names(vision = true))
        assertFalse("phone_ddz_turn" in names(vision = false))
    }

    @Test fun invalidRecoveryReturnsAStableFailureWithoutReplacingARunnableSession() = runTest {
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(full("call"))))
        val dispatcher = toolDispatcher(game)
        PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""
            {"action":"resume","hand":["3","3"],"bottom":["3","4","5"],"history":[],"phase":"call","seat":"landlord"}
        """.trimIndent()).jsonObject), dispatcher.ctx)
        assertEquals("invalid_snapshot", result.text("error"))
        assertEquals("hand_count", result.text("reason"))
        assertEquals("calling", result.text("state"))
        assertEquals("call", result.text("phase"))
        assertTrue(game.taps.isEmpty())
    }

    @Test fun validRecoveryReportsTheReplayedHistoryWithoutClicking() = runTest {
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(full("call"))))
        val dispatcher = toolDispatcher(game)
        PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""
            {"action":"resume",
             "hand":["8","9","10","J","Q","K","A","2","SJ","BJ","8","9","10","Q","K","A","2"],
             "bottom":["5","6","7"],
             "history":[["3"],[],[],["4"]],
             "phase":"follow","seat":"landlord_down"}
        """.trimIndent()).jsonObject), dispatcher.ctx)
        assertEquals("true", result.text("ok"))
        assertEquals("playing", result.text("state"))
        assertEquals("follow", result.text("phase"))
        assertEquals("4", result.text("historyCount"))
        assertTrue(game.taps.isEmpty())
    }

    @Test fun ambiguousRecoveryCardsAreReportedWithoutGuessing() = runTest {
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(full("call"))))
        val dispatcher = toolDispatcher(game)
        PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""
            {"action":"resume","hand":["3","?4"],"bottom":["6","7","8"],"history":[],"phase":"call","seat":"landlord"}
        """.trimIndent()).jsonObject), dispatcher.ctx)
        assertEquals("invalid_snapshot", result.text("error"))
        assertEquals("ambiguous_card", result.text("reason"))
        assertTrue(game.taps.isEmpty())
    }

    @Test fun illegalHistoryIsRejectedBeforeTheSessionIsChanged() = runTest {
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(full("call"))))
        val dispatcher = toolDispatcher(game)
        PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""
            {"action":"resume","hand":["3","4","5","6","7","8","9","10","J","Q","K","A","2","SJ","BJ","3","4"],
             "bottom":["5","6","7"],"history":[[],["3"]],"phase":"follow","seat":"landlord_up"}
        """.trimIndent()).jsonObject), dispatcher.ctx)
        assertEquals("invalid_snapshot", result.text("error"))
        assertEquals("history_mismatch", result.text("reason"))
        assertEquals("calling", result.text("state"))
        assertTrue(game.taps.isEmpty())
    }

    @Test fun invalidSeatIsReportedAsStructuredRecoveryFailure() = runTest {
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(full("call"))))
        val dispatcher = toolDispatcher(game)
        PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""
            {"action":"resume","hand":["3","4","5","6","7","8","9","10","J","Q","K","A","2","SJ","BJ","3","4"],
             "bottom":["5","6","7"],"history":[],"phase":"call","seat":"dealer"}
        """.trimIndent()).jsonObject), dispatcher.ctx)
        assertEquals("invalid_snapshot", result.text("error"))
        assertEquals("seat", result.text("reason"))
        assertTrue(game.taps.isEmpty())
    }

    @Test fun incompleteFollowHistoryIsRejectedWithoutGuessingTheCurrentTrick() = runTest {
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(full("call"))))
        val dispatcher = toolDispatcher(game)
        PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""
            {"action":"resume","hand":["3","4","5","6","7","8","9","10","J","Q","K","A","2","SJ","BJ","3","4"],
             "bottom":["5","6","7"],"history":[["3"],[],[]],"phase":"follow","seat":"landlord_up"}
        """.trimIndent()).jsonObject), dispatcher.ctx)
        assertEquals("invalid_snapshot", result.text("error"))
        assertEquals("history_phase", result.text("reason"))
        assertTrue(game.taps.isEmpty())
    }

    @Test fun malformedSnapshotsAreRejectedWithoutChangingTheSession() = runTest {
        val farmer = """"hand":["3","4","5","6","7","8","9","10","J","Q","K","A","2","SJ","BJ","3","4"]"""
        val cases = mapOf(
            "missing history" to ("""{"action":"resume",$farmer,"bottom":["5","6","7"],"phase":"call","seat":"landlord"}""" to "invalid_snapshot"),
            "missing seat" to ("""{"action":"resume",$farmer,"bottom":["5","6","7"],"history":[],"phase":"call"}""" to "invalid_snapshot"),
            "unknown phase" to ("""{"action":"resume",$farmer,"bottom":["5","6","7"],"history":[],"phase":"bid","seat":"landlord"}""" to "phase"),
            "two bottom cards" to ("""{"action":"resume",$farmer,"bottom":["5","6"],"history":[],"phase":"call","seat":"landlord"}""" to "bottom_count"),
            "five threes" to ("""{"action":"resume","hand":["3","3","3","3","3","4","5","6","7","8","9","10","J","Q","K","A","2","SJ","BJ","4"],
                "bottom":["5","6","7"],"history":[],"phase":"call","seat":"landlord"}""" to "duplicate_card"),
        )
        for ((name, case) in cases) {
            val (body, reason) = case
            val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(full("call"))))
            val dispatcher = toolDispatcher(game)
            PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
            val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement(body).jsonObject), dispatcher.ctx)
            assertEquals(name, "invalid_snapshot", result.text("error"))
            assertEquals(name, reason, result.text("reason"))
            assertEquals(name, "calling", result.text("state"))
            assertTrue(name, game.taps.isEmpty())
        }
    }

    @Test fun resumeWithTheRightHandButTheWrongTurnPausesBeforeItActs() = runTest {
        // Hand and bottom match lead.jpg exactly; the snapshot claims a follow (with a table play the
        // screen does not show) while the screen is a lead. The phase and visible-play checks both reject it.
        val lead = full("lead")
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(lead, lead)))
        val dispatcher = toolDispatcher(game)
        PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        val resumed = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""
            {"action":"resume","hand":["BJ","SJ","2","2","2","K","Q","10","9","4","4","4","3","3","3"],
             "bottom":["K","Q","8"],"history":[["5","6","7","8","9"],["6","7","8","9","10"],[]],"phase":"follow","seat":"landlord"}
        """.trimIndent()).jsonObject), dispatcher.ctx)
        assertEquals("true", resumed.text("ok"))
        val next = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
        assertEquals("paused", next.text("state"))
        assertEquals("snapshot_mismatch", next.text("reason"))
        assertTrue(game.taps.isEmpty())
    }

    @Test fun humanStartRefusesTheSinglePlayerStageTable() = runTest {
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(full("vd_stage_call"))))
        val result = run(game, """{"action":"start","mode":"human","advisor":"http://mac:3426"}""")
        assertEquals("not_human_game", result.text("error"))
        assertTrue(game.taps.isEmpty())
    }

    @Test fun humanAutoStartWaitsForTheTableAfterQuickStart() = runTest {
        // Live 2026-11-09 run 6: the model waited 25 s after 快速开始 before calling start
        // (start used to refuse the matching screen) and missed bidding.
        val matching = fullBlank
        val call = full("call")
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(List(5) { matching } + listOf(call, call, lobbyFrame("vd_lobby"))))
        val dispatcher = toolDispatcher(game)
        PhoneDdzTurn.adviseBid = { _, _ -> 0 }
        val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","auto":true,"advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        assertNull(result.toString(), result.text("error"))
        assertEquals("bid at the deal after waiting for the table", 1, game.taps.size)
    }

    @Test fun humanAutoStartGivesUpWhenNoTableAppears() = runTest {
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(fullBlank)))
        val dispatcher = toolDispatcher(game)
        val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","auto":true,"advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        assertEquals("not_human_game", result.text("error"))
        assertTrue(game.taps.isEmpty())
    }

    @Test fun humanAutoStartDoesNotLetSlowOcrStretchTheWaitForTheTable() = runTest {
        // 2026-11-11 16:13:认不出的静态页(商店金豆页)上 clearPopup 每一轮都整屏 OCR(手机上 ~0.8 秒),
        // 300 轮把承诺的 ~45 秒拖成 294 秒。画面没变就不再 OCR,等待也按真实时间算,慢轮拉不长它。
        var ocr = 0
        val game = object : Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(fullBlank))) {
            override suspend fun readVisibleText(): List<VisibleTextLine>? { ocr++; delay(800); return null }
            // 真机上每一轮截到的都是新数组(内容一样):指纹得按内容算,不能按数组身份
            override suspend fun rawFrame(rows: List<Int>): RawFrame = RawFrame(2412, 1080, rows.associateWith { IntArray(2412) })
        }
        val dispatcher = toolDispatcher(game)
        val started = testScheduler.currentTime
        val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","auto":true,"advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        val elapsed = testScheduler.currentTime - started
        assertEquals("not_human_game", result.text("error"))
        assertTrue("start took ${elapsed}ms, wanted ~45s", elapsed in 45_000..50_000)
        assertEquals("unchanged frame is OCR'd once, not once a poll: $ocr", 1, ocr)
        assertTrue(game.taps.isEmpty())
    }

    @Test fun humanAutoStartClaimsFreeBeansThenSitsDownAtTheNormalRoom() = runTest {
        // 金豆输光点普通场:弹窗点「领取」、恭喜获得页点「普通领取」,回到场次页再点普通场,进桌叫分
        val rooms = shot("human_classic_rooms")
        val call = full("call")
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(
            rooms, shot("human_relief_popup"), shot("human_relief_claimed"), rooms, call, call, lobbyFrame("vd_lobby"),
        )))
        val dispatcher = toolDispatcher(game)
        val savedBid = PhoneDdzTurn.adviseBid
        PhoneDdzTurn.adviseBid = { _, _ -> 0 }
        try {
            val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","auto":true,"advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
            assertNull(result.toString(), result.text("error"))
            assertEquals(872 to 400, game.taps[0]) // 普通场
            assertTrue("「领取」: ${game.taps[1]}", game.taps[1].first in 980..1380 && game.taps[1].second in 675..790)
            assertTrue("「普通领取」: ${game.taps[2]}", game.taps[2].first in 1520..1720 && game.taps[2].second in 840..900)
            assertEquals("back into 普通场 after the claim", 872 to 400, game.taps[3])
            assertTrue("绝不点带视频图标的「双倍领取」: ${game.taps}", game.taps.none { it.first in 1000..1440 && it.second in 815..925 })
        } finally {
            PhoneDdzTurn.adviseBid = savedBid
        }
    }

    @Test fun humanAutoStartLeavesTheResultScreenAndSitsDownAtTheNormalRoomAgain() = runTest {
        // 2026-11-11: 一局打完在结算页调用 start(auto) 认不出结算,回了 unknown_popup;模型自己点「继续」进了商店。
        // 现在工具自己点一次绿色「离开」(绝不点「换桌」「继续」),再从场次页点「普通场」进桌叫分。
        val result = shot("human_result_leave_change_continue")
        val rooms = shot("human_classic_rooms")
        val call = full("call")
        val leave = DdzCards.humanResultLeave(result)
        assertTrue("绿色「离开」按钮正中: $leave", leave!!.first in 1010..1300 && leave.second in 785..882)
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(
            result, result, rooms, call, call, call, lobbyFrame("vd_lobby"),
        )))
        val dispatcher = toolDispatcher(game)
        val savedBid = PhoneDdzTurn.adviseBid
        PhoneDdzTurn.adviseBid = { _, _ -> 0 }
        try {
            val started = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","auto":true,"advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
            assertNull(started.toString(), started.text("error"))
            // 结算页只点这一次「离开」,同一画面不再点;之后是场次页的「普通场」和进桌后的叫分
            assertEquals(1, game.taps.count { it == leave })
            assertEquals(listOf(leave, 872 to 400), game.taps.take(2))
            assertEquals(DdzScreen.read(call).buttons[0].let { it.x to it.y }, game.taps[2])
            // 结算页下半排的「换桌」「继续」一下都不点
            assertTrue("绝不点换桌/继续: ${game.taps}", game.taps.none { (x, y) -> y == leave.second && x > 1400 })
        } finally {
            PhoneDdzTurn.adviseBid = savedBid
        }
    }

    @Test fun humanStartReportsTheSizeCompatWindowInsteadOfAGuess() = runTest {
        // 安卓的兼容小窗:游戏画面只占屏幕一角,截到的帧不是 2412×1080;认牌的坐标全不对
        for (auto in listOf(false, true)) {
            val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(RawFrame(724, 448, emptyMap()))))
            val json = if (auto) """{"action":"start","mode":"human","auto":true,"advisor":"http://mac:3426"}"""
            else """{"action":"start","mode":"human","advisor":"http://mac:3426"}"""
            val result = run(game, json)
            assertEquals("letterboxed", result.text("error"))
            assertTrue(game.taps.isEmpty())
        }
    }

    @Test fun humanAutoStartKeepsWaitingWhileTheTableIsStillMatching() = runTest {
        // 真人场匹配的倒计时就有 30 秒:原来的约 20 秒上限会让牌桌还没出来就放弃
        val matching = fullBlank
        val call = full("call")
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(List(250) { matching } + listOf(call, call, lobbyFrame("vd_lobby"))))
        val dispatcher = toolDispatcher(game)
        PhoneDdzTurn.adviseBid = { _, _ -> 0 }
        val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","auto":true,"advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        assertNull(result.toString(), result.text("error"))
        assertEquals("bid at the deal after the long match", 1, game.taps.size)
    }

    @Test fun humanStartWalksFromTheMainLobbyIntoTheNormalRoom() = runTest {
        // 2026-11-11: the model guessed (275,384) from a screenshot, hit 单机模式 and played the
        // single-player classic room. From the main lobby the tool now taps 真人对战 by itself.
        val lobby = shot("human_main_lobby")
        val rooms = shot("human_classic_rooms")
        val matching = shot("human_matching")
        val call = full("call")
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(lobby, rooms, matching, call) + List(6) { call } + List(2) { lobbyFrame("vd_lobby") }))
        val dispatcher = toolDispatcher(game)
        PhoneDdzTurn.adviseBid = { _, _ -> 0 }
        val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","auto":true,"advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        assertEquals(result.toString(), listOf(1285 to 560, 872 to 400), game.taps.take(2))
    }

    @Test fun humanStartLeavesTheStorePageThenWalksIntoTheNormalRoom() = runTest {
        // 2026-11-11 16:13:金豆输光后冷启动,游戏开在商店的金豆页。那页上什么都花钱,只点左上角的返回箭头,
        // 回到主大厅再照常点「真人对战」、普通场。
        val shop = shot("human_shop_beans")
        val lobby = shot("human_main_lobby")
        val rooms = shot("human_classic_rooms")
        val matching = shot("human_matching")
        val call = full("call")
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(shop, lobby, rooms, matching, call) + List(6) { call } + List(2) { lobbyFrame("vd_lobby") }))
        val dispatcher = toolDispatcher(game)
        PhoneDdzTurn.adviseBid = { _, _ -> 0 }
        val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","auto":true,"advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        assertEquals(result.toString(), listOf(127 to 82, 1285 to 560, 872 to 400), game.taps.take(3))
    }

    @Test fun humanStartTapsOnlyTheStorePagesBackArrow() = runTest {
        // 商店页别的地方一下都不点:连点几次返回还停在这页,就交还模型,不再点。
        // 也不走 OCR:实测 16:13 认不出这页时每一轮都 OCR 整屏,start 拖了 294 秒
        val shop = shot("human_shop_beans")
        var ocr = 0
        val game = object : Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(shop))) {
            override suspend fun readVisibleText(): List<VisibleTextLine>? { ocr++; return null }
        }
        run(game, """{"action":"start","mode":"human","auto":true,"advisor":"http://mac:3426"}""")
        assertTrue(game.taps.toString(), game.taps.isNotEmpty())
        assertTrue(game.taps.toString(), game.taps.all { it == 127 to 82 })
        assertTrue(game.taps.toString(), game.taps.size <= 3)
        assertEquals("商店页不该 OCR", 0, ocr)
    }

    @Test fun humanStartWalksFromTheRoomsPageIntoTheNormalRoom() = runTest {
        val rooms = shot("human_classic_rooms")
        val call = full("call")
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(rooms, call) + List(6) { call } + List(2) { lobbyFrame("vd_lobby") }))
        val dispatcher = toolDispatcher(game)
        PhoneDdzTurn.adviseBid = { _, _ -> 0 }
        PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","auto":true,"advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        assertEquals(listOf(872 to 400), game.taps.take(1))
    }

    @Test fun humanStartWalksIntoTheEliteRoomWhenAsked() = runTest {
        // 2026-11-11:豆豆超过普通场 200 万的上限,点普通场只弹「您的豆豆足够到高级的房间游戏啦」;
        // 模型自己点精英场再 start,晚了 26 秒,接手时已发完牌叫完分,DouZero 没上。room=elite 由工具自己点精英场
        val rooms = shot("human_classic_rooms")
        val call = full("call")
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(rooms, call) + List(6) { call } + List(2) { lobbyFrame("vd_lobby") }))
        val dispatcher = toolDispatcher(game)
        PhoneDdzTurn.adviseBid = { _, _ -> 0 }
        PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","auto":true,"room":"elite","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        assertEquals(listOf(1360 to 400), game.taps.take(1))
    }

    @Test fun humanStartRefusesAnUnknownRoomWithoutTapping() = runTest {
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(shot("human_classic_rooms"))))
        val dispatcher = toolDispatcher(game)
        var thrown = false
        try {
            PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","auto":true,"room":"rich","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        } catch (_: ArgError) {
            thrown = true
        }
        assertTrue(thrown)
        assertTrue(game.taps.isEmpty())
    }

    @Test fun humanStartRefusesTheSinglePlayerClassicRoomsPage() = runTest {
        for (name in listOf("single_classic_rooms", "single_classic_rooms_newbie")) {
            val screen = shot(name)
            for (auto in listOf(false, true)) {
                val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(screen)))
                val json = if (auto) """{"action":"start","mode":"human","auto":true,"advisor":"http://mac:3426"}"""
                else """{"action":"start","mode":"human","advisor":"http://mac:3426"}"""
                assertEquals(name, "single_player_rooms", run(game, json).text("error"))
                assertTrue(name, game.taps.isEmpty())
            }
        }
    }

    @Test fun humanAutoStartClosesTheBeansTooManyPopupAndHandsBackTheRoom() = runTest {
        // 普通场只收 200 万:点了弹「您的豆豆足够到高级的房间游戏啦」。工具自己点叉关掉(绝不点「立即前往」),
        // 报 beans_too_many;换不换精英场由用户决定
        val rooms = shot("human_classic_rooms")
        val tooMany = shot("human_room_beans_too_many")
        val close = DdzCards.humanBeansTooMany(tooMany)!!
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(rooms, tooMany, tooMany, tooMany)))
        val result = run(game, """{"action":"start","mode":"human","auto":true,"advisor":"http://mac:3426"}""")
        assertEquals("beans_too_many", result.text("error"))
        assertEquals(listOf(872 to 400, close), game.taps)
        assertTrue("绝不点「立即前往」: ${game.taps}", game.taps.none { (x, y) -> x in 1000..1420 && y in 690..800 })
    }

    @Test fun humanStartTapsOnlyTheFreeClaimsOnTheReliefScreens() = runTest {
        // 领金豆弹窗只点「领取」,恭喜获得页只点「普通领取」,带视频图标的「双倍领取」一下都不点
        val popup = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(shot("human_relief_popup"))))
        run(popup, """{"action":"start","mode":"human","auto":true,"advisor":"http://mac:3426"}""")
        assertTrue(popup.taps.toString(), popup.taps.isNotEmpty() && popup.taps.all { it.first in 980..1380 && it.second in 675..790 })
        val claimed = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(shot("human_relief_claimed"))))
        run(claimed, """{"action":"start","mode":"human","auto":true,"advisor":"http://mac:3426"}""")
        assertTrue(claimed.taps.toString(), claimed.taps.isNotEmpty() && claimed.taps.all { it.first in 1520..1720 && it.second in 840..900 })
        assertTrue((popup.taps + claimed.taps).none { it.first in 1000..1440 && it.second in 815..925 })
    }

    @Test fun theHumanLobbyAndRoomsAreTellApartFromTheOtherScreens() {
        assertTrue(DdzCards.humanMainLobby(shot("human_main_lobby")))
        // 单机经典模式的主大厅是同一张:真人局照样从中间的「真人对战」进
        assertTrue(DdzCards.humanMainLobby(lobbyFrame("vd_lobby")))
        assertTrue(DdzCards.humanClassicRooms(shot("human_classic_rooms")))
        assertTrue(DdzCards.singleClassicRooms(shot("single_classic_rooms")))
        assertFalse(DdzCards.humanClassicRooms(shot("single_classic_rooms")))
        assertFalse(DdzCards.singleClassicRooms(shot("human_classic_rooms")))
        assertFalse(DdzCards.humanMainLobby(shot("single_classic_rooms")))
        assertFalse(DdzCards.humanMainLobby(shot("human_classic_rooms")))
        // 主屏左边有黑边、上面压着 Mika 的悬泡:坐标对不上,不认
        assertFalse(DdzCards.humanMainLobby(lobbyFrame("lobby")))
        for (name in listOf("vd_stage", "vd_stage_call", "vd_hand_follow", "call", "lead", "result",
                "vd_result_bust", "human_result_leave_change_continue", "vd_relief")) {
            val frame = lobbyFrame(name)
            assertFalse("main lobby $name", DdzCards.humanMainLobby(frame))
            assertFalse("human rooms $name", DdzCards.humanClassicRooms(frame))
            assertFalse("single rooms $name", DdzCards.singleClassicRooms(frame))
        }
        val table = shot("human_right_landlord_joker")
        assertFalse(DdzCards.humanMainLobby(table))
        assertFalse(DdzCards.humanClassicRooms(table))
        assertFalse(DdzCards.singleClassicRooms(table))
        // 牌桌(别家在想)不能认成大厅或场次页
        val waiting = shot("human_matching")
        assertTrue(DdzCards.classicHumanTable(waiting))
        assertFalse(DdzCards.humanMainLobby(waiting))
        assertFalse(DdzCards.humanClassicRooms(waiting))
        assertFalse(DdzCards.singleClassicRooms(waiting))
    }

    /** A hand whose history is lost (missed_play) at hand_t1: Left shows pairs on top, we follow. */
    private suspend fun lostHistoryFollow(landlord: DdzSeat, asked: MutableList<String>): Game {
        PhoneDdzTurn.advise = { _, body -> asked += body; """{"play":[]}""" }
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(List(6) { full("hand_t1") }))
        val dispatcher = toolDispatcher(game)
        PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        PhoneDdzTurn.useTracker(dispatcher.ctx, DdzTracker(DdzSettlingPolicy.HUMAN).apply {
            restore(landlord, listOf("J", "7", "4"), emptyList())
            fail(DdzSessionReason.MISSED_PLAY)
        })
        PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
        return game
    }

    @Test fun lostHistoryPassesOnOurTeammatesPlay() = runTest {
        // Live 2026-11-10 run 16: handing a lost hand to the model was slower than the 15 s clock and
        // the game auto-played a bomb for us. The tool finishes the hand itself with simple rules.
        val asked = mutableListOf<String>()
        val saved = PhoneDdzTurn.advise
        try {
            val game = lostHistoryFollow(landlord = DdzSeat.Right, asked = asked) // Left is our teammate
            val pass = DdzScreen.read(full("hand_t1")).buttons[0].let { it.x to it.y }
            assertEquals("不出 on the teammate's pairs", pass, game.taps.first())
            assertTrue("DouZero is not asked with a lost history", asked.isEmpty())
        } finally {
            PhoneDdzTurn.advise = saved
        }
    }

    @Test fun lostHistoryUsesTheGamesHintAgainstAnOpponent() = runTest {
        val asked = mutableListOf<String>()
        val saved = PhoneDdzTurn.advise
        try {
            val game = lostHistoryFollow(landlord = DdzSeat.Left, asked = asked) // Left (the landlord) is on top
            val hint = DdzScreen.read(full("hand_t1")).buttons[1].let { it.x to it.y }
            assertEquals("提示 against the landlord's pairs", hint, game.taps.first())
            assertTrue(asked.isEmpty())
        } finally {
            PhoneDdzTurn.advise = saved
        }
    }

    @Test fun aLateModelMoveAfterAMissedPlayDoesNotPauseTheHand() = runTest {
        // Live 2026-11-10 run 12: the model's pass arrived after the game had passed for us;
        // the error paused the session and the model could no longer play the hand.
        val follow = full("hand_t1")
        val othersTurn = full("hand_k2")
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(follow, othersTurn, othersTurn, follow, follow, follow)))
        val dispatcher = toolDispatcher(game)
        PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        PhoneDdzTurn.useTracker(dispatcher.ctx, DdzTracker(DdzSettlingPolicy.HUMAN).apply { fail(DdzSessionReason.MISSED_PLAY) })
        val late = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"pass"}""").jsonObject), dispatcher.ctx)
        assertEquals(late.toString(), "wrong_phase", late.text("error"))
        assertTrue("a late move does not pause a hand whose history is lost", late.text("state") != "paused")
    }

    @Test fun eachHumanDecisionGetsItsOwnSettlingWindow() = runTest {
        // Live 2026-11-10 run 10: the settling counter used up in one game carried into the next,
        // so the next game's first not-yet-ready decision failed history_not_ready at once.
        val follow = full("hand_t1")
        val game = object : Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(follow))) {
            var reads = 0
            override suspend fun rawFrame(rows: List<Int>): RawFrame? { reads++; return super.rawFrame(rows) }
        }
        val dispatcher = toolDispatcher(game)
        val start = Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject)
        val wait = Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject)
        // Right still has to play before us and its play never shows: the tracker is not ready.
        fun waitingOnRight() = DdzTracker(DdzSettlingPolicy.HUMAN).apply {
            restore(DdzSeat.Left, listOf("J", "7", "4"), listOf(listOf("10", "10", "9", "9", "8", "8")), current = DdzSeat.Right)
        }
        // When the window runs out the hand goes to the model (missed_play) instead of pausing
        // and running out the game clock (live 2026-11-10 run 13).
        PhoneDdzTurn.execute(start, dispatcher.ctx)
        PhoneDdzTurn.useTracker(dispatcher.ctx, waitingOnRight())
        val first = PhoneDdzTurn.execute(wait, dispatcher.ctx)
        assertTrue(first.toString(), first.text("douzeroOff") != null)
        // Next game: a tracker that is not ready yet must get the full settling window again before the
        // lost-history fallback taps anything.
        PhoneDdzTurn.execute(start, dispatcher.ctx)
        PhoneDdzTurn.useTracker(dispatcher.ctx, waitingOnRight())
        val before = game.reads
        val tapsBefore = game.taps.size
        PhoneDdzTurn.execute(wait, dispatcher.ctx)
        assertTrue("waited ${game.reads - before} frames before giving up", game.reads - before > 40)
        assertTrue("the fallback acted after the window", game.taps.size > tapsBefore)
    }

    @Test fun humanLandlordDeclinesDoublingWhenTheTwentyCardHandIsUnreadable() = runTest {
        // Live 2026-11-09 run 7: as landlord the 20-card hand is unreadable at doubling (vd_20);
        // the tool paused unknown_scene instead of taking the safe 不加倍.
        val double = full("vd_20")
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(List(4) { double }))
        val dispatcher = toolDispatcher(game)
        val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","auto":true,"advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        assertNull(result.toString(), result.text("reason"))
        assertEquals("不加倍 is the first (blue) button", listOf(DdzScreen.read(double).buttons[0].let { it.x to it.y }), game.taps)
    }

    @Test fun humanLoopWaitsWhenNoClockIsReadable() = runTest {
        // Live 2026-11-09 run 6: everyone decides doubling at once with no clock on any seat; the
        // tool paused unknown_scene 1.8 s in. vd_17 also has a clock the reader misses.
        val waiting = full("vd_17")
        assertTrue("our hand is on the human table while others decide", DdzCards.classicHumanTable(waiting))
        val call = full("call")
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(call, call) + List(20) { waiting } + listOf(lobbyFrame("vd_lobby"))))
        val dispatcher = toolDispatcher(game)
        PhoneDdzTurn.adviseBid = { _, _ -> 0 }
        val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","auto":true,"advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        assertEquals(result.toString(), "lobby_return", result.text("reason"))
    }

    @Test fun theHumanResultScreenEndsTheHandInsteadOfPausing() = runTest {
        val call = full("call")
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(call, call) + List(3) { full("human_result_leave_change_continue") }))
        val dispatcher = toolDispatcher(game)
        PhoneDdzTurn.adviseBid = { _, _ -> 0 }
        val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","auto":true,"advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        assertEquals(result.toString(), "result", result.text("phase"))
        assertNull(result.text("reason"))
    }

    @Test fun humanLoopWaitsThroughOtherPlayersTurns() = runTest {
        // Live 2026-11-09 run 3: after our bid the tool paused unknown_scene 1.8 s into the next
        // player's turn (no buttons for us), and the game's autoplay took the hand.
        val waiting = full("hand_k2")
        assertTrue("a human table where another player has the clock", DdzCards.classicHumanTable(waiting))
        val call = full("call")
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(call, call) + List(20) { waiting } + listOf(lobbyFrame("vd_lobby"))))
        val dispatcher = toolDispatcher(game)
        PhoneDdzTurn.adviseBid = { _, _ -> 0 }
        val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","auto":true,"advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        assertEquals(result.toString(), "lobby_return", result.text("reason"))
        assertEquals("only our 不叫 was tapped", 1, game.taps.size)
    }

    @Test fun humanWaitAutomaticallyCallsAndDeclinesDoubling() = runTest {
        val call = full("call")
        val double = full("double")
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(call, call, call, double, double, full("lead"))))
        val dispatcher = toolDispatcher(game)
        PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
        assertEquals("lead", result.text("phase"))
        // Static lead frames never settle the history; the lost-history fallback may tap after bid + double.
        assertTrue(game.taps.toString(), game.taps.size >= 2)
        assertEquals(DdzScreen.read(call).buttons[3].let { it.x to it.y }, game.taps[0])
        assertEquals(DdzScreen.read(double).buttons[0].let { it.x to it.y }, game.taps[1])
    }

    @Test fun humanWaitAtCallingWaitsThroughOtherPlayersBids() = runTest {
        val call = full("call")
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(call, call, call) + List(20) { full("hand_k2") } + listOf(lobbyFrame("vd_lobby"))))
        val dispatcher = toolDispatcher(game)
        PhoneDdzTurn.adviseBid = { _, _ -> 0 }
        PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
        assertEquals(result.toString(), "lobby_return", result.text("reason"))
        assertEquals("only our 不叫 was tapped", 1, game.taps.size)
    }

    @Test fun humanAutoStartReportsAPauseFromItsLoop() = runTest {
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(full("call"), lobbyFrame("vd_lobby"))))
        val dispatcher = toolDispatcher(game)
        val savedBid = PhoneDdzTurn.adviseBid
        PhoneDdzTurn.adviseBid = { _, _ -> 0 }
        try {
            val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","auto":true,"advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
            assertEquals(result.toString(), "paused", result.text("state"))
            assertEquals("lobby_return", result.text("reason"))
        } finally {
            PhoneDdzTurn.adviseBid = savedBid
        }
    }

    @Test fun humanAutoStartTakesBackAGameAlreadyOnAutoplayAndPlaysItByRules() = runTest {
        // Live 2026-11-11: the game keeps 托管 on into the next deal, so sitting the joined hand out loops forever.
        val asked = mutableListOf<String>()
        val saved = PhoneDdzTurn.advise
        PhoneDdzTurn.advise = { _, body -> asked += body; """{"play":[]}""" }
        try {
            val autoplay = full("human_autoplay_left11_right5")
            val follow = full("human_follow_left8_right2")
            val game = playsThenEnds(listOf(autoplay, autoplay, autoplay) + List(8) { follow })
            val dispatcher = toolDispatcher(game)
            val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","auto":true,"advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
            assertNotEquals(result.content.toString() + game.taps, "paused", result.text("state"))
            assertEquals("joined_mid_game", result.text("douzeroOff"))
            assertEquals("取消托管 is tapped first", 760, game.taps.first().second)
            assertTrue("the rules play the follow after taking back control", game.taps.size > 1)
            assertTrue("DouZero is never asked with a partial history", asked.isEmpty())
        } finally {
            PhoneDdzTurn.advise = saved
        }
    }

    @Test fun humanWaitTakesBackAutoplayInAJoinedGame() = runTest {
        val autoplay = full("human_autoplay_left11_right5")
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(List(6) { autoplay }))
        val dispatcher = toolDispatcher(game)
        PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
        assertNotEquals("joined_mid_game", result.text("reason"))
        assertEquals("取消托管 is tapped", 760, game.taps.first().second)
    }

    @Test fun humanAutoStartOnTheResultScreenIsNotAMidGameJoin() = runTest {
        val result = full("result")
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(List(4) { result }))
        val dispatcher = toolDispatcher(game)
        val start = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","auto":true,"advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        assertEquals(start.toString(), "result", start.text("phase"))
        assertNull(start.text("reason"))
    }

    @Test fun humanStartAfterAFinishedGameDoesNotReuseItsHistory() = runTest {
        val asked = mutableListOf<String>()
        val saved = PhoneDdzTurn.advise
        PhoneDdzTurn.advise = { _, body -> asked += body; """{"play":[]}""" }
        try {
            val follow = full("hand_t1")
            val game = playsThenEnds(listOf(full("result")) + List(6) { follow })
            val dispatcher = toolDispatcher(game)
            val start = """{"action":"start","mode":"human","advisor":"http://mac:3426"}"""
            PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement(start).jsonObject), dispatcher.ctx)
            PhoneDdzTurn.useTracker(dispatcher.ctx, leftLedPairs())
            // The model tapped 再来一局 itself; the next game is already at a play turn.
            val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement(
                """{"action":"start","mode":"human","auto":true,"advisor":"http://mac:3426"}"""
            ).jsonObject), dispatcher.ctx)
            assertEquals("joined_mid_game", result.text("douzeroOff"))
            assertNotEquals(result.content.toString() + game.taps, "paused", result.text("state"))
            assertTrue("the finished game's history must not reach DouZero", asked.isEmpty())
        } finally {
            PhoneDdzTurn.advise = saved
        }
    }

    @Test fun humanWaitPlaysAGameJoinedAtAPlayTurnByRules() = runTest {
        val asked = mutableListOf<String>()
        val saved = PhoneDdzTurn.advise
        PhoneDdzTurn.advise = { _, body -> asked += body; """{"play":[]}""" }
        try {
            val follow = full("human_follow_left8_right2")
            val game = playsThenEnds(List(10) { follow })
            val dispatcher = toolDispatcher(game)
            PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
            val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
            assertNotEquals(result.content.toString() + game.taps, "paused", result.text("state"))
            assertEquals("joined_mid_game", result.text("douzeroOff"))
            assertTrue(asked.isEmpty())
            assertTrue("the rules answer the follow", game.taps.isNotEmpty())
        } finally {
            PhoneDdzTurn.advise = saved
        }
    }

    /** One screen: what the frame, the accessibility tree and OCR show. A tap moves to the next scene; [reads] > 0 moves on by itself. */
    private class Scene(val frames: List<RawFrame>, val els: List<UiElement> = emptyList(), val ocr: List<VisibleTextLine>? = null, val reads: Int = 0, val frameReads: Int = 0)

    private class Scenes(val scenes: List<Scene>) : Game(PhoneDdzTurn.PACKAGE, ArrayDeque()) {
        var at = 0
        var seen = 0
        val scene get() = scenes[at]
        override suspend fun peek(): ScreenSnapshot {
            if (scene.reads > 0 && ++seen >= scene.reads && at < scenes.lastIndex) { at++; seen = 0 }
            return ScreenSnapshot("p", app, scene.els, 2412, 1080)
        }
        override suspend fun readVisibleText() = scene.ocr
        private val queue = ArrayDeque<RawFrame>()
        private var queued = -1
        private var framesSeen = 0
        override suspend fun rawFrame(rows: List<Int>): RawFrame? {
            if (scene.frameReads > 0 && ++framesSeen > scene.frameReads && at < scenes.lastIndex) { at++; seen = 0; framesSeen = 1 }
            if (queued != at) { queue.clear(); queue.addAll(scene.frames); queued = at }
            last = queue.removeFirstOrNull() ?: last
            return last
        }
        override suspend fun quickTap(x: Int, y: Int): Boolean {
            taps += x to y
            if (scenes[at].frames.none { DdzCards.classicHumanTable(it) } && at < scenes.lastIndex) { at++; seen = 0 }
            return true
        }
    }

    private fun el(name: String, role: String, l: Int, t: Int, r: Int, b: Int) =
        UiElement("@e", role, name, toPx((l + r) / 2, 2412), toPx((t + b) / 2, 1080), listOf(l, t, r, b), null, false, false, false)

    private val videoBar = listOf(
        el("看27s/打开应用/查看详情可立即获得奖励", "TextView", 35, 56, 356, 111),
        el("", "TextView", 884, 56, 909, 111),
        el("", "ImageView", 930, 56, 955, 111),
        el("立刻打开", "TextView", 614, 824, 709, 903),
    )

    @Test fun humanAutoStartClaimsReliefThroughTheRewardVideoAndSitsDown() = runTest {
        // Live 2026-11-11: out of coins, entering a room pops the relief; only the video claim is left. The model took 4 minutes.
        val game = Scenes(listOf(
            Scene(listOf(full("popup_relief_video_only")), ocr = listOf(VisibleTextLine("|系统送钱啦!", listOf(380, 300, 500, 330)))),
            Scene(listOf(fullBlank), listOf(el("获得加速机会！", "TextView", 421, 163, 578, 221), el("我要直接拿奖励", "TextView", 429, 665, 571, 727)) + videoBar),
            Scene(listOf(fullBlank), listOf(el("前往“淘宝”浏览15秒可立即领取奖励。确认放弃直接拿奖励吗？", "TextView", 326, 483, 674, 711),
                el("放弃", "TextView", 296, 775, 499, 842), el("直接拿奖励", "TextView", 501, 775, 704, 842))),
            Scene(listOf(fullBlank), videoBar, reads = 5),
            Scene(listOf(fullBlank), listOf(el("", "ImageView", 960, 50, 985, 100), el("立刻打开", "TextView", 614, 824, 709, 903))),
            Scene(listOf(fullBlank), ocr = listOf(VisibleTextLine("点击任意空白区域多展", listOf(400, 800, 600, 830)))),
            Scene(listOf(shot("human_classic_rooms"))),
            Scene(listOf(full("call"), full("call"), full("call"), full("double"), full("double"), full("lead"))),
        ))
        val savedBid = PhoneDdzTurn.adviseBid
        PhoneDdzTurn.adviseBid = { _, _ -> 3 }
        try {
            val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","auto":true,"advisor":"http://mac:3426"}""").jsonObject), toolDispatcher(game).ctx)
            assertEquals(result.content.toString(), "lead", result.text("phase"))
            assertEquals(listOf(
                toPx(508, 2412) to toPx(787, 1080), // 双倍领取
                toPx(942, 2412) to toPx(83, 1080), // the boost offer's ✕, not 我要直接拿奖励
                toPx(397, 2412) to toPx(808, 1080), // 放弃
                toPx(972, 2412) to toPx(75, 1080), // the end card's ✕, not 立刻打开
                toPx(400, 2412) to toPx(300, 1080), // a blank spot on the reward screen
                872 to 400, // back into 普通场 on the human room list
            ), game.taps.take(6))
        } finally {
            PhoneDdzTurn.adviseBid = savedBid
        }
    }

    @Test fun humanWaitClosesAnAdEndCardAtTheTableAndPlaysOn() = runTest {
        // Live 2026-11-11 (run 23): a Taobao end card covered the table and the tool paused with unknown_popup
        val others = full("human_right_landlord_joker")
        val game = Scenes(listOf(
            Scene(listOf(others), frameReads = 3),
            Scene(listOf(fullBlank), listOf(el("", "ImageView", 960, 50, 985, 100), el("立刻打开", "TextView", 614, 824, 709, 903))),
            Scene(List(6) { others } + full("human_follow_left8_right2")),
        ))
        val dispatcher = toolDispatcher(game)
        PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
        assertNotEquals(result.content.toString(), "unknown_popup", result.text("reason"))
        assertTrue(game.taps.toString(), (toPx(972, 2412) to toPx(75, 1080)) in game.taps)
    }

    @Test fun humanAutoStartKeepsTheWholeDecisionLoopInsideTheToolAndUsesDouZeroBid() = runTest {
        val call = full("call")
        val double = full("double")
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(call, call, call, double, double, full("lead"))))
        val dispatcher = toolDispatcher(game)
        val savedBid = PhoneDdzTurn.adviseBid
        var bidCalls = 0
        PhoneDdzTurn.adviseBid = { _, _ -> bidCalls++; 3 }
        try {
            val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","auto":true,"advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
            assertEquals("lead", result.text("phase"))
            assertEquals(1, bidCalls)
            // Bid then double; the static lead frames afterwards never settle the history, so the lost-history
            // fallback may tap on after these two.
            assertTrue(game.taps.toString(), game.taps.size >= 2)
        } finally {
            PhoneDdzTurn.adviseBid = savedBid
        }
    }

    @Test fun humanWaitUsesTheAdvisorToPassAtFollow() = runTest {
        val follow = full("hand_t1")
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(follow, follow, follow, fullBlank)))
        val dispatcher = toolDispatcher(game)
        val tracker = leftLedPairs()
        val saved = PhoneDdzTurn.advise
        PhoneDdzTurn.advise = { _, _ -> """{"play":[]}""" }
        try {
            PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
            PhoneDdzTurn.useTracker(dispatcher.ctx, tracker)
            val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
            assertEquals("paused", result.text("state"))
            assertEquals("unknown_scene", result.text("reason"))
            assertEquals(1, game.taps.size)
            assertEquals(DdzScreen.read(follow).buttons[0].let { it.x to it.y }, game.taps.single())
        } finally {
            PhoneDdzTurn.advise = saved
        }
    }

    @Test fun humanUnknownScenePausesBeforeAnyAutomaticTap() = runTest {
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(full("call"), fullBlank)))
        val dispatcher = toolDispatcher(game)
        PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
        assertEquals("paused", result.text("state"))
        assertEquals("unknown_scene", result.text("reason"))
        assertTrue(game.taps.isEmpty())
    }

    @Test fun humanWaitRecoversFromOneTransientUnknownFrame() = runTest {
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(full("call"), fullBlank, full("call"))))
        val dispatcher = toolDispatcher(game)
        PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)

        val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)

        assertNull(result.toString(), result.text("reason"))
        assertEquals("calling", result.text("state"))
        assertTrue(game.taps.isNotEmpty())
    }

    @Test fun humanAppSwitchPausesWithAStableReason() = runTest {
        val game = object : Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(full("call")))) {
            var peeks = 0
            override suspend fun peek() = ScreenSnapshot("p1", if (peeks++ == 0) PhoneDdzTurn.PACKAGE else "com.other", emptyList(), 2412, 1080)
        }
        val dispatcher = toolDispatcher(game)
        PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
        assertEquals("paused", result.text("state"))
        assertEquals("app_switch", result.text("reason"))
        assertTrue(game.taps.isEmpty())
    }

    @Test fun stopDuringHumanPollingPreventsAQueuedTap() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val game = object : Game(PhoneDdzTurn.PACKAGE, ArrayDeque(List(3) { full("call") })) {
            override suspend fun quickTap(x: Int, y: Int): Boolean {
                taps += x to y
                entered.complete(Unit)
                release.await()
                return true
            }
        }
        val dispatcher = toolDispatcher(game)
        PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        val waiting = backgroundScope.async { PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx) }
        entered.await()
        val stopped = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"stop"}""").jsonObject), dispatcher.ctx)
        release.complete(Unit)
        val finished = waiting.await()
        assertEquals("stopped", stopped.text("state"))
        assertEquals("stopped", finished.text("state"))
        assertEquals(1, game.taps.size)
    }

    @Test fun humanLeadUsesTheAdvisorAtThePhoneToolSeam() = runTest {
        val asked = mutableListOf<String>()
        val saved = PhoneDdzTurn.advise
        PhoneDdzTurn.advise = { _, body -> asked += body; """{"play":["3"]}""" }
        try {
            val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(List(12) { full("lead") }))
            val dispatcher = toolDispatcher(game)
            PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
            PhoneDdzTurn.useTracker(dispatcher.ctx, DdzTracker(DdzSettlingPolicy.HUMAN).apply {
                observe(DdzSeat.Me, { DdzSeen.Empty }, { listOf("3", "4", "5") }, leadingWith20 = true, leading = true)
            })
            val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
            assertTrue("advisor was called", asked.isNotEmpty())
            assertTrue("a lead card was submitted", game.taps.isNotEmpty())
            assertEquals("lead", result.text("phase"))
        } finally {
            PhoneDdzTurn.advise = saved
        }
    }

    @Test fun humanSelectionWaitsOutATransitionBeforeItsFirstTap() = runTest {
        // Live 2026-11-10 run 12: right after we became landlord the first frame read by the card
        // selection was a transition; selection gave up at once (rejected_action) and autoplay took over.
        val saved = PhoneDdzTurn.advise
        PhoneDdzTurn.advise = { _, _ -> """{"play":["3"]}""" }
        try {
            val lead = full("lead")
            val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(lead, lead, lead, full("hand_k2")) + List(8) { lead }))
            val dispatcher = toolDispatcher(game)
            PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
            PhoneDdzTurn.useTracker(dispatcher.ctx, DdzTracker(DdzSettlingPolicy.HUMAN).apply {
                observe(DdzSeat.Me, { DdzSeen.Empty }, { listOf("3", "4", "5") }, leadingWith20 = true, leading = true)
            })
            val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
            // Static frames never show the card lifted, so how the selection ends is not under test here.
            assertTrue("a card was tapped after the transition: ${game.taps} $result", game.taps.isNotEmpty())
        } finally {
            PhoneDdzTurn.advise = saved
        }
    }

    @Test fun humanSelectionContinuesPastOneUnrecognisedFrame() = runTest {
        // Live 2026-11-09: one unrecognised frame mid-play paused the session (unknown_scene)
        // and the game's autoplay took the hand.
        val saved = PhoneDdzTurn.advise
        PhoneDdzTurn.advise = { _, _ -> """{"play":["3"]}""" }
        try {
            val lead = full("lead")
            val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(lead, lead, lead, lead, fullBlank) + List(8) { lead }))
            val dispatcher = toolDispatcher(game)
            PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
            PhoneDdzTurn.useTracker(dispatcher.ctx, DdzTracker(DdzSettlingPolicy.HUMAN).apply {
                observe(DdzSeat.Me, { DdzSeen.Empty }, { listOf("3", "4", "5") }, leadingWith20 = true, leading = true)
            })
            val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
            // The blank frame comes right after the first card tap; selection must keep going.
            assertTrue("selection stopped at the transient frame: ${game.taps}", game.taps.size > 1)
            assertTrue(result.text("reason") != "unknown_scene")
        } finally {
            PhoneDdzTurn.advise = saved
        }
    }

    @Test fun humanFollowUsesTheAdvisorToPlayVisibleCards() = runTest {
        val asked = mutableListOf<String>()
        val saved = PhoneDdzTurn.advise
        PhoneDdzTurn.advise = { _, body -> asked += body; """{"play":["3"]}""" }
        try {
            val follow = full("hand_t1")
            val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(List(12) { follow }))
            val dispatcher = toolDispatcher(game)
            PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
            PhoneDdzTurn.useTracker(dispatcher.ctx, leftLedPairs())
            val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
            assertTrue("advisor was called", asked.isNotEmpty())
            assertTrue("a follow card was submitted", game.taps.isNotEmpty())
            assertEquals("follow", result.text("phase"))
        } finally {
            PhoneDdzTurn.advise = saved
        }
    }

    @Test fun humanResultCanStartTheNextGameOnlyThroughAnExplicitAgain() = runTest {
        val result = full("result")
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(result, result, full("call"))))
        val dispatcher = toolDispatcher(game)
        val start = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        assertEquals("result", start.text("phase"))
        val next = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"again"}""").jsonObject), dispatcher.ctx)
        assertEquals("call", next.text("phase"))
        assertEquals(2, game.taps.size)
    }

    @Test fun humanHistoryMismatchFinishesTheHandByRules() = runTest {
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(full("hand_t1"))))
        val dispatcher = toolDispatcher(game)
        PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        PhoneDdzTurn.useTracker(dispatcher.ctx, teammateFollowTracker().apply { fail("douzero_history_mismatch") })
        val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
        assertEquals(result.content.toString() + game.taps, "douzero_history_mismatch", result.text("douzeroOff"))
        assertNotEquals(result.content.toString() + game.taps, "paused", result.text("state"))
        assertTrue("the rules answer the follow: ${game.taps}", game.taps.isNotEmpty())
    }

    @Test fun humanPausePointsTheModelToSnapshotResume() = runTest {
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(full("hand_t1"))))
        val dispatcher = toolDispatcher(game)
        PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        PhoneDdzTurn.useTracker(dispatcher.ctx, DdzTracker(DdzSettlingPolicy.HUMAN).apply { fail("not_my_turn") })

        val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
        assertEquals("paused", result.text("state"))
        val recovery = result.content["recovery"]!!.jsonObject

        assertEquals("resume", recovery["action"]!!.jsonPrimitive.content)
        assertEquals(setOf("hand", "bottom", "history", "phase", "seat"), recovery["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
    }

    @Test fun anUnreadableHandWithTheExpectedCardsStillLetsDouZeroPlay() = runTest {
        // Live 2026-11-11: our 8-card hand became unreadable on our turn and the whole hand was
        // given up as missed_play. The previous DouZero play tells us exactly what is left.
        val asked = mutableListOf<String>()
        val saved = PhoneDdzTurn.advise
        PhoneDdzTurn.advise = { _, body -> asked += body; """{"play":["3"]}""" }
        try {
            val unreadable = unreadableHand(liftIndex = 15)
            val after = oneCardLess()
            assertNull("construction: the hand must not be readable", DdzCards.hand(unreadable, DdzScreen.read(unreadable)))
            assertEquals("construction: one card left the hand", 16, DdzScreen.read(after).handCount)
            val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(List(5) { unreadable } + listOf(after, full("result"))))
            val dispatcher = toolDispatcher(game)
            PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
            PhoneDdzTurn.useTracker(dispatcher.ctx, readyFollowTracker(), expectedHand = handT1)
            val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
            assertEquals(result.content.toString(), handT1, Json.parseToJsonElement(asked.single()).jsonObject["hand"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(result.content.toString(), listOf(DdzScreen.read(unreadable).buttons.last().let { it.x to it.y }), game.taps)
            assertNull("the hand continues on DouZero", result.text("douzeroOff"))
            assertEquals("result", result.text("phase"))
        } finally {
            PhoneDdzTurn.advise = saved
        }
    }

    @Test fun anUnreadableHandWithoutTheExpectedCardsFallsBackAsUnreadHand() = runTest {
        val saved = PhoneDdzTurn.advise
        PhoneDdzTurn.advise = { _, _ -> error("DouZero is not asked when the hand is unknown") }
        try {
            val unreadable = unreadableHand()
            assertEquals("follow", DdzScreen.read(unreadable).phase.name.lowercase())
            assertEquals(17, DdzScreen.read(unreadable).handCount)
            assertTrue(DdzCards.classicHumanTable(unreadable))
            var reads = 0
            var firstTapReads = -1
            val game = object : Game(
                PhoneDdzTurn.PACKAGE, ArrayDeque(List(60) { unreadable } + List(3) { lobbyFrame("vd_lobby") }),
            ) {
                override suspend fun rawFrame(rows: List<Int>): RawFrame? { reads++; return super.rawFrame(rows) }
                override suspend fun quickTap(x: Int, y: Int): Boolean {
                    if (firstTapReads < 0) firstTapReads = reads
                    return super.quickTap(x, y)
                }
            }
            val dispatcher = toolDispatcher(game)
            PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
            PhoneDdzTurn.useTracker(dispatcher.ctx, readyFollowTracker())
            val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
            assertEquals("reads=$reads firstTap=$firstTapReads ${result.content}", "unread_hand", result.text("douzeroOff"))
            assertTrue("waited the settling window: $firstTapReads", firstTapReads > 40)
        } finally {
            PhoneDdzTurn.advise = saved
        }
    }

    @Test fun aTrackerThatIsNotReadyStillFailsAsMissedPlay() = runTest {
        val saved = PhoneDdzTurn.advise
        PhoneDdzTurn.advise = { _, _ -> error("DouZero is not asked while the history is unread") }
        try {
            // 两家的出牌区都读成空的:跟踪器一直认不出地主,没 ready
            val unreadable = unreadableHand(noPlays = true)
            assertNull("construction: the hand must not be readable", DdzCards.hand(unreadable, DdzScreen.read(unreadable)))
            assertEquals("follow", DdzScreen.read(unreadable).phase.name.lowercase())
            assertEquals(17, DdzScreen.read(unreadable).handCount)
            assertTrue(DdzCards.classicHumanTable(unreadable))
            val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(List(60) { unreadable } + List(3) { lobbyFrame("vd_lobby") }))
            val dispatcher = toolDispatcher(game)
            PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
            PhoneDdzTurn.useTracker(dispatcher.ctx, DdzTracker(DdzSettlingPolicy.HUMAN))
            val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
            assertEquals(result.content.toString(), "missed_play", result.text("douzeroOff"))
        } finally {
            PhoneDdzTurn.advise = saved
        }
    }

    @Test fun humanHandDriftFinishesTheHandByRules() = runTest {
        // 认到的牌和上一手该剩的不一样:不停下把这一手交给托管,这一局剩下的按简单规则打完
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(full("hand_t1"))))
        val dispatcher = toolDispatcher(game)
        PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        PhoneDdzTurn.useTracker(dispatcher.ctx, teammateFollowTracker(), expectedHand = listOf("3"))
        val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
        assertEquals(result.content.toString() + game.taps, "hand_drift", result.text("douzeroOff"))
        assertNotEquals(result.content.toString() + game.taps, "paused", result.text("state"))
        assertTrue("the rules answer the follow: ${game.taps}", game.taps.isNotEmpty())
    }

    @Test fun humanAdvisorFailureFinishesTheHandByRules() = runTest {
        val saved = PhoneDdzTurn.advise
        PhoneDdzTurn.advise = { _, _ -> null }
        try {
            val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(full("hand_t1"))))
            val dispatcher = toolDispatcher(game)
            PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
            PhoneDdzTurn.useTracker(dispatcher.ctx, teammateFollowTracker())
            val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
            assertEquals("douzero_unreachable", result.text("douzeroOff"))
            assertNotEquals(result.content.toString() + game.taps, "paused", result.text("state"))
            assertTrue("the rules answer the follow: ${game.taps}", game.taps.isNotEmpty())
        } finally {
            PhoneDdzTurn.advise = saved
        }
    }

    @Test fun humanAdvisorThrowingFinishesTheHandByRules() = runTest {
        // 给 advisor 的接口抛异常(比如序列化坏了)和连不上一样处理,不能把整次调用炸掉
        val saved = PhoneDdzTurn.advise
        PhoneDdzTurn.advise = { _, _ -> error("advisor down") }
        try {
            val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(full("hand_t1"))))
            val dispatcher = toolDispatcher(game)
            PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
            PhoneDdzTurn.useTracker(dispatcher.ctx, teammateFollowTracker())
            val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
            assertEquals("douzero_unreachable", result.text("douzeroOff"))
            assertNotEquals(result.content.toString() + game.taps, "paused", result.text("state"))
            assertTrue("the rules answer the follow: ${game.taps}", game.taps.isNotEmpty())
        } finally {
            PhoneDdzTurn.advise = saved
        }
    }

    @Test fun humanBadAdvisorReplyFinishesTheHandByRules() = runTest {
        val saved = PhoneDdzTurn.advise
        PhoneDdzTurn.advise = { _, _ -> """{"play":3}""" }
        try {
            val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(full("hand_t1"))))
            val dispatcher = toolDispatcher(game)
            PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
            PhoneDdzTurn.useTracker(dispatcher.ctx, teammateFollowTracker())
            val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
            assertEquals("douzero_bad_reply", result.text("douzeroOff"))
            assertNotEquals(result.content.toString() + game.taps, "paused", result.text("state"))
            assertTrue("the rules answer the follow: ${game.taps}", game.taps.isNotEmpty())
        } finally {
            PhoneDdzTurn.advise = saved
        }
    }

    @Test fun humanSelectFailureFinishesTheHandByRules() = runTest {
        // DouZero 要的牌抬不起来(SelectFailed):这一手按简单规则出,而不是暂停交给托管
        val saved = PhoneDdzTurn.advise
        PhoneDdzTurn.advise = { _, _ -> """{"play":["3"]}""" }
        try {
            val follow = full("hand_t1")
            val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(List(20) { follow }))
            val dispatcher = toolDispatcher(game)
            PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
            // 左家是队友、右家是地主:简单规则跟牌时就是不出
            PhoneDdzTurn.useTracker(dispatcher.ctx, DdzTracker(DdzSettlingPolicy.HUMAN).apply {
                restore(DdzSeat.Right, listOf("J", "7", "4"), emptyList(), current = DdzSeat.Me)
            })
            val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
            assertEquals(result.content.toString() + game.taps, "select_failed", result.text("douzeroOff"))
            assertNotEquals(result.content.toString() + game.taps, "paused", result.text("state"))
            assertEquals("pass on the teammate's play", DdzScreen.read(follow).buttons[0].let { it.x to it.y }, game.taps.last())
        } finally {
            PhoneDdzTurn.advise = saved
        }
    }

    @Test fun aPlayThatDriftedAfterThePressIsNotPlayedAgainFromTheOldScreen() = runTest {
        // 按了出牌、却看不出出去的是不是这几张(Drift):牌已经出去了,轮次也过了;
        // 不能拿按之前那一帧再按简单规则出一次(会在别家回合乱点,或下一手按旧下标点错牌)
        val saved = PhoneDdzTurn.advise
        PhoneDdzTurn.advise = { _, _ -> """{"play":["Q","Q","Q","7"]}""" }
        try {
            val hint = shot("human_hand_hint_raised_qqq107") // 游戏已经把 Q Q Q 7 抬起
            val play = DdzScreen.read(hint).buttons.last().let { it.x to it.y }
            val game = object : Game(PhoneDdzTurn.PACKAGE, ArrayDeque(List(20) { hint })) {
                override suspend fun quickTap(x: Int, y: Int): Boolean {
                    taps += x to y
                    // 按下出牌后 verify 那十二帧都认不出(报 Drift),之后这一局结束、出现结算页
                    if (x to y == play) { frames.clear(); frames.addAll(List(12) { fullBlank } + full("human_result_leave_change_continue")) }
                    return true
                }
            }
            val dispatcher = toolDispatcher(game)
            PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
            PhoneDdzTurn.useTracker(dispatcher.ctx, DdzTracker(DdzSettlingPolicy.HUMAN).apply {
                restore(DdzSeat.Right, listOf("J", "7", "4"), emptyList(), current = DdzSeat.Me)
            })
            val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
            assertEquals(result.content.toString() + game.taps, "hand_drift", result.text("douzeroOff"))
            assertNotEquals(result.content.toString() + game.taps, "paused", result.text("state"))
            assertEquals("只按了一次出牌,之后什么都没点", listOf(play), game.taps)
        } finally {
            PhoneDdzTurn.advise = saved
        }
    }

    @Test fun aHandBrokenMidHandKeepsUsingTheRulesOnLaterTurns() = runTest {
        // 同一局后面的每一手也不再问 DouZero、不暂停,直到下一局叫分
        val saved = PhoneDdzTurn.advise
        PhoneDdzTurn.advise = { _, _ -> error("DouZero must not be asked again this hand") }
        try {
            val follow = full("hand_t1")
            val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(List(20) { follow }))
            val dispatcher = toolDispatcher(game)
            PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
            PhoneDdzTurn.useTracker(dispatcher.ctx, DdzTracker(DdzSettlingPolicy.HUMAN).apply {
                restore(DdzSeat.Right, listOf("J", "7", "4"), emptyList(), current = DdzSeat.Me)
                fail("douzero_bad_reply")
            })
            val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
            assertEquals("douzero_bad_reply", result.text("douzeroOff"))
            assertNotEquals(result.content.toString() + game.taps, "paused", result.text("state"))
            assertEquals("pass on the teammate's play", DdzScreen.read(follow).buttons[0].let { it.x to it.y }, game.taps.first())
        } finally {
            PhoneDdzTurn.advise = saved
        }
    }

    @Test fun humanHandCountMismatchFinishesTheHandByRules() = runTest {
        var calls = 0
        val saved = PhoneDdzTurn.advise
        PhoneDdzTurn.advise = { _, _ -> calls++; """{"error":"hand_count_mismatch"}""" }
        try {
            val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(full("hand_t1"))))
            val dispatcher = toolDispatcher(game)
            PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
            PhoneDdzTurn.useTracker(dispatcher.ctx, teammateFollowTracker())
            val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
            assertEquals("douzero_hand_count_mismatch", result.text("douzeroOff"))
            assertNotEquals(result.content.toString() + game.taps, "paused", result.text("state"))
            assertNull("no snapshot resume once the hand plays on", result.content["recovery"])
            assertEquals("a deterministic mismatch is not retried", 1, calls)
            assertTrue("the rules answer the follow: ${game.taps}", game.taps.isNotEmpty())
        } finally {
            PhoneDdzTurn.advise = saved
        }
    }

    @Test fun humanAdvisorFailureRetriesOnceBeforePausing() = runTest {
        var calls = 0
        val saved = PhoneDdzTurn.advise
        PhoneDdzTurn.advise = { _, _ -> if (calls++ == 0) null else """{"play":["3"]}""" }
        try {
            val follow = full("hand_t1")
            val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(List(12) { follow }))
            val dispatcher = toolDispatcher(game)
            PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
            PhoneDdzTurn.useTracker(dispatcher.ctx, leftLedPairs())
            val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
            assertTrue(result.toString(), result.text("reason") != "service_unavailable")
            assertTrue("the advisor is retried after one failure", calls >= 2)
            assertTrue("the retried advice was played", game.taps.isNotEmpty())
        } finally {
            PhoneDdzTurn.advise = saved
        }
    }

    @Test fun humanPopupPausesWithoutTreatingItAsAPlayableScene() = runTest {
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(full("call"), full("vd_relief"))))
        val dispatcher = toolDispatcher(game)
        PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
        assertEquals("paused", result.text("state"))
        assertEquals("unknown_popup", result.text("reason"))
        assertTrue(game.taps.isEmpty())
    }

    @Test fun humanLobbyReturnPausesWithAnExplicitReason() = runTest {
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(full("call"), lobbyFrame("vd_lobby"))))
        val dispatcher = toolDispatcher(game)
        PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
        assertEquals("paused", result.text("state"))
        assertEquals("lobby_return", result.text("reason"))
        assertNull("the game ended; there is no table to resume", result.content["recovery"])
        val again = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
        assertEquals("lobby_return", again.text("reason"))
        assertNull(again.content["recovery"])
        assertTrue(game.taps.isEmpty())
    }

    @Test fun humanSceneRetryReportsALobbyThatAppearsMidRetry() = runTest {
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(full("call"), fullBlank, lobbyFrame("vd_lobby"))))
        val dispatcher = toolDispatcher(game)
        PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
        assertEquals("paused", result.text("state"))
        assertEquals("lobby_return", result.text("reason"))
        assertTrue(game.taps.isEmpty())
    }

    @Test fun humanLeadWithAnEmptyAdvisorPlayFallsBackToTheSmallestCard() = runTest {
        // 先出时 DouZero 没给明确的牌:猜一张可能出成别的牌,这一局剩下的按简单规则打(出最小的)
        val saved = PhoneDdzTurn.advise
        PhoneDdzTurn.advise = { _, _ -> """{"play":[]}""" }
        try {
            val lifted = liftedLastCard("lead")
            val after = withoutLastCard("lead")
            var played = false
            val game = object : Game(PhoneDdzTurn.PACKAGE, ArrayDeque(List(8) { lifted })) {
                override suspend fun rawFrame(rows: List<Int>): RawFrame? = if (played) after else super.rawFrame(rows)
                override suspend fun quickTap(x: Int, y: Int): Boolean { taps += x to y; played = true; return true }
            }
            val dispatcher = toolDispatcher(game)
            PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
            PhoneDdzTurn.useTracker(dispatcher.ctx, DdzTracker(DdzSettlingPolicy.HUMAN).apply {
                observe(DdzSeat.Me, { DdzSeen.Empty }, { listOf("3", "4", "5") }, leadingWith20 = true, leading = true)
            })
            val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
            assertEquals(result.content.toString() + game.taps, "invalid_advisor_play", result.text("douzeroOff"))
            assertNotEquals(result.content.toString() + game.taps, "paused", result.text("state"))
            assertEquals("the smallest card was played", DdzScreen.read(lifted).buttons[0].let { it.x to it.y }, game.taps.single())
        } finally {
            PhoneDdzTurn.advise = saved
        }
    }

    @Test fun resumeRequiresTheVisibleStateToMatchBeforeItActs() = runTest {
        val call = full("call")
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(call, call)))
        val dispatcher = toolDispatcher(game)
        PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        val hand = listOf("3", "3", "3", "3", "4", "4", "4", "4", "5", "5", "5", "5", "6", "6", "6", "6", "7", "7", "7", "7")
        val resumed = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""
            {"action":"resume","hand":${hand.joinToString(",", "[", "]") { "\"$it\"" }},"bottom":["8","9","10"],"history":[],"phase":"call","seat":"landlord"}
        """.trimIndent()).jsonObject), dispatcher.ctx)
        assertEquals("true", resumed.text("ok"))
        val next = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
        assertEquals("paused", next.text("state"))
        assertEquals("snapshot_mismatch", next.text("reason"))
        assertTrue(game.taps.isEmpty())
    }

    @Test fun snapshotRecoveryRequiresTheExactRemainingHandCount() {
        val snapshot = DdzSnapshot(
            hand = listOf("3"), bottom = listOf("4", "5", "6"), history = listOf(listOf("7")),
            phase = "follow", seat = "landlord_up",
        )
        val result = DdzSnapshotRecovery.recover(snapshot)
        assertTrue(result is DdzRecoveryResult.Invalid)
        assertEquals("hand_count", (result as DdzRecoveryResult.Invalid).failure.reason)
    }

    @Test fun snapshotRecoveryRejectsASeatThatIsNotTheCurrentActor() {
        val snapshot = DdzSnapshot(
            hand = listOf("4", "5", "6", "7", "8", "9", "10", "J", "Q", "K", "A", "2", "SJ", "BJ", "3", "4", "5", "6", "7"),
            bottom = listOf("8", "9", "10"), history = listOf(listOf("3")),
            phase = "follow", seat = "landlord",
        )
        val result = DdzSnapshotRecovery.recover(snapshot)
        assertTrue(result is DdzRecoveryResult.Invalid)
        assertEquals("actor", (result as DdzRecoveryResult.Invalid).failure.reason)
    }

    @Test fun classicHumanEligibilityRequiresThePositiveClassicTableMarker() {
        assertTrue(DdzCards.classicHumanTable(full("call")))
        assertFalse(DdzCards.classicHumanTable(blank))
        // Live 2026-11-11: the 经典模式 room list's white room cards read as two cards in hand and the blue 中级场 card as a button
        assertFalse(DdzCards.classicHumanTable(full("popup_classic_lobby")))
        // Live 2026-11-11: a reward video is mostly black (passes the dark watermark test) with a white card that reads as a hand
        assertFalse(DdzCards.classicHumanTable(full("popup_reward_video_kuaishou")))
    }

    @Test fun classicHumanEligibilityAcceptsTheKnownAutoplayOverlay() {
        assertEquals(DdzPhase.AutoPlay, DdzScreen.read(full("auto")).phase)
        assertTrue(DdzCards.classicHumanTable(full("auto")))
    }

    @Test fun humanPopupHasADistinctPauseReason() = runTest {
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(full("call"), full("vd_relief"))))
        val dispatcher = toolDispatcher(game)
        PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
        assertEquals("paused", result.text("state"))
        assertEquals("unknown_popup", result.text("reason"))
        assertTrue(game.taps.isEmpty())
    }

    @Test fun humanToolPromptDocumentsWaitAndNestedResumeSchema() {
        assertTrue(PhoneDdzTurn.def.description.contains("wait"))
        assertTrue(PhoneDdzTurn.def.description.contains("call"))
        assertTrue(PhoneDdzTurn.def.description.contains("pause"))
        assertTrue(PhoneDdzTurn.def.description.contains("do not call `start` or `play`"))
        assertTrue(PhoneDdzTurn.def.description.contains("call `resume`"))
        val schema = Json.parseToJsonElement(PhoneDdzTurn.def.spec().parametersJsonSchema).jsonObject
        val snapshot = schema["properties"]!!.jsonObject["snapshot"]!!.jsonObject
        assertEquals(setOf("hand", "bottom", "history", "phase", "seat"), snapshot["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertEquals(setOf("call", "double", "lead", "follow"), snapshot["properties"]!!.jsonObject["phase"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertEquals(setOf("landlord", "landlord_up", "landlord_down"), snapshot["properties"]!!.jsonObject["seat"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
    }
}
