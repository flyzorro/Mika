package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.uistate.RawFrame
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import javax.imageio.ImageIO

/** 牌由电脑认:测试里 [JjDdzTurn.detect] 按顺序回预先摆好的牌,用完了回 null(连着认不了就停下,测试也就结束)。 */
class JjDdzTurnTest {
    @get:Rule val temp = TemporaryFolder()
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

    private open class Game(var app: String, var frame: RawFrame, private val dump: File? = null) : ScreenPort by FakeScreen() {
        val taps = mutableListOf<Pair<Int, Int>>()
        private var shots = 0
        override suspend fun peek() = ScreenSnapshot("p1", app, emptyList(), frame.width, frame.height)
        override suspend fun rawFrame(rows: List<Int>) = frame
        override suspend fun quickShot() = byteArrayOf((++shots).toByte())
        override fun jjDumpDirectory() = dump
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

    @Test fun decidesAgainWhenTheLandlordLeadsWithoutABreakInFollow() = runTest {
        val fixture = javaClass.getResourceAsStream("/ddz-jj/replay-2210.tsv")!!.bufferedReader().use { it.readLines() }
        val dealt = JjCards.parse(fixture[1])!!
        val onlyPass = frame("only_pass")
        val follow = frame("follow")
        val game = Game(JjDdzScreen.PACKAGE, frame("left_turn"))
        val reads = ArrayDeque(fixture.drop(2))
        JjDdzTurn.detect = { _, _ ->
            reads.removeFirstOrNull()?.let { row ->
                val (line, seconds, boxes) = row.split('\t', limit = 3)
                now = seconds.toLong() * 1_000
                when (line) {
                    "48465" -> game.frame = onlyPass
                    "48470" -> game.frame = follow
                }
                dealt + JjCards.parse(boxes)!!
            }
        }
        val histories = mutableListOf<String>()
        PhoneDdzTurn.advise = { _, request ->
            histories += Json.parseToJsonElement(request).jsonObject["history"].toString()
            """{"play":[]}"""
        }

        val result = run(game, start)
        val firstPass = JjDdzScreen.read(onlyPass).buttons.single().x to JjDdzScreen.TAP_Y
        val nextPass = JjDdzScreen.read(follow).buttons.first().x to JjDdzScreen.TAP_Y
        assertEquals(listOf(firstPass, nextPass), game.taps)
        assertEquals(listOf("""[["5","5","4","4","3","3"],[],[],["9","8","7","6","5"],[]]"""), histories)
        assertEquals(null, result.text("douzeroOff"))
    }

    @Test fun keepsTheSameFollowDecisionThroughMyPlayAndOneNoisyFrame() = runTest {
        val follow = frame("follow")
        val game = Game(JjDdzScreen.PACKAGE, frame("left_turn"))
        val play = JjDdzScreen.read(follow).buttons[2].x to JjDdzScreen.TAP_Y
        val select = 906 to JjCards.HAND_TAP_Y
        val leftPass = card("PASS", 650.0, 200.0)
        val right = card("J", 1600.0, 272.0)
        val mine = card("K", 1140.0, 510.0)
        var reads = 0
        var afterPlay = 0
        JjDdzTurn.detect = { _, _ ->
            now += 500
            if (++reads == 2) game.frame = follow
            when {
                reads > 22 -> null
                play in game.taps -> hand(seventeen.filterNot { it == "K" }) + mine + leftPass +
                    if (++afterPlay == 2) right.copy(rank = "Q") else right
                select in game.taps -> hand(seventeen, setOf(4)) + leftPass + right
                else -> hand(seventeen) + leftPass + right
            }
        }
        var decisions = 0
        PhoneDdzTurn.advise = { _, _ ->
            decisions++
            """{"play":["K"]}"""
        }

        val result = run(game, start)
        assertTrue(afterPlay > 2)
        assertEquals(listOf(select, play), game.taps)
        assertEquals(1, decisions)
        assertEquals(null, result.text("douzeroOff"))
    }

