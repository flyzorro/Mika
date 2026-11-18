package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.run.SteerSignal
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.userscreen.HintHud
import com.wangzan101.mika.core.userscreen.MainScreenPort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 看屏用法：截屏、前台、胶囊、HTTP、时钟全是替身。 */
class PhoneMahjongHintWatchTest {
    private val main = FakeMainScreen()
    private val hud = FakeHud()
    private val screen = FakeScreen()
    private val posts = mutableListOf<Pair<String, String>>()
    private var reply: (Int) -> String? = { hint(state = "wait") }

    @org.junit.Before fun setUp() {
        main.reset()
        hud.reset()
        posts.clear()
        reply = { hint(state = "wait") }
        PhoneMahjongHint.postHint = { url, body ->
            posts += (url to body)
            reply(posts.size)
        }
    }

    @org.junit.After fun restore() {
        PhoneMahjongHint.postHint = REAL_POST_HINT
        PhoneMahjongHint.clock = System::currentTimeMillis
    }

    private suspend fun kotlinx.coroutines.test.TestScope.watch(
        json: String = """{"action":"watch","advisor":"http://mac:3428"}""",
    ): ToolExecution {
        PhoneMahjongHint.clock = { testScheduler.currentTime }
        return PhoneMahjongHint.execute(
            Args(PhoneMahjongHint.def, Json.parseToJsonElement(json).jsonObject),
            toolDispatcher(screen, mainScreen = main, hintHud = hud).ctx,
        )
    }

    private fun ToolExecution.text(key: String) = content[key]?.jsonPrimitive?.content

    @Test fun onlyCapturesWhenJjIsInForeground() = runTest {
        main.front = "com.tencent.mm"
        val r = watch()
        assertEquals("app_left", r.text("stopped"))
        assertEquals("0", r.text("hinted"))
        assertEquals("0", r.text("unsure"))
        assertEquals("no screenshot while another app is in front", 0, main.captures)
        assertTrue("no frame sent: $posts", posts.isEmpty())
        assertEquals("the watch released the capsule", 1, main.ends)
    }

    @Test fun sendsFramesOnlyWhileJjIsInFront() = runTest {
        reply = { hint(state = "wait") }
        // 第二帧之后用户切走了：不再截图、也不再发。
        PhoneMahjongHint.postHint = { url, body ->
            posts += (url to body)
            if (posts.size == 2) main.front = "com.tencent.mm"
            hint(state = "wait")
        }
        val r = watch()
        assertEquals("app_left", r.text("stopped"))
        assertEquals("two frames, then the app left", 2, main.captures)
        assertEquals(2, posts.size)
        assertTrue("the frame went to /hint", posts[0].second.contains("\"image\""))
        assertTrue("nothing flashed for wait", hud.flashes.isEmpty())
        assertEquals("0", r.text("hinted"))
        assertEquals("0", r.text("unsure"))
    }

    @Test fun hintsOnlyAfterTwoIdenticalTurnsAndOnlyOnce() = runTest {
        // 同一手连着认出 5 帧，然后服务连不上。
        reply = { i -> if (i <= 5) hint(state = "turn") else null }
        val r = watch()
        assertEquals("service_down", r.text("stopped"))
        assertEquals("one hint for the whole hand", 1, hud.flashes.size)
        assertEquals("1", r.text("hinted"))
        assertEquals("0", r.text("unsure"))
        assertEquals("the watch never touches the screen", 0, screen.calls.size)
        assertEquals("the watch never reads the virtual screen", 0, screen.snapshots)
    }

    @Test fun hintsAgainWhenTheHandChanges() = runTest {
        val a = """["1m","2m","3m","4m","5m","6m","7m","8m","9m","1p","2p","3p","4p","5p"]"""
        val b = """["1m","2m","3m","4m","5m","6m","7m","8m","9m","1p","2p","3p","4p","6p"]"""
        reply = { i ->
            when (i) {
                1, 2 -> hint(state = "turn", hand = a)
                3, 4 -> hint(state = "turn", hand = b)
                else -> null
            }
        }
        val r = watch()
        assertEquals("service_down", r.text("stopped"))
        assertEquals("one hint per hand", 2, hud.flashes.size)
        assertEquals("2", r.text("hinted"))
    }

