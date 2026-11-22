package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.uistate.RawFrame
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import javax.imageio.ImageIO

/** 牌由电脑认:测试里 [JjDdzTurn.detect] 按顺序回预先摆好的牌,用完了回 null(连着认不了就停下,测试也就结束)。 */
class JjDdzTurnTest {
    private var now = 0L

    @Before fun stub() {
        PhoneDdzTurn.probeAdvisor = { true }
        PhoneDdzTurn.adviseBid = { _, _ -> null }
        PhoneDdzTurn.advise = { _, _ -> null }
        PhoneDdzTurn.clock = { now }
        JjDdzTurn.think = {}
    }

    @After fun restore() {
        PhoneDdzTurn.probeAdvisor = DdzAdvisor::probe
        PhoneDdzTurn.clock = System::currentTimeMillis
        JjDdzTurn.detect = JjCards::detect
    }

    private fun frame(name: String): RawFrame {
        val image = ImageIO.read(javaClass.getResourceAsStream("/ddz-jj/$name.jpg")!!)
        return RawFrame(image.width, image.height, JjDdzScreen.ROWS.associateWith { image.getRGB(0, it, image.width, 1, null, 0, image.width) })
    }

    private class Game(var app: String, var frame: RawFrame) : ScreenPort by FakeScreen() {
        val taps = mutableListOf<Pair<Int, Int>>()
        override suspend fun peek() = ScreenSnapshot("p1", app, emptyList(), frame.width, frame.height)
        override suspend fun rawFrame(rows: List<Int>) = frame
        override suspend fun quickShot() = ByteArray(1)
        override suspend fun quickTap(x: Int, y: Int): Boolean { taps += x to y; return true }
        override suspend fun pressAt(x: Int, y: Int, holdMs: Long): Boolean = quickTap(x, y)
    }

    /** 手牌一排:点数框从 x=500 起每张 96,上沿平放 770、抬起 735。 */
    private fun hand(ranks: List<String>, lifted: Set<Int> = emptySet()) = ranks.mapIndexed { i, r ->
        val y = if (i in lifted) 735.0 else 770.0
        JjCard(r, 500.0 + i * 96, y, 545.0 + i * 96, y + 78, 0.99)
    }

    private fun replies(vararg frames: List<JjCard>) {
        val queue = ArrayDeque(frames.toList())
        JjDdzTurn.detect = { _, _ -> queue.removeFirstOrNull() }
    }