    @Test fun tapsPlayAgainOnTheFinalPage() = runTest {
        now = 60_000
        replies(emptyList(), emptyList())
        val game = Game(JjDdzScreen.PACKAGE, frame("final_page"))
        run(game, """{"action":"start","mode":"human","advisor":"http://mac:3426","auto":true,"hands":2}""")
        val again = JjDdzScreen.read(game.frame).buttons.single()
        assertEquals(listOf(again.x to JjDdzScreen.AGAIN_TAP_Y), game.taps)
    }

    @Test fun staysInJjUntilThirtyHandsEnd() = runTest {
        val dealt = hand(seventeen)
        var reads = 0
        JjDdzTurn.detect = { _, _ -> if (reads++ % 7 == 0) dealt else emptyList() }
        val game = Game(JjDdzScreen.PACKAGE, frame("beach_scenery"))
        val result = run(game, """{"action":"start","mode":"human","advisor":"http://mac:3426","auto":true,"hands":30}""")
        assertEquals("result", result.text("state"))
        assertEquals("30", result.text("handsPlayed"))
        assertEquals(210, reads)
    }

    @Test fun sendsTheVotedBottomToDouZero() = runTest {
        val fixture = javaClass.getResourceAsStream("/ddz-jj/replay-bottom-1800.tsv")!!.bufferedReader().use { it.readLines() }
        val dealt = JjCards.parse(fixture[1])!!
        val frames = fixture.drop(2).map { dealt + JjCards.parse(it.substringAfterLast('\t'))!! }
        val game = Game(JjDdzScreen.PACKAGE, frame("left_turn"))
        val follow = frame("follow")
        var reads = 0
        JjDdzTurn.detect = { _, _ ->
            if (reads == frames.lastIndex) game.frame = follow
            val index = reads++
            frames.getOrNull(index) ?: frames.last().takeIf { index < frames.size + 6 }
        }
        var sentBottom: String? = null
        PhoneDdzTurn.advise = { _, request ->
            sentBottom = Json.parseToJsonElement(request).jsonObject["bottom"].toString()
            """{"play":[]}"""
        }
        run(game, start)
        assertEquals("""["2","10","6"]""", sentBottom)
    }

    @Test fun rejectsHandsAboveThirty() = runTest {
        val game = Game(JjDdzScreen.PACKAGE, frame("beach_scenery"))
        val error = runCatching {
            run(game, """{"action":"start","mode":"human","advisor":"http://mac:3426","auto":true,"hands":31}""")
        }.exceptionOrNull()
        assertTrue(error is ArgError)
        assertEquals("hands: must be between 1 and 30", error?.message)
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
        val allSevens = hand(ranks, setOf(0, 1, 2, 3))
        val pair = hand(ranks, setOf(0, 1))
        replies(hand(ranks), hand(ranks), allSevens, allSevens, pair, pair)
        val game = Game(JjDdzScreen.PACKAGE, frame("follow"))
        val x = JjDdzTurn.select(toolDispatcher(game).ctx, JjDdzTurn.Session("http://mac:3426", 1), setOf(0, 1), JjCards.table(hand(ranks), 2408))
        assertEquals(JjDdzScreen.read(game.frame).buttons[2].x, x)
        val xs = game.taps.map { it.first }
        assertEquals(listOf(0, 1, 2, 3).map { 500 + it * 96 + 22 }, xs)
    }

    @Test fun anyTwoOfThreeAcesArePairOfAces() = runTest {
        // 真机 11:13:要出对 A(序号 0、1),游戏抬起的是 1、2:点数对就算选好了,不再来回点
        val ranks = listOf("A", "A", "A", "J", "9")
        replies(hand(ranks), hand(ranks), hand(ranks, setOf(1, 2)), hand(ranks, setOf(1, 2)))
        val game = Game(JjDdzScreen.PACKAGE, frame("follow"))
        val x = JjDdzTurn.select(toolDispatcher(game).ctx, JjDdzTurn.Session("http://mac:3426", 1), setOf(0, 1), JjCards.table(hand(ranks), 2408))
        assertEquals(JjDdzScreen.read(game.frame).buttons[2].x, x)
        assertEquals(listOf(0, 1).map { 500 + it * 96 + 22 }, game.taps.map { it.first })
    }