    @Test fun aWaitFrameBetweenTwoTurnsBreaksThePair() = runTest {
        reply = { i ->
            when (i) {
                1 -> hint(state = "turn")
                2 -> hint(state = "wait")
                3, 4 -> hint(state = "turn")
                else -> null
            }
        }
        val r = watch()
        assertEquals("service_down", r.text("stopped"))
        assertEquals("the wait frame broke the pair", 1, hud.flashes.size)
    }

    @Test fun flashesTheDiscardAdvice() = runTest {
        reply = { i -> if (i <= 2) hint(state = "turn", discard = "5m", target = "qingyise", value = 1.4) else null }
        watch()
        assertEquals(listOf("打 5万 · 冲清一色 · 期望 1.4倍"), hud.flashes)
    }

    @Test fun flashesTheGamesOwnJianWhenItDiffers() = runTest {
        reply = { i -> if (i <= 2) hint(state = "turn", discard = "5m", jian = "9s") else null }
        watch()
        assertEquals(listOf("打 5万 · 冲清一色 · 期望 1.4倍（游戏荐 9条）"), hud.flashes)
    }

    @Test fun doesNotRepeatTheGameJianWhenItIsOurDiscard() = runTest {
        reply = { i -> if (i <= 2) hint(state = "turn", discard = "9s", jian = "9s") else null }
        watch()
        assertEquals(listOf("打 9条 · 冲清一色 · 期望 1.4倍"), hud.flashes)
    }

    @Test fun flashesQueSelectOnce() = runTest {
        reply = { i -> if (i <= 4) hint(state = "que_select", que = "s") else null }
        watch()
        assertEquals(listOf("定缺 条"), hud.flashes)
    }

    @Test fun flashesWinOnce() = runTest {
        reply = { i -> if (i <= 4) hint(state = "win") else null }
        val r = watch()
        assertEquals(listOf("可以胡"), hud.flashes)
        assertEquals("1", r.text("hinted"))
    }

    @Test fun skipsFramesThatCannotBeShared() = runTest {
        main.results.addAll(listOf(MainScreenPort.Capture.Skip, MainScreenPort.Capture.Skip))
        main.results.add(MainScreenPort.Capture.Unavailable)
        val r = watch()
        assertEquals("screen_unavailable", r.text("stopped"))
        assertEquals("nothing was sent for the skipped frames", 0, posts.size)
        assertEquals("two skipped captures, then the service went away", 3, main.captures)
    }

    @Test fun stopsWhenAccessibilityGoesAway() = runTest {
        main.shot = MainScreenPort.Capture.Unavailable
        val r = watch()
        assertEquals("screen_unavailable", r.text("stopped"))
        assertTrue(posts.isEmpty())
        assertEquals("the watch released the capsule", 1, main.ends)
    }

    @Test fun refusesASecondWatch() = runTest {
        main.busy = true
        val r = watch()
        assertEquals("busy", r.text("stopped"))
        assertEquals("0", r.text("hinted"))
        assertEquals("0", r.text("unsure"))
        assertEquals("no screenshot", 0, main.captures)
        assertEquals("must not release the other watch", 0, main.ends)
    }

    @Test fun stopsWhenTheUserSendsANewMessage() = runTest {
        val pending = MutableStateFlow(false)
        PhoneMahjongHint.postHint = { url, body ->
            posts += (url to body)
            if (posts.size == 2) pending.value = true
            hint(state = "wait")
        }
        val r = withContext(SteerSignal(pending)) { watch() }
        assertEquals("new_message", r.text("stopped"))
        assertEquals("stopped at the next wait", 2, posts.size)
        assertEquals(1, main.ends)
    }

