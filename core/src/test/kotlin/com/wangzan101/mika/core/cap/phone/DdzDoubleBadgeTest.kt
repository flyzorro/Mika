package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.uistate.RawFrame
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import javax.imageio.ImageIO

/**
 * 两家头像下沿的红底「加倍」小标签。素材是 2026-11-17 真机 2412×1080 整帧,
 * 只留了 [DdzCards.DOUBLE_ROWS] 和 [DdzScreen.blackBar] 读的那一行。
 */
class DdzDoubleBadgeTest {
    @org.junit.Before fun noBidService() {
        PhoneDdzTurn.adviseBid = { _, _ -> null }
        PhoneDdzTurn.adviseDouble = { _, _, _, _ -> null }
        PhoneDdzTurn.clock = System::currentTimeMillis
    }

    private fun image(name: String): BufferedImage =
        ImageIO.read(javaClass.getResourceAsStream("/ddz/$name.png") ?: javaClass.getResourceAsStream("/ddz/$name.jpg")!!)

    private open class Game(val app: String, val frames: ArrayDeque<RawFrame>, val base: FakeScreen = FakeScreen()) : ScreenPort by base {
        val taps = mutableListOf<Pair<Int, Int>>()
        var last: RawFrame? = null
        open override suspend fun peek() = ScreenSnapshot("p1", app, emptyList(), 2412, 1080)
        override suspend fun rawFrame(rows: List<Int>): RawFrame? { last = frames.removeFirstOrNull() ?: last; return last }
        open override suspend fun quickTap(x: Int, y: Int): Boolean { taps += x to y; return true }
    }

    /** 真机整帧(含弹窗、场次、加倍标签要读的行)。 */
    private fun full(name: String): RawFrame {
        val image = image(name)
        val rows = (DdzScreen.ROWS + DdzCards.ROWS + DdzCards.LOBBY_ROWS + DdzPopups.SCAN_ROWS + DdzCards.DOUBLE_ROWS).distinct()
        return RawFrame(image.width, image.height, rows.associateWith {
            image.getRGB(0, it, image.width, 1, null, 0, image.width)
        })
    }

    /**
     * 2026-11-17 13:14 精英场的局面:我是地主、上家农民(左家)加了倍,还没出第一手。
     * 用 Mika 屏、轮到我出的一帧做底(人、手牌、按钮、水印都是真的),只把两家头像那一段换成素材:
     * [badge] 为 null 时把头像上半涂黑(弹窗/头像不在),读不出标签。
     */
    private fun tableWithBadges(badge: String?): RawFrame {
        val table = image("human_left_10_10_10_3")
        val badgeImage = badge?.let(::image)
        val rows = (DdzScreen.ROWS + DdzCards.ROWS + DdzCards.LOBBY_ROWS + DdzPopups.SCAN_ROWS + DdzCards.DOUBLE_ROWS).distinct()
        return RawFrame(table.width, table.height, rows.associateWith { y ->
            when {
                badgeImage != null && y in DdzCards.DOUBLE_ROWS -> badgeImage.getRGB(0, y, table.width, 1, null, 0, table.width)
                badgeImage == null && y in 239 until 350 -> IntArray(table.width)
                else -> table.getRGB(0, y, table.width, 1, null, 0, table.width)
            }
        })
    }

    /** 我是地主,还没出第一手:tracker 里地主已知,两个对手是 landlord_up(左)/landlord_down(右)。 */
    private fun landlordMeTracker() = DdzTracker(DdzSettlingPolicy.HUMAN).apply {
        restore(DdzSeat.Me, listOf("J", "9", "3"), emptyList(), current = DdzSeat.Me)
    }

    /** 左家是地主(素材里的局面):左家是 landlord,右家是 landlord_up。 */
    private fun leftLandlordTracker() = DdzTracker(DdzSettlingPolicy.HUMAN).apply {
        restore(DdzSeat.Left, listOf("J", "9", "3"), emptyList(), current = DdzSeat.Me)
    }