    @Test fun putsTheGamesHintDownBeforeLiftingTheWantedCard() = runTest {
        // 真机 20:04:轮到我时游戏已经提示抬起一张 6;要出的是 2。先放下 6、读稳,再抬 2
        val ranks = listOf("SJ", "2", "A", "6", "5")
        val hint = hand(ranks, setOf(3))
        replies(hint, hint, hand(ranks), hand(ranks), hand(ranks, setOf(1)), hand(ranks, setOf(1)))
        val game = Game(JjDdzScreen.PACKAGE, frame("follow"))
        val x = JjDdzTurn.select(toolDispatcher(game).ctx, JjDdzTurn.Session("http://mac:3426", 1), setOf(1), JjCards.table(hint, 2408))
        assertEquals(JjDdzScreen.read(game.frame).buttons[2].x, x)
        assertEquals(listOf(3, 1).map { 500 + it * 96 + 22 }, game.taps.map { it.first })
    }

    @Test fun playsABombWhenOnlyOneCardLiftsPerRound() = runTest {
        val game = Game(JjDdzScreen.PACKAGE, frame("left_turn"))
        val result = followWithBomb(game, 1, 1, 1, 1)

        assertEquals(null, result.text("douzeroOff"))
        val play = JjDdzScreen.read(game.frame).buttons[2]
        assertEquals(listOf(0, 1, 2, 16, 9, 10, 11, 12, 10, 11, 12, 11, 12, 12), handTappedIndices(game))
        assertEquals(play.x to JjDdzScreen.TAP_Y, game.taps.last())
    }

    @Test fun progressResetsTheStalledRoundCount() = runTest {
        val game = Game(JjDdzScreen.PACKAGE, frame("left_turn"))
        val result = followWithBomb(game, 0, 1, 0, 1, 1, 1)

        assertEquals(null, result.text("douzeroOff"))
        val play = JjDdzScreen.read(game.frame).buttons[2]
        assertEquals(play.x to JjDdzScreen.TAP_Y, game.taps.last())
    }

    @Test fun rejectsTheBombAfterTwoRoundsWithoutProgress() = runTest {
        val game = Game(JjDdzScreen.PACKAGE, frame("left_turn"))
        val log = captureLog {
            assertEquals(DdzSessionReason.REJECTED_ACTION, followWithBomb(game, 0, 0).text("douzeroOff"))
        }

        assertTrue(log, "select_failed" in log)
        val pass = JjDdzScreen.read(game.frame).buttons[0]
        assertEquals(listOf(0, 1, 2, 16, 9, 10, 11, 12, 9, 10, 11, 12), handTappedIndices(game))
        assertEquals(pass.x to JjDdzScreen.TAP_Y, game.taps.last())
    }

    private fun handTappedIndices(game: Game) = game.taps.filter { it.second == JjCards.HAND_TAP_Y }
        .map { (x, _) -> (x - 522) / 96 }

