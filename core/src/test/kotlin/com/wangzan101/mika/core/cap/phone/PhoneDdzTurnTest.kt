package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.run.SteerSignal
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.uistate.RawFrame
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
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
    private fun frame(name: String): RawFrame {
        val image = ImageIO.read(javaClass.getResourceAsStream("/ddz/$name.jpg")!!)
        return RawFrame(image.width, image.height, DdzScreen.ROWS.associateWith { image.getRGB(0, it, image.width, 1, null, 0, image.width) })
    }

    private val blank = RawFrame(2412, 1080, DdzScreen.ROWS.associateWith { IntArray(2412) })

    private class Game(val app: String, val frames: ArrayDeque<RawFrame>, val base: FakeScreen = FakeScreen()) : ScreenPort by base {
        val taps = mutableListOf<Pair<Int, Int>>()
        var last: RawFrame? = null
        override suspend fun peek() = ScreenSnapshot("p1", app, emptyList(), 2412, 1080)
        override suspend fun rawFrame(rows: List<Int>): RawFrame? { last = frames.removeFirstOrNull() ?: last; return last }
        override suspend fun quickTap(x: Int, y: Int): Boolean { taps += x to y; return true }
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

    @Test fun whenDouZeroRefusesTheHistoryThisHandIsHintedAndTheModelTakesOver() = runTest {
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
            assertEquals("先按提示", DdzScreen.read(follow).buttons[1].let { it.x to it.y }, game.taps.first())
        } finally {
            PhoneDdzTurn.advise = saved
        }
    }

    private fun lobbyFrame(name: String): RawFrame {
        val image = ImageIO.read(javaClass.getResourceAsStream("/ddz/$name.jpg")!!)
        return RawFrame(image.width, image.height, (DdzScreen.ROWS + DdzCards.ROWS + DdzCards.LOBBY_ROWS).distinct().associateWith { image.getRGB(0, it, image.width, 1, null, 0, image.width) })
    }

    @Test fun theLobbySendsTheModelToTheFreeStageMode() = runTest {
        // 快速开始进的经典场要金币,不够就弹救济金
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(lobbyFrame("vd_lobby"), fullBlank, full("call"))))
        assertEquals("use_stage_mode", run(game, """{"action":"start","advisor":"http://mac:3426"}""").text("error"))
        assertTrue(game.taps.isEmpty())
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

    @Test fun inTheToolListFrozenAtTaskStartWhenTheModelCanSee() {
        fun names(vision: Boolean) = toolDispatcher(FakeScreen(), visionEnabled = vision).offeredNow(all = true).map { it.name }
        assertTrue("phone_ddz_turn" in names(vision = true))
        assertFalse("phone_ddz_turn" in names(vision = false))
    }
}