    private suspend fun run(game: Game, json: String, ctx: ToolContext = toolDispatcher(game).ctx): ToolExecution =
        PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement(json).jsonObject), ctx)

    private fun ToolExecution.text(key: String) = content[key]?.jsonPrimitive?.content

    private val start = """{"action":"start","mode":"human","advisor":"http://mac:3426","auto":true}"""
    private val seventeen = listOf("BJ", "2", "2", "A", "K", "Q", "Q", "J", "10", "9", "8", "7", "6", "5", "4", "3", "3")

    @Test fun jjGoesToItsOwnTurnLoop() = runTest {
        replies()
        val result = run(Game(JjDdzScreen.PACKAGE, frame("final_page")), start)
        assertEquals("jj", result.text("game"))
        // 电脑一次都没认出来:停下,一下都没点
        assertEquals("paused", result.text("state"))
        assertEquals(DdzSessionReason.SERVICE_UNAVAILABLE, result.text("reason"))
    }

    @Test fun bidsThreeWhenDouZeroLikesTheHand() = runTest {
        PhoneDdzTurn.adviseBid = { _, _ -> PhoneDdzTurn.BidAdvice(3, 0.8) }
        replies(hand(seventeen), hand(seventeen))
        val game = Game(JjDdzScreen.PACKAGE, frame("call_beach"))
        run(game, start)
        val three = JjDdzScreen.read(game.frame).buttons[3]
        assertEquals(listOf(three.x to JjDdzScreen.TAP_Y), game.taps)
    }

    @Test fun bidsAgainAfterARedeal() = runTest {
        // 都不叫重新发牌:手牌一直没空,新发的 17 张也要叫(真机 10:02 没叫,超时进了托管)
        PhoneDdzTurn.adviseBid = { _, _ -> PhoneDdzTurn.BidAdvice(3, 0.8) }
        val game = Game(JjDdzScreen.PACKAGE, frame("call_beach"))
        val redealt = listOf("SJ", "2", "A", "K", "K", "K", "K", "J", "10", "10", "8", "8", "7", "7", "6", "4", "3")
        // 一帧认牌之后换下一帧画面:叫分 → 别人叫分(没按钮)→ 新发的牌又轮到我叫
        val steps = ArrayDeque(listOf(hand(seventeen) to "beach_scenery", hand(seventeen) to "call_beach", hand(redealt) to "call_beach", hand(redealt) to "call_beach"))
        JjDdzTurn.detect = { _, _ -> steps.removeFirstOrNull()?.let { (cards, next) -> game.frame = frame(next); cards } }
        run(game, start)
        val three = JjDdzScreen.read(frame("call_beach")).buttons[3]
        assertEquals(listOf(three.x to JjDdzScreen.TAP_Y, three.x to JjDdzScreen.TAP_Y), game.taps)
    }

    @Test fun passesOnTheGrayThreeWhenTheBidIsTaken() = runTest {
        // 别人叫过 2 分:1、2 灰了,规则想叫 1 分也不行,不叫
        PhoneDdzTurn.adviseBid = { _, _ -> PhoneDdzTurn.BidAdvice(1, 0.5) }
        replies(hand(seventeen))
        val game = Game(JjDdzScreen.PACKAGE, frame("call_sea_2412"))
        run(game, start)
        assertEquals(JjDdzScreen.read(game.frame).buttons[0].x, game.taps.single().first)
    }

    @Test fun cannotBeatTapsTheOnlyButton() = runTest {
        replies(hand(listOf("4", "3")))
        val game = Game(JjDdzScreen.PACKAGE, frame("only_pass"))
        run(game, start)
        assertEquals(JjDdzScreen.read(game.frame).buttons.single().x to JjDdzScreen.TAP_Y, game.taps.single())
    }

    @Test fun tapsPlayAgainOnTheFinalPage() = runTest {
        now = 60_000
        replies(emptyList(), emptyList())
        val game = Game(JjDdzScreen.PACKAGE, frame("final_page"))
        run(game, """{"action":"start","mode":"human","advisor":"http://mac:3426","auto":true,"hands":2}""")
        val again = JjDdzScreen.read(game.frame).buttons.single()
        assertEquals(listOf(again.x to JjDdzScreen.AGAIN_TAP_Y), game.taps)
    }

    @Test fun cancelsAutoPlayRightAway() = runTest {
        now = 60_000
        replies(hand(listOf("2", "2", "A")))
        val game = Game(JjDdzScreen.PACKAGE, frame("auto_play"))
        run(game, start)
        assertEquals(JjDdzScreen.read(game.frame).buttons.single().x to JjDdzScreen.AUTO_TAP_Y, game.taps.first())
    }

    @Test fun waitsOutALongBreakBetweenTournamentRounds() = runTest {
        // 真机 11:32:轮间「比赛进度」画面超过 90 秒没有手牌,工具停了。现在等满 5 分钟才停
        JjDdzTurn.detect = { _, _ -> now += 10_000; emptyList() }
        val result = run(Game(JjDdzScreen.PACKAGE, frame("beach_scenery")), start)
        assertEquals(DdzSessionReason.UNKNOWN_SCENE, result.text("reason"))
        assertTrue("paused after ${now}ms", now > 300_000)
    }

    @Test fun neverTapsTheLobbyPool() = runTest {
        // 斗地主大厅泳池的池水在「取消托管」那里也是蓝的;没有手牌就不能当托管去点
        now = 60_000
        replies(emptyList(), emptyList(), emptyList())
        val game = Game(JjDdzScreen.PACKAGE, frame("lobby_pool"))
        run(game, start)
        assertTrue(game.taps.isEmpty())
    }

    @Test fun lowersTheExtraCardsTheGameLiftedByItself() = runTest {
        // 点了两张 7,游戏把四张 7 都抬了起来(读稳两次),要把多起来的两张放下
        val ranks = listOf("7", "7", "7", "7", "5", "4")
        replies(hand(ranks, setOf(0, 1, 2, 3)), hand(ranks, setOf(0, 1, 2, 3)), hand(ranks, setOf(0, 1)), hand(ranks, setOf(0, 1)))
        val game = Game(JjDdzScreen.PACKAGE, frame("follow"))
        val ok = JjDdzTurn.select(toolDispatcher(game).ctx, JjDdzTurn.Session("http://mac:3426", 1), setOf(0, 1), JjCards.table(hand(ranks), 2408))
        assertTrue(ok)
        val xs = game.taps.map { it.first }
        assertEquals(listOf(0, 1, 2, 3).map { 500 + it * 96 + 22 }, xs)
    }

    @Test fun anyTwoOfThreeAcesArePairOfAces() = runTest {
        // 真机 11:13:要出对 A(序号 0、1),游戏抬起的是 1、2:点数对就算选好了,不再来回点
        val ranks = listOf("A", "A", "A", "J", "9")
        replies(hand(ranks, setOf(1, 2)), hand(ranks, setOf(1, 2)))
        val game = Game(JjDdzScreen.PACKAGE, frame("follow"))
        val ok = JjDdzTurn.select(toolDispatcher(game).ctx, JjDdzTurn.Session("http://mac:3426", 1), setOf(0, 1), JjCards.table(hand(ranks), 2408))
        assertTrue(ok)
        assertEquals(listOf(0, 1).map { 500 + it * 96 + 22 }, game.taps.map { it.first })
    }

    @Test fun putsTheGamesHintDownBeforeLiftingTheWantedCard() = runTest {
        // 真机 20:04:轮到我时游戏已经提示抬起一张 6;要出的是 2。先放下 6、读稳,再抬 2
        val ranks = listOf("SJ", "2", "A", "6", "5")
        replies(hand(ranks), hand(ranks), hand(ranks, setOf(1)), hand(ranks, setOf(1)))
        val game = Game(JjDdzScreen.PACKAGE, frame("follow"))
        val ok = JjDdzTurn.select(toolDispatcher(game).ctx, JjDdzTurn.Session("http://mac:3426", 1), setOf(1), JjCards.table(hand(ranks, setOf(3)), 2408))
        assertTrue(ok)
        assertEquals(listOf(3, 1).map { 500 + it * 96 + 22 }, game.taps.map { it.first })
    }

    @Test fun waitsForTheWholeDealBeforeTracking() = runTest {
        // 发牌动画:先看到 10 张,等到 17 张才从头记;不能当成中途进来
        replies(hand(seventeen.take(10)), hand(seventeen), hand(seventeen))
        val result = run(Game(JjDdzScreen.PACKAGE, frame("left_turn")), start)
        assertEquals(null, result.text("douzeroOff"))
        assertEquals("17", result.text("handCount"))
    }

    @Test fun joinedMidHandFollowsByPassing() = runTest {
        // 中途进来(9 张、在跟牌):不问 DouZero,规则是不出
        replies(hand(List(9) { "5" }))
        val game = Game(JjDdzScreen.PACKAGE, frame("follow"))
        val result = run(game, start)
        assertEquals(JjDdzScreen.read(game.frame).buttons[0].x to JjDdzScreen.TAP_Y, game.taps.single())
        assertEquals(DdzSessionReason.JOINED_MID_GAME, result.text("douzeroOff"))
    }

    @Test fun joinedMidHandLeadsTheSmallestCard() = runTest {
        replies(hand(listOf("A", "9", "3")))
        val game = Game(JjDdzScreen.PACKAGE, frame("lead"))
        run(game, start)
        val play = JjDdzScreen.read(game.frame).buttons[2]
        assertEquals(listOf((500 + 2 * 96 + 22) to JjCards.HAND_TAP_Y, play.x to JjDdzScreen.TAP_Y), game.taps)
    }

    @Test fun joinedMidHandLeadsTheSmallestPairBeforeASingle() = runTest {
        // 真机 15:13:记牌断了按规则先出,出了单张 3,地主只剩一张。有对子先出最小的对子
        val ranks = listOf("A", "9", "9", "3")
        replies(hand(ranks), hand(ranks, setOf(1, 2)), hand(ranks, setOf(1, 2)))
        val game = Game(JjDdzScreen.PACKAGE, frame("lead"))
        run(game, start)
        assertEquals(listOf(1, 2).map { 500 + it * 96 + 22 }, game.taps.take(2).map { it.first })
        assertEquals(JjDdzScreen.read(game.frame).buttons[2].x, game.taps.last().first)
    }

    @Test fun aBombIsNotTheFallbackLead() = runTest {
        val ranks = listOf("A", "7", "7", "7", "7", "3")
        assertEquals(null, JjDdzTurn.smallestGroup(JjCards.table(hand(ranks), 2408)))
        assertEquals(setOf(1, 2, 3), JjDdzTurn.smallestGroup(JjCards.table(hand(listOf("K", "8", "8", "8", "4")), 2408)))
    }

    @Test fun aRocketAnimationHidingTheHandDoesNotEndTheHand() = runTest {
        // 真机 15:04:王炸特效挡了手牌几帧,工具当成一手打完,手牌回来又当成中途进来,按规则先出了单张 9
        val sixteen = seventeen.drop(1)
        replies(hand(seventeen), hand(sixteen), *Array(6) { emptyList<JjCard>() }, hand(sixteen))
        val game = Game(JjDdzScreen.PACKAGE, frame("beach_scenery"))
        val result = run(game, """{"action":"start","mode":"human","advisor":"http://mac:3426","auto":true,"hands":3}""")
        assertEquals("0", result.text("handsPlayed"))
        assertEquals(null, result.text("douzeroOff"))
    }

    @Test fun aSteadyLowScoreReadCountsAfterAWhile() {
        // 真机 15:30:地主出小王,分数一直 0.73–0.80(门槛 0.8),原来等 4 秒就断了记牌。连着 1.5 秒一样就算认准
        val s = JjDdzTurn.Session("http://mac:3426", 1)
        val joker = JjCards.table(listOf(JjCard("SJ", 1600.0, 272.0, 1640.0, 325.0, 0.74)), 2408)
        now = 0
        assertEquals(DdzSeen.Unsure, JjDdzTurn.steady(s, joker, DdzSide.Right))
        now = 1_000
        assertEquals(DdzSeen.Unsure, JjDdzTurn.steady(s, joker, DdzSide.Right))
        now = 1_600
        assertEquals(DdzSeen.Cards(listOf("SJ")), JjDdzTurn.steady(s, joker, DdzSide.Right))
    }

    @Test fun aChangingOrVeryLowReadNeverCounts() {
        val s = JjDdzTurn.Session("http://mac:3426", 1)
        val sj = JjCards.table(listOf(JjCard("SJ", 1600.0, 272.0, 1640.0, 325.0, 0.74)), 2408)
        val bj = JjCards.table(listOf(JjCard("BJ", 1600.0, 272.0, 1640.0, 325.0, 0.74)), 2408)
        val faint = JjCards.table(listOf(JjCard("SJ", 1600.0, 272.0, 1640.0, 325.0, 0.4)), 2408)
        now = 0; JjDdzTurn.steady(s, sj, DdzSide.Right)
        now = 1_000; JjDdzTurn.steady(s, bj, DdzSide.Right)
        now = 2_000
        assertEquals(DdzSeen.Unsure, JjDdzTurn.steady(s, bj, DdzSide.Right))
        now = 0; JjDdzTurn.steady(s, faint, DdzSide.Right)
        now = 5_000
        assertEquals(DdzSeen.Unsure, JjDdzTurn.steady(s, faint, DdzSide.Right))
    }

    @Test fun theFallbackBeatsOnlyTheLandlord() {
        val s = JjDdzTurn.Session("http://mac:3426", 1)
        val rightPlayed = JjCards.table(listOf(card("PASS", 650.0, 200.0), card("SJ", 1600.0, 272.0)), 2408)
        val leftPlayed = JjCards.table(listOf(card("9", 650.0, 272.0)), 2408)
        // 地主还没认出来:不压
        assertTrue(!JjDdzTurn.beatsByHint(s, rightPlayed))
        s.lordSeen = DdzSeat.Right; s.lordFrames = 10
        assertTrue(JjDdzTurn.beatsByHint(s, rightPlayed))
        // 左家是队友:他的牌不压
        assertTrue(!JjDdzTurn.beatsByHint(s, leftPlayed))
        s.lordSeen = DdzSeat.Me; s.lordFrames = 20
        assertTrue(JjDdzTurn.beatsByHint(s, leftPlayed))
    }

    private fun card(rank: String, x: Double, y: Double) = JjCard(rank, x, y, x + 40, y + 53, 0.99)

    @Test fun switchingAwayPausesWithoutTapping() = runTest {
        replies(hand(seventeen))
        val game = Game(JjDdzScreen.PACKAGE, frame("left_turn"))
        val ctx = toolDispatcher(game).ctx
        run(game, start, ctx)
        game.app = "com.tencent.mm"
        val result = run(game, """{"action":"wait"}""", ctx)
        assertEquals(DdzSessionReason.APP_SWITCH, result.text("reason"))
        assertTrue(game.taps.isEmpty())
    }

    @Test fun stopEndsTheSession() = runTest {
        replies()
        val game = Game(JjDdzScreen.PACKAGE, frame("left_turn"))
        val ctx = toolDispatcher(game).ctx
        run(game, start, ctx)
        assertEquals("stopped", run(game, """{"action":"stop"}""", ctx).text("state"))
        assertEquals("stopped", run(game, """{"action":"wait"}""", ctx).text("state"))
    }
}