    private suspend fun followWithBomb(game: Game, vararg liftsPerRound: Int): ToolExecution {
        val ranks = "SJ 2 A A Q Q J J J 10 10 10 10 8 5 5 3".split(' ')
        val right = listOf("K", "K", "K", "Q").mapIndexed { i, rank -> card(rank, 1470.0 + i * 56, 272.0) }
        val leftPass = card("PASS", 650.0, 200.0)
        val follow = frame("follow")
        val lifts = ArrayDeque(liftsPerRound.toList())
        var lifted = setOf(0, 1, 2, 16)
        var reads = 0
        var processedTaps = 0
        JjDdzTurn.detect = { _, _ ->
            if (game.taps.lastOrNull()?.second == JjDdzScreen.TAP_Y || ++reads > 30) null
            else {
                if (reads == 2) game.frame = follow
                val tapped = handTappedIndices(game).drop(processedTaps)
                processedTaps += tapped.size
                val dropped = tapped.filter { it in lifted }
                if (tapped.isNotEmpty()) {
                    lifted = if (dropped.isNotEmpty()) lifted - dropped.toSet() else lifted + tapped.take(lifts.removeFirstOrNull() ?: 0)
                }
                hand(ranks, lifted) + right + leftPass
            }
        }
        PhoneDdzTurn.advise = { _, _ -> """{"play":["10","10","10","10"]}""" }
        return run(game, start)
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
        val ranks = listOf("A", "9", "3")
        replies(hand(ranks), hand(ranks), hand(ranks), hand(ranks, setOf(2)), hand(ranks, setOf(2)))
        val game = Game(JjDdzScreen.PACKAGE, frame("lead"))
        run(game, start)
        val play = JjDdzScreen.read(game.frame).buttons[2]
        assertEquals(listOf((500 + 2 * 96 + 22) to JjCards.HAND_TAP_Y, play.x to JjDdzScreen.TAP_Y), game.taps)
    }

    @Test fun joinedMidHandLeadsTheSmallestPairBeforeASingle() = runTest {
        // 真机 15:13:记牌断了按规则先出,出了单张 3,地主只剩一张。有对子先出最小的对子
        val ranks = listOf("A", "9", "9", "3")
        replies(hand(ranks), hand(ranks), hand(ranks), hand(ranks, setOf(1, 2)), hand(ranks, setOf(1, 2)))
        val game = Game(JjDdzScreen.PACKAGE, frame("lead"))
        run(game, start)
        assertEquals(listOf(1, 2).map { 500 + it * 96 + 22 }, game.taps.take(2).map { it.first })
        assertEquals(JjDdzScreen.read(game.frame).buttons[2].x, game.taps.last().first)
    }

    @Test fun aFallbackLeadIsNotReportedAsAnAutomaticPlay() = runTest {
        val ranks = listOf("A", "9", "9", "3")
        val pair = hand(ranks, setOf(1, 2))
        replies(hand(ranks), hand(ranks), hand(ranks), pair, pair, hand(listOf("A", "3")))
        val game = Game(JjDdzScreen.PACKAGE, frame("lead"))
        val log = captureLog { assertEquals("2", run(game, start).text("handCount")) }
        assertEquals(JjDdzScreen.read(game.frame).buttons[2].x, game.taps.last().first)
        assertTrue(log, "played_without_me" !in log)
    }

    @Test fun doesNotPlayAnUnmatchedSelectionInThe2156Replay() = runTest {
        val ranks = "BJ 2 2 2 2 A A K K Q J 10 9 8 7 6 5 4 3 3".split(' ')
        // 21:56:24–34, 按 /cards 原始框还原抬牌。每组状态连续出现两帧。
        val observed = listOf(emptySet(), setOf(1, 15, 18), setOf(1, 2), setOf(1, 2, 3, 15, 18), setOf(1, 2, 3, 4))
        val reads = ArrayDeque(listOf(hand(ranks.take(17)), hand(ranks)) + observed.flatMap { listOf(hand(ranks, it), hand(ranks, it)) })
        val game = Game(JjDdzScreen.PACKAGE, frame("beach_scenery"))
        val lead = frame("lead")
        JjDdzTurn.detect = { _, _ -> reads.removeFirstOrNull().also { game.frame = lead } }
        PhoneDdzTurn.advise = { _, _ -> """{"play":["3","6","2","2","2","2"]}""" }

        assertEquals(DdzSessionReason.REJECTED_ACTION, run(game, start).text("douzeroOff"))
        assertTrue(game.taps.isNotEmpty())
        assertTrue(game.taps.none { it.second == JjDdzScreen.TAP_Y })
    }