    @Test fun stopsAfterSixtyMinutes() = runTest {
        reply = { hint(state = "wait") }
        val r = watch()
        assertEquals("timeout", r.text("stopped"))
        assertEquals("hinted", "0", r.text("hinted"))
        assertTrue("kept watching: ${posts.size}", posts.size >= 3600)
    }

    @Test fun countsUnsureWithoutHinting() = runTest {
        reply = { i -> if (i <= 3) hint(state = "unsure") else null }
        val r = watch()
        assertEquals("3", r.text("unsure"))
        assertEquals("0", r.text("hinted"))
        assertTrue("nothing flashed for unsure", hud.flashes.isEmpty())
    }

    @Test fun unsupportedScreenIsJustAwait() = runTest {
        reply = { i -> if (i <= 3) """{"error":"unsupported_screen"}""" else null }
        val r = watch()
        assertEquals("service_down", r.text("stopped"))
        assertEquals("0", r.text("hinted"))
        assertEquals("0", r.text("unsure"))
        assertTrue("nothing flashed", hud.flashes.isEmpty())
    }

    @Test fun sendsTheJpegAsBase64ToHint() = runTest {
        main.shot = MainScreenPort.Capture.Ok(byteArrayOf(1, 2, 3))
        reply = { i -> if (i <= 1) hint(state = "wait") else null }
        watch()
        assertEquals("http://mac:3428", posts.first().first)
        assertEquals("""{"image":"AQID"}""", posts.first().second)
    }

    @Test fun clipsTheCapsuleTextToThirtyCharacters() = runTest {
        reply = { i -> if (i <= 2) hint(state = "turn", discard = "5m", jian = "123456789s") else null }
        watch()
        val text = hud.flashes.single()
        assertEquals(30, text.length)
        assertTrue("clipped: $text", text.endsWith("…"))
    }

    @Test fun stoppingTheTaskFlashesAndRestoresTheCapsule() = runTest {
        val atPost = CompletableDeferred<Unit>()
        PhoneMahjongHint.postHint = { _, _ ->
            atPost.complete(Unit)
            awaitCancellation()
        }
        val work = launch { watch() }
        atPost.await()
        work.cancelAndJoin()
        assertEquals(listOf("麻将提示已停止"), hud.flashes)
        assertEquals("released after cancellation", 1, main.ends)
    }

    @Test fun manualUsageStillNeedsHandAndQue() = runTest {
        val d = toolDispatcher(screen, visionEnabled = true)
        val r = d.execute(Part.ToolCall("c1", "phone_mahjong_hint", """{"advisor":"http://mac:3428"}"""))
        assertEquals("invalid_arguments", r.content["error"]?.jsonPrimitive?.content)
        assertTrue("the message names the missing hand", r.content["message"]!!.jsonPrimitive.content.contains("hand"))
    }

    /** 看屏不经过模型看图:主模型不能看图(如 DeepSeek flash)时,只要能截主屏、能闪胶囊,工具也要提供。 */
    @Test fun offeredToATextOnlyModelWhenTheScreenCanBeWatched() {
        val ctx = toolDispatcher(screen, visionEnabled = false, mainScreen = main, hintHud = hud).ctx
        assertTrue(PhoneMahjongHint.available(ctx))
    }

    @Test fun notOfferedToATextOnlyModelWithoutWatchPorts() {
        val ctx = toolDispatcher(screen, visionEnabled = false).ctx
        assertFalse(PhoneMahjongHint.available(ctx))
    }

    /** 用户只说「开始麻将提示」不给地址时,用编译时预设的麻将服务地址。 */
    @Test fun usesThePresetServiceWhenNoAdvisorIsGiven() = runTest {
        main.busy = false
        main.front = "com.tencent.mm"
        PhoneMahjongHint.clock = { testScheduler.currentTime }
        val r = PhoneMahjongHint.execute(
            Args(PhoneMahjongHint.def, Json.parseToJsonElement("""{"action":"watch"}""").jsonObject),
            toolDispatcher(screen, mainScreen = main, hintHud = hud, mahjongAdvisor = "http://preset:3428").ctx,
        )
        assertEquals("app_left", r.text("stopped"))
    }

