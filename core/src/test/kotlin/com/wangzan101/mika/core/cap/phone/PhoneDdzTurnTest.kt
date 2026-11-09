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
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
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
        return RawFrame(image.width, image.height, DdzScreen.ROWS.associateWith { image.getRGB(0, it, image.width, 1, null, 0, image.width) })
    }

    private val blank = RawFrame(2412, 1080, DdzScreen.ROWS.associateWith { IntArray(2412) })

    private open class Game(val app: String, val frames: ArrayDeque<RawFrame>, val base: FakeScreen = FakeScreen()) : ScreenPort by base {
        val taps = mutableListOf<Pair<Int, Int>>()
        var last: RawFrame? = null
        open override suspend fun peek() = ScreenSnapshot("p1", app, emptyList(), 2412, 1080)
        override suspend fun rawFrame(rows: List<Int>): RawFrame? { last = frames.removeFirstOrNull() ?: last; return last }
        open override suspend fun quickTap(x: Int, y: Int): Boolean { taps += x to y; return true }
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

    @Test fun pressesPlayAgainWhenTheFirstPressWasIgnored() = runTest {
        val lead = frame("lead")
        val hand = (List(14) { "4" } + "3").joinToString(",", "[", "]") { "\"$it\"" }
        val lifted = frame("lead_lifted") // 最后一张已抬起,不用再选
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(lifted, lifted, lifted, blank, lead)))
        val result = run(game, """{"action":"play","hand":$hand,"play":["3"]}""")
        assertEquals("lead", result.text("phase"))
        assertEquals("两次出牌", 2, game.taps.count { it.second != DdzScreen.CARD_Y })
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
        val image = ImageIO.read(javaClass.getResourceAsStream("/ddz/$name.jpg")!!)
        return RawFrame(image.width, image.height, (DdzScreen.ROWS + DdzCards.ROWS + DdzCards.LOBBY_ROWS).distinct().associateWith { image.getRGB(0, it, image.width, 1, null, 0, image.width) })
    }

    private val fullBlank = RawFrame(2412, 1080, (DdzScreen.ROWS + DdzCards.ROWS + DdzCards.LOBBY_ROWS).distinct().associateWith { IntArray(2412) })

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
        val matching = lobbyFrame("vd_lobby")
        val call = full("call")
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(List(5) { matching } + listOf(call, call, lobbyFrame("vd_lobby"))))
        val dispatcher = toolDispatcher(game)
        PhoneDdzTurn.adviseBid = { _, _ -> 0 }
        val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","auto":true,"advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        assertNull(result.toString(), result.text("error"))
        assertEquals("bid at the deal after waiting for the table", 1, game.taps.size)
    }

    @Test fun humanAutoStartGivesUpWhenNoTableAppears() = runTest {
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(lobbyFrame("vd_lobby"))))
        val dispatcher = toolDispatcher(game)
        val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","auto":true,"advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        assertEquals("not_human_game", result.text("error"))
        assertTrue(game.taps.isEmpty())
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
        assertEquals(2, game.taps.size)
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

    @Test fun humanAutoStartSitsOutAGameAlreadyOnAutoplay() = runTest {
        // Live 2026-11-09: quick start seated us in a game already on autoplay, far into play.
        val asked = mutableListOf<String>()
        val saved = PhoneDdzTurn.advise
        PhoneDdzTurn.advise = { _, body -> asked += body; """{"play":[]}""" }
        try {
            val autoplay = full("human_autoplay_left11_right5")
            val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(autoplay, autoplay, autoplay, full("human_follow_left8_right2"))))
            val dispatcher = toolDispatcher(game)
            val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","auto":true,"advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
            assertEquals(result.toString(), "paused", result.text("state"))
            assertEquals("joined_mid_game", result.text("reason"))
            assertNull("the history cannot be rebuilt, so no resume hint", result.content["recovery"])
            assertTrue("the game's own autoplay is left on", game.taps.isEmpty())
            assertTrue("DouZero is never asked with a partial history", asked.isEmpty())
        } finally {
            PhoneDdzTurn.advise = saved
        }
    }

    @Test fun humanWaitLeavesAutoplayOnUntilTheJoinIsVerified() = runTest {
        val autoplay = full("human_autoplay_left11_right5")
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(List(6) { autoplay }))
        val dispatcher = toolDispatcher(game)
        PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
        assertEquals("joined_mid_game", result.text("reason"))
        assertTrue("取消托管 is not tapped on a game joined mid-way", game.taps.isEmpty())
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
            val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(full("result")) + List(6) { follow }))
            val dispatcher = toolDispatcher(game)
            val start = """{"action":"start","mode":"human","advisor":"http://mac:3426"}"""
            PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement(start).jsonObject), dispatcher.ctx)
            PhoneDdzTurn.useTracker(dispatcher.ctx, leftLedPairs())
            // The model tapped 再来一局 itself; the next game is already at a play turn.
            val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement(
                """{"action":"start","mode":"human","auto":true,"advisor":"http://mac:3426"}"""
            ).jsonObject), dispatcher.ctx)
            assertEquals("joined_mid_game", result.text("reason"))
            assertTrue("the finished game's history must not reach DouZero", asked.isEmpty())
        } finally {
            PhoneDdzTurn.advise = saved
        }
    }

    @Test fun humanWaitSitsOutAGameJoinedAtAPlayTurn() = runTest {
        val asked = mutableListOf<String>()
        val saved = PhoneDdzTurn.advise
        PhoneDdzTurn.advise = { _, body -> asked += body; """{"play":[]}""" }
        try {
            val follow = full("human_follow_left8_right2")
            val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(List(6) { follow }))
            val dispatcher = toolDispatcher(game)
            PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
            val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
            assertEquals("paused", result.text("state"))
            assertEquals("joined_mid_game", result.text("reason"))
            assertTrue(asked.isEmpty())
            assertTrue(game.taps.isEmpty())
        } finally {
            PhoneDdzTurn.advise = saved
        }
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
            assertEquals(2, game.taps.size)
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

    @Test fun humanHistoryMismatchPausesWithoutTapping() = runTest {
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(full("hand_t1"))))
        val dispatcher = toolDispatcher(game)
        PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        PhoneDdzTurn.useTracker(dispatcher.ctx, DdzTracker(DdzSettlingPolicy.HUMAN).apply { fail("douzero_history_mismatch") })
        val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
        assertEquals("paused", result.text("state"))
        assertEquals("history_mismatch", result.text("reason"))
        assertTrue(game.taps.isEmpty())
    }

    @Test fun humanPausePointsTheModelToSnapshotResume() = runTest {
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(full("hand_t1"))))
        val dispatcher = toolDispatcher(game)
        PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        PhoneDdzTurn.useTracker(dispatcher.ctx, DdzTracker(DdzSettlingPolicy.HUMAN).apply { fail("douzero_history_mismatch") })

        val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
        val recovery = result.content["recovery"]!!.jsonObject

        assertEquals("resume", recovery["action"]!!.jsonPrimitive.content)
        assertEquals(setOf("hand", "bottom", "history", "phase", "seat"), recovery["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
    }

    @Test fun humanHandDriftPausesBeforeAskingTheAdvisor() = runTest {
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(full("hand_t1"))))
        val dispatcher = toolDispatcher(game)
        PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
        PhoneDdzTurn.useTracker(dispatcher.ctx, leftLedPairs(), expectedHand = listOf("3"))
        val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
        assertEquals("paused", result.text("state"))
        assertEquals("hand_drift", result.text("reason"))
        assertTrue(game.taps.isEmpty())
    }

    @Test fun humanAdvisorFailurePausesWithServiceUnavailable() = runTest {
        val saved = PhoneDdzTurn.advise
        PhoneDdzTurn.advise = { _, _ -> null }
        try {
            val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(full("hand_t1"))))
            val dispatcher = toolDispatcher(game)
            PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
            PhoneDdzTurn.useTracker(dispatcher.ctx, leftLedPairs())
            val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
            assertEquals("paused", result.text("state"))
            assertEquals("service_unavailable", result.text("reason"))
            assertTrue(game.taps.isEmpty())
        } finally {
            PhoneDdzTurn.advise = saved
        }
    }

    @Test fun humanHandCountMismatchPausesForSnapshotResume() = runTest {
        var calls = 0
        val saved = PhoneDdzTurn.advise
        PhoneDdzTurn.advise = { _, _ -> calls++; """{"error":"hand_count_mismatch"}""" }
        try {
            val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(full("hand_t1"))))
            val dispatcher = toolDispatcher(game)
            PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
            PhoneDdzTurn.useTracker(dispatcher.ctx, leftLedPairs())
            val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
            assertEquals("paused", result.text("state"))
            assertEquals("history_mismatch", result.text("reason"))
            assertEquals("resume", result.content["recovery"]!!.jsonObject["action"]!!.jsonPrimitive.content)
            assertEquals("a deterministic mismatch is not retried", 1, calls)
            assertTrue(game.taps.isEmpty())
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

    @Test fun humanLeadDoesNotGuessWhenTheAdvisorReturnsAnEmptyPlay() = runTest {
        val saved = PhoneDdzTurn.advise
        PhoneDdzTurn.advise = { _, _ -> """{"play":[]}""" }
        try {
            val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(List(12) { full("lead") }))
            val dispatcher = toolDispatcher(game)
            PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"start","mode":"human","advisor":"http://mac:3426"}""").jsonObject), dispatcher.ctx)
            PhoneDdzTurn.useTracker(dispatcher.ctx, DdzTracker(DdzSettlingPolicy.HUMAN).apply {
                observe(DdzSeat.Me, { DdzSeen.Empty }, { listOf("3", "4", "5") }, leadingWith20 = true, leading = true)
            })
            val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject), dispatcher.ctx)
            assertEquals("paused", result.text("state"))
            assertEquals("invalid_advisor_play", result.text("reason"))
            assertTrue(game.taps.isEmpty())
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