    @Test fun aRuleLeadDoesNotPlayAfterOnlyOneFrameWithTheRightCards() = runTest {
        val ranks = listOf("A", "9", "3")
        val extra = hand(ranks, setOf(0, 2))
        val target = hand(ranks, setOf(2))
        replies(hand(ranks), hand(ranks), hand(ranks), extra, target, extra, target)
        val game = Game(JjDdzScreen.PACKAGE, frame("lead"))

        run(game, start)
        assertTrue(game.taps.isNotEmpty())
        assertTrue(game.taps.none { it.second == JjDdzScreen.TAP_Y })
    }

    @Test fun aRuleLeadConfirmsCardsThatLookAlreadySelected() = runTest {
        val ranks = listOf("A", "9", "3")
        val extra = hand(ranks, setOf(0, 2))
        replies(hand(ranks, setOf(2)), extra, extra)
        val game = Game(JjDdzScreen.PACKAGE, frame("lead"))

        run(game, start)
        assertTrue(game.taps.none { it.second == JjDdzScreen.TAP_Y })
    }

    @Test fun rechecksTheSelectionWhenThePlayButtonAppearsLater() = runTest {
        val ranks = listOf("A", "9", "3")
        val lead = frame("lead")
        val hiddenButton = frame("left_turn")
        var reads = 0
        var buttonWasHidden = false
        val game = object : Game(JjDdzScreen.PACKAGE, lead) {
            override suspend fun rawFrame(rows: List<Int>): RawFrame {
                if (reads == 5 && !buttonWasHidden) {
                    buttonWasHidden = true
                    return hiddenButton
                }
                return lead
            }
        }
        JjDdzTurn.detect = { _, _ ->
            when (++reads) {
                in 1..3 -> hand(ranks)
                4, 5 -> hand(ranks, setOf(2))
                6, 7 -> hand(ranks, setOf(0, 2))
                else -> null
            }
        }

        run(game, start)
        assertTrue(buttonWasHidden)
        assertTrue(game.taps.none { it.second == JjDdzScreen.TAP_Y })
    }

    @Test fun aFallbackPlaysTheLowestOvercallWithoutHint() = runTest {
        val game = fallbackFollow(listOf("BJ", "2", "K", "Q"), listOf("J"), lifted = 3)
        val buttons = JjDdzScreen.read(game.frame).buttons
        assertEquals(listOf((500 + 3 * 96 + 22) to JjCards.HAND_TAP_Y, buttons[2].x to JjDdzScreen.TAP_Y), game.taps)
    }

    @Test fun aFallbackBeatsTheSmallJokerWithTheBigJoker() = runTest {
        val game = fallbackFollow(listOf("BJ", "2", "9", "3"), listOf("SJ"), lifted = 0, landlord = DdzSeat.Me)
        val play = JjDdzScreen.read(game.frame).buttons[2]
        assertEquals(listOf(522 to JjCards.HAND_TAP_Y, play.x to JjDdzScreen.TAP_Y), game.taps)
    }

    @Test fun aFallbackPassesRatherThanSplitABomb() = runTest {
        assertFallbackPass(fallbackFollow(listOf("Q", "Q", "Q", "Q", "3"), listOf("J", "J")))
    }

    @Test fun aFallbackStillPassesOnATeammatesPlay() = runTest {
        assertFallbackPass(fallbackFollow(listOf("BJ", "3"), listOf("SJ"), owner = DdzSeat.Left))
    }

    private fun assertFallbackPass(game: Game) {
        assertEquals(listOf(JjDdzScreen.read(game.frame).buttons[0].x to JjDdzScreen.TAP_Y), game.taps)
    }

