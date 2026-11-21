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

    private class Game(var app: String, val frame: RawFrame) : ScreenPort by FakeScreen() {
        val taps = mutableListOf<Pair<Int, Int>>()
        override suspend fun peek() = ScreenSnapshot("p1", app, emptyList(), frame.width, frame.height)
        override suspend fun rawFrame(rows: List<Int>) = frame
        override suspend fun quickShot() = ByteArray(1)
        override suspend fun quickTap(x: Int, y: Int): Boolean { taps += x to y; return true }
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
