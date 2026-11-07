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
        observe(DdzSeat.Me, { if (it == DdzSide.Left) pairs else DdzSeen.Empty }, { listOf("J", "7", "4") })
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

    @Test fun startOnlyFromTheSinglePlayerLobby() = runTest {
        val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(lobbyFrame("vd_lobby"), fullBlank, full("call"))))
        val result = run(game, """{"action":"start","advisor":"http://mac:3426"}""")
        assertEquals("true", result.text("ok"))
        assertEquals("只点了快速开始", listOf(2140 to 995), game.taps)
    }

    @Test fun theLobbyLingeringRightAfterStartDoesNotCancelSinglePlayer() = runTest {
        val asked = mutableListOf<String>()
        val saved = PhoneDdzTurn.advise
        PhoneDdzTurn.advise = { _, body -> asked += body; """{"play":[]}""" }
        try {
            val lobby = lobbyFrame("vd_lobby")
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
        assertEquals("只点了报名", listOf(1204 to 920), game.taps)
    }

    @Test fun theStagePageIsTellApartFromTheLobbyAndTheTable() {
        assertTrue(DdzCards.singleStage(lobbyFrame("vd_stage")))
        assertFalse(DdzCards.singleStage(lobbyFrame("vd_lobby")))
        assertFalse(DdzCards.singleStage(lobbyFrame("vd_hand_follow")))
        assertFalse(DdzCards.singleLobby(lobbyFrame("vd_stage")))
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