    private suspend fun fallbackFollow(
        ranks: List<String>, play: List<String>, lifted: Int? = null, owner: DdzSeat = DdzSeat.Right,
        landlord: DdzSeat = DdzSeat.Right,
    ): Game {
        val game = Game(JjDdzScreen.PACKAGE, frame("follow"))
        val left = if (owner == DdzSeat.Right) listOf(card("PASS", 650.0, 200.0)) else emptyList()
        val boxes = left + play.mapIndexed { i, rank ->
            card(rank, (if (owner == DdzSeat.Right) 1600.0 else 650.0) + 56 * i, 272.0)
        }
        val selected = hand(ranks, setOfNotNull(lifted)) + boxes
        replies(hand(ranks) + boxes, hand(ranks) + boxes, selected, selected)
        val session = JjDdzTurn.Session("", 1).apply { lordSeen = landlord; lordFrames = 12 }
        JjDdzTurn.byRules(toolDispatcher(game).ctx, session, JjDdzScreen.read(game.frame), JjCards.table(hand(ranks) + boxes, game.frame.width))
        return game
    }

    @Test fun aTrackingFailureDumpsTheLastSixSentJpegsAndPrunesOldFiles() = runTest {
        val dir = temp.newFolder("jjdump")
        repeat(301) { index ->
            File(dir, "old-$index.jpg").apply { writeText("old"); setLastModified(1_000L + 1_000 * index) }
        }
        val game = Game(JjDdzScreen.PACKAGE, frame("beach_scenery"), dir)
        val lead = frame("lead")
        var reads = 0
        JjDdzTurn.detect = { _, _ ->
            when (++reads) {
                in 1..6 -> emptyList()
                7 -> emptyList<JjCard>().also { game.frame = lead }
                8 -> hand(listOf("3"))
                else -> null
            }
        }
        assertEquals(DdzSessionReason.JOINED_MID_GAME, run(game, start).text("douzeroOff"))
        val dumped = withContext(Dispatchers.IO) {
            withTimeout(3_000) {
                var files: List<File>
                do {
                    files = dir.listFiles().orEmpty().filter { "_${DdzSessionReason.JOINED_MID_GAME}_" in it.name }.sortedBy { it.name }
                    if (files.size < 6 || files.any { it.length() != 1L }) delay(10)
                } while (files.size < 6 || files.any { it.length() != 1L })
                files
            }
        }
        assertEquals((3..8).toList(), dumped.map { it.readBytes().single().toInt() })
        assertTrue(dumped.all { it.name.matches(Regex("[0-9]{6}\\.[0-9]{3}_joined_mid_game_[0-5]\\.jpg")) })
        assertEquals(300, dir.listFiles()!!.size)
        assertTrue(!File(dir, "old-6.jpg").exists())
        assertTrue(File(dir, "old-7.jpg").exists())
    }

    @Test fun aTrackingFailureKeepsSixRecentFramesAndSamplesTwentySeconds() = runTest {
        val dir = temp.newFolder("window")
        val session = JjDdzTurn.Session("", 1) { dir }
        repeat(151) { i ->
            now = i * 200L
            session.rememberShot(byteArrayOf(i.toByte()))
        }
        DdzTracker(onBroken = session::dump).fail("window")
        // 后台写入串行。等后续的一张写完，断档的文件就全部可读。
        val marker = temp.newFolder("marker")
        JjDdzTurn.Session("", 1) { marker }.apply {
            rememberShot(byteArrayOf(1))
            dump("marker")
        }
        withContext(Dispatchers.IO) {
            withTimeout(3_000) {
                while (marker.listFiles().orEmpty().none { it.length() == 1L }) delay(10)
            }
        }
        val shots = dir.listFiles()!!.map { it.readBytes().single().toUByte().toInt() }.sorted()
        assertTrue(shots.toString(), shots.size <= 26)
        assertEquals((145..150).toList(), shots.takeLast(6))
        val sampled = shots.dropLast(6)
        assertEquals((10..28).toList(), sampled.map { it / 5 })
        assertTrue(sampled.first() in 50..54)
    }