    /** 走一遍 start(human) → 装上这一局的 tracker → wait,返回 DouZero 收到的 /act 请求体。 */
    private suspend fun askAt(table: RawFrame, tracker: DdzTracker = landlordMeTracker()): Pair<List<String>, ToolExecution> {
        val asked = mutableListOf<String>()
        val saved = PhoneDdzTurn.advise
        PhoneDdzTurn.advise = { _, body -> asked += body; """{"play":[]}""" }
        try {
            val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(full("human_left_10_10_10_3"), table)))
            val dispatcher = toolDispatcher(game)
            PhoneDdzTurn.execute(
                Args(PhoneDdzTurn.def, Json.parseToJsonElement(
                    """{"action":"start","mode":"human","advisor":"http://mac:3426"}"""
                ).jsonObject),
                dispatcher.ctx,
            )
            PhoneDdzTurn.useTracker(dispatcher.ctx, tracker)
            val result = PhoneDdzTurn.execute(
                Args(PhoneDdzTurn.def, Json.parseToJsonElement("""{"action":"wait"}""").jsonObject),
                dispatcher.ctx,
            )
            return asked to result
        } finally {
            PhoneDdzTurn.advise = saved
        }
    }

    /** 和真机取帧一样带上 [DdzCards.DOUBLE_ROWS] 与 blackBar 那一行。 */
    private fun trimmed(name: String): RawFrame {
        val image = image(name)
        val rows = (DdzCards.DOUBLE_ROWS + DdzScreen.HAND_ROW).distinct()
        return RawFrame(image.width, image.height, rows.associateWith {
            image.getRGB(0, it, image.width, 1, null, 0, image.width)
        })
    }

    @Test fun theLeftLandlordsBadgeIsRead() {
        // during_double: 蓝「不加倍」黄「加倍」按钮还在;after: 加倍都决定完、还没出第一手。
        for (name in listOf("double_badge_left", "double_badge_left_during")) {
            val badges = DdzCards.doubleBadges(trimmed(name))
            assertEquals("$name 左家: $badges", true, badges[DdzSide.Left])
            assertEquals("$name 右家: $badges", false, badges[DdzSide.Right])
        }
    }

    @Test fun nobodyDoubledHasNoBadge() {
        // none_2 的右家头像里有大片红,按面积不能认成标签;undecided 两家都还没决定。
        for (name in listOf("double_badge_none", "double_badge_none_2", "double_badge_none_undecided")) {
            val badges = DdzCards.doubleBadges(trimmed(name))
            assertEquals("$name 左家: $badges", false, badges[DdzSide.Left])
            assertEquals("$name 右家: $badges", false, badges[DdzSide.Right])
        }
    }

    @Test fun theRightBadgeIsFoundInTheSymmetricBox() {
        // 素材里只有左家加倍:把左家的标签按对称位置(2411 - x)抄到右家头像下沿,
        // 验证右家的框和左家一样能从红底面积认出来。真机右家待截。
        val left = image("double_badge_left")
        val none = image("double_badge_none")
        val rows = (DdzCards.DOUBLE_ROWS + DdzScreen.HAND_ROW).distinct()
        val frame = RawFrame(none.width, none.height, rows.associateWith { y ->
            IntArray(none.width) { x ->
                if (y in 355..394 && x in 2236..2302) left.getRGB(2411 - x, y) else none.getRGB(x, y)
            }
        })
        val badges = DdzCards.doubleBadges(frame)
        assertEquals("左家: $badges", false, badges[DdzSide.Left])
        assertEquals("右家: $badges", true, badges[DdzSide.Right])
    }

    @Test fun anObscuredAvatarIsUnreadable() {
        // 弹窗盖住牌桌:头像框不在画面上,不知道这一家有没有加倍,不能记成「没有」。
        val badges = DdzCards.doubleBadges(trimmed("human_relief_popup"))
        assertNull("左家: $badges", badges[DdzSide.Left])
        assertNull("右家: $badges", badges[DdzSide.Right])
    }

    @Test fun theActRequestCarriesTheDoubledOpponent() = runTest {
        val out = ByteArrayOutputStream()
        val old = System.out
        try {
            System.setOut(PrintStream(out))
            val (asked, result) = askAt(tableWithBadges("double_badge_left"))
            assertTrue("要问 DouZero: ${result.content}", asked.isNotEmpty())
            val body = Json.parseToJsonElement(asked.first()).jsonObject
            val double = body["auction"]!!.jsonObject["double"]!!.jsonObject
            assertEquals("我是地主,左家是上家: $double", true, double["landlord_up"]!!.jsonPrimitive.boolean)
            assertEquals("右家没加倍: $double", false, double["landlord_down"]!!.jsonPrimitive.boolean)
            assertNull("自己的座位不发: $double", double["landlord"])
            val log = out.toString()
            assertEquals("这一局只打一次: $log", 1, log.lines().count { it.startsWith("DDZTRACE auction") })
            assertTrue(log, log.lines().any { it == "DDZTRACE auction double=landlord_down:false,landlord_up:true" })
        } finally {
            System.setOut(old)
        }
    }

    @Test fun theLeftLandlordSeatIsNamedLandlord() = runTest {
        // 素材里的局面:左家是地主、加了倍;右家是上家。换成 DouZero 的座位名时左家就是 landlord。
        val (asked, result) = askAt(tableWithBadges("double_badge_left"), leftLandlordTracker())
        assertTrue("要问 DouZero: ${result.content}", asked.isNotEmpty())
        val double = Json.parseToJsonElement(asked.first()).jsonObject["auction"]!!.jsonObject["double"]!!.jsonObject
        assertEquals("左家: $double", true, double["landlord"]!!.jsonPrimitive.boolean)
        assertEquals("右家: $double", false, double["landlord_up"]!!.jsonPrimitive.boolean)
    }

    @Test fun theActRequestSendsFalseWhenNobodyDoubled() = runTest {
        val (asked, result) = askAt(tableWithBadges("double_badge_none"))
        assertTrue("要问 DouZero: ${result.content}", asked.isNotEmpty())
        val double = Json.parseToJsonElement(asked.first()).jsonObject["auction"]!!.jsonObject["double"]!!.jsonObject
        assertEquals("左家: $double", false, double["landlord_up"]!!.jsonPrimitive.boolean)
        assertEquals("右家: $double", false, double["landlord_down"]!!.jsonPrimitive.boolean)
    }

    @Test fun theActRequestOmitsTheAuctionWhenTheLabelsAreUnreadable() = runTest {
        val (asked, result) = askAt(tableWithBadges(null))
        assertTrue("要问 DouZero: ${result.content}", asked.isNotEmpty())
        assertNull("读不到就不发: ${asked.first()}", Json.parseToJsonElement(asked.first()).jsonObject["auction"])
        assertFalse("请求体里不能有 auction: ${asked.first()}", asked.first().contains("\"auction\""))
    }
}