    @Test fun asksForTheServiceWhenNothingIsPreset() = runTest {
        val d = toolDispatcher(screen, visionEnabled = true, mainScreen = main, hintHud = hud)
        val r = d.execute(Part.ToolCall("c1", "phone_mahjong_hint", """{"action":"watch"}"""))
        assertEquals("invalid_arguments", r.content["error"]?.jsonPrimitive?.content)
        assertTrue(r.content["message"]!!.jsonPrimitive.content.contains("advisor"))
    }

    @Test fun rejectsAnUnknownAction() = runTest {
        val d = toolDispatcher(screen, visionEnabled = true, mainScreen = main, hintHud = hud)
        val r = d.execute(Part.ToolCall("c1", "phone_mahjong_hint", """{"action":"dance","advisor":"http://mac:3428"}"""))
        assertEquals("invalid_arguments", r.content["error"]?.jsonPrimitive?.content)
    }

    @Test fun watchSummaryNamesWhatItDid() = runTest {
        main.busy = true
        val r = watch()
        assertEquals(setOf("ok", "hinted", "unsure", "stopped"), r.content.keys)
        assertEquals("true", r.text("ok"))
    }

    private companion object {
        val REAL_POST_HINT = PhoneMahjongHint.postHint

        fun hint(
            state: String,
            hand: String = """["1m","2m","3m","4m","5m","6m","7m","8m","9m","1p","2p","3p","4p","5p"]""",
            melds: String = "[]",
            jian: String? = null,
            discard: String? = null,
            target: String = "qingyise",
            value: Double = 1.4,
            que: String? = null,
        ): String = buildString {
            append("""{"state":"$state"""")
            when (state) {
                "turn" -> append(""","hand":$hand,"melds":$melds,"que":"m","left":31,"draws":8""")
                "win" -> append(""","hand":$hand,"melds":$melds,"que":"m","left":31""")
                "que_select" -> append(""","hand":$hand""")
            }
            if (jian != null) append(""","jian":"$jian"""")
            when (state) {
                "turn" -> append(
                    ""","advice":{"discard":"${discard ?: "5m"}","target":"$target","value":$value,"shanten":0,"ukeire":4,"waits":[],"queLeft":0,"options":[]}""",
                )
                "que_select" -> append(""","advice":{"que":"${que ?: "s"}","options":[]}""")
            }
            append("}")
        }
    }
}

private class FakeMainScreen : MainScreenPort {
    var front: String? = PhoneMahjongHint.JJ_PACKAGE
    var shot: MainScreenPort.Capture = MainScreenPort.Capture.Ok(byteArrayOf(1, 2, 3))
    val results = ArrayDeque<MainScreenPort.Capture>()
    var busy = false
    var captures = 0
    var begins = 0
    var ends = 0

    fun reset() {
        front = PhoneMahjongHint.JJ_PACKAGE
        shot = MainScreenPort.Capture.Ok(byteArrayOf(1, 2, 3))
        results.clear()
        busy = false
        captures = 0
        begins = 0
        ends = 0
    }

    override suspend fun beginWatch(): Boolean { begins++; return !busy }
    override suspend fun endWatch() { ends++ }
    override suspend fun foreground(): String? = front
    override suspend fun capture(packageName: String): MainScreenPort.Capture {
        assertEquals(PhoneMahjongHint.JJ_PACKAGE, packageName)
        captures++
        return results.removeFirstOrNull() ?: shot
    }
}

private class FakeHud : HintHud {
    val flashes = mutableListOf<String>()
    override var showing = true

    fun reset() { flashes.clear(); showing = true }

    override suspend fun flash(text: String, ms: Long): Boolean {
        flashes += text
        return showing
    }
}