    @Test fun aDouZeroErrorDumpsAtTheDecisionThatBrokeTracking() = runTest {
        val dir = temp.newFolder("jjdump")
        val game = Game(JjDdzScreen.PACKAGE, frame("beach_scenery"), dir)
        val follow = frame("follow")
        assertEquals(DdzSeat.Right, JjDdzScreen.read(follow).redLord)
        val cards = hand(seventeen.map { if (it == "4") "3" else it })
        val table = cards + listOf(card("PASS", 650.0, 200.0), card("3", 1600.0, 272.0), card("3", 1656.0, 272.0))
        var reads = 0
        JjDdzTurn.detect = { _, _ ->
            now += 1_000
            when (++reads) {
                1 -> cards.also { game.frame = follow }
                in 2..12 -> table
                else -> null
            }
        }
        var lastAtAdvice = 0
        PhoneDdzTurn.advise = { _, request ->
            assertEquals("""[["3","?"],[]]""", Json.parseToJsonElement(request).jsonObject["history"].toString())
            lastAtAdvice = reads
            """{"error":"ambiguous_card"}"""
        }
        assertEquals("douzero_ambiguous_card", run(game, start).text("douzeroOff"))
        val dumped = withContext(Dispatchers.IO) {
            withTimeout(3_000) {
                while (dir.listFiles().orEmpty().count { it.length() == 1L } < lastAtAdvice) delay(10)
                dir.listFiles()!!.toList()
            }
        }
        assertEquals((1..lastAtAdvice).toList(), dumped.map { it.readBytes().single().toInt() }.sorted())
        assertTrue(dumped.all { "_douzero_ambiguous_card_" in it.name })
    }

    @Test fun aFallbackIsNotReportedAsAnAutomaticPlay() = runTest {
        val game = Game(JjDdzScreen.PACKAGE, frame("left_turn"))
        val follow = frame("follow")
        var reads = 0
        JjDdzTurn.detect = { _, _ ->
            when (++reads) {
                in 1..4 -> hand(seventeen)
                5 -> hand(seventeen).also { game.frame = follow }
                6 -> hand(seventeen).also { now = 1_000 }
                7 -> (hand(seventeen) + card("PASS", 650.0, 200.0) + card("Q", 1600.0, 272.0)).also { now = 6_000 }
                8, 9 -> hand(seventeen)
                10, 11 -> hand(seventeen, setOf(4))
                12 -> hand(seventeen.filter { it != "K" })
                else -> null
            }
        }
        val log = captureLog { assertEquals("16", run(game, start).text("handCount")) }
        assertEquals(listOf((500 + 4 * 96 + 22) to JjCards.HAND_TAP_Y, JjDdzScreen.read(follow).buttons[2].x to JjDdzScreen.TAP_Y), game.taps)
        assertTrue(log, "played_without_me" !in log)
    }

    @Test fun waitsAgainAfterTheGamePassesForMe() = runTest {
        val dealt = hand("SJ 2 A A K K Q J J J 8 6 5 5 4 3 3".split(' '))
        val right = listOf("2", "2", "2", "9").mapIndexed { i, rank -> card(rank, 1470.0 + i * 56, 272.0) }
        val bomb = List(4) { card("10", 650.0 + it * 56, 272.0) }
        val fullHouse = listOf("6", "6", "6", "3", "3").mapIndexed { i, rank -> card(rank, 650.0 + i * 56, 272.0) }
        val myPass = card("PASS", 454.0, 656.0)
        val rightPass = card("PASS", 1595.0, 217.0)

        assertSettleDeadline(10_000L,
            Triple(1_000L, "left_turn", dealt + right),
            Triple(1_500L, "left_turn", dealt + right),
            Triple(2_000L, "follow", dealt + right + bomb),
            Triple(2_500L, "right_turn", dealt + bomb + myPass),
            Triple(3_000L, "right_turn", dealt + bomb + myPass),
            Triple(5_000L, "left_turn", dealt + rightPass),
            Triple(5_200L, "left_turn", dealt + rightPass),
            Triple(5_800L, "beach_scenery", dealt + fullHouse.dropLast(1)),
            Triple(6_000L, "follow", dealt + fullHouse),
            Triple(8_000L, "follow", dealt),
            Triple(9_999L, "follow", dealt),
            Triple(10_000L, "follow", dealt),
        )
    }

    @Test fun keepsWaitingThroughAFrameWithoutButtonsOrOpponentClock() = runTest {
        val dealt = hand(seventeen)
        val flicker = JjDdzScreen.read(frame("beach_scenery"))
        assertEquals(DdzPhase.Other, flicker.phase)
        assertEquals(null, flicker.turn)

        assertSettleDeadline(5_000L,
            Triple(1_000L, "follow", dealt),
            Triple(3_000L, "beach_scenery", dealt),
            Triple(4_999L, "follow", dealt),
            Triple(5_000L, "follow", dealt),
        )
    }

    private suspend fun assertSettleDeadline(deadline: Long, vararg reads: Triple<Long, String, List<JjCard>>) {
        val frames = reads.map { (_, name, _) -> name }.distinct().associateWith(::frame)
        val game = Game(JjDdzScreen.PACKAGE, frames.getValue(reads.first().second))
        val decisions = mutableListOf<Long>()
        JjDdzTurn.think = { decisions += now }
        var index = 0
        JjDdzTurn.detect = { _, _ ->
            reads.getOrNull(index++)?.let { (at, _, cards) ->
                now = at
                reads.getOrNull(index)?.let { (_, next, _) -> game.frame = frames.getValue(next) }
                cards
            }
        }

        val result = run(game, start)
        assertEquals(listOf(deadline), decisions)
        assertEquals("history_not_ready", result.text("douzeroOff"))
        assertFallbackPass(game)
    }

    private suspend fun captureLog(block: suspend () -> Unit): String {
        val log = ByteArrayOutputStream()
        val original = System.out
        PrintStream(log).use { output ->
            System.setOut(output)
            try {
                block()
            } finally {
                System.setOut(original)
            }
        }
        return log.toString()
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
        assertEquals(null, JjDdzTurn.opposingPlay(s, rightPlayed))
        s.lordSeen = DdzSeat.Right; s.lordFrames = 10
        assertEquals(listOf("SJ"), JjDdzTurn.opposingPlay(s, rightPlayed))
        // 左家是队友:他的牌不压
        assertEquals(null, JjDdzTurn.opposingPlay(s, leftPlayed))
        s.lordSeen = DdzSeat.Me; s.lordFrames = 20
        assertEquals(listOf("9"), JjDdzTurn.opposingPlay(s, leftPlayed))
    }

    private fun card(rank: String, x: Double, y: Double) = JjCard(rank, x, y, x + 40, y + 53, 0.99)

    @Test fun cardsLeavingTheHandWithoutTheToolAreReported() {
        val s = JjDdzTurn.Session("http://mac:3426", 1)
        s.lastRanks = listOf("A", "A", "A", "Q", "J", "J", "J", "9", "3")
        // 游戏替我出了 A A A Q
        assertEquals(listOf("A", "A", "A", "Q"), JjDdzTurn.playedWithoutMe(s, listOf("J", "J", "J", "9", "3")))
        // 工具自己出的那手不算
        s.expectedHand = listOf("J", "J", "J", "9", "3")
        assertEquals(null, JjDdzTurn.playedWithoutMe(s, listOf("J", "J", "J", "9", "3")))
        // 认错成别的牌(不是少了几张)不算
        s.expectedHand = null
        assertEquals(null, JjDdzTurn.playedWithoutMe(s, listOf("K", "J", "J", "9")))
    }

    @Test fun theResultOverlayDoesNotLookLikeAnAutomaticPlay() {
        val s = JjDdzTurn.Session("", 1)
        // 16:50:12 结算浮层盖住手牌，只有小王还读得到。
        s.lastRanks = listOf("SJ", "2", "A", "A", "Q", "10", "10", "9", "9", "8", "8", "7", "6", "5", "5", "4", "4")
        assertEquals(null, JjDdzTurn.playedWithoutMe(s, listOf("SJ")))
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
