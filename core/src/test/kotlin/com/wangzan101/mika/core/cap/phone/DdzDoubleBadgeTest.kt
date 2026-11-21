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
    @org.junit.Before fun stubAdvisor() {
        DdzAdvisor.lastReachableUrl = null
        PhoneDdzTurn.probeAdvisor = { url -> url == "http://mac:3426" }
        PhoneDdzTurn.adviseBid = { _, _ -> null }
        PhoneDdzTurn.adviseDouble = { _, _, _, _ -> null }
        PhoneDdzTurn.clock = System::currentTimeMillis
    }

    @org.junit.After fun restoreAdvisorProbe() {
        PhoneDdzTurn.probeAdvisor = DdzAdvisor::probe
        DdzAdvisor.lastReachableUrl = null
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

    /** 我是地主、第一手已经出过:左家刚出 10 10 10 3,右家不出,轮到我(和 human_left_10_10_10_3 的画面一致)。 */
    private fun landlordMeAfterFirstPlay() = DdzTracker(DdzSettlingPolicy.HUMAN).apply {
        restore(
            DdzSeat.Me, listOf("J", "9", "3"),
            listOf(emptyList(), emptyList(), listOf("10", "10", "10", "3"), emptyList()),
            current = DdzSeat.Me,
        )
    }

    /** 左家是地主(素材里的局面):左家是 landlord,右家是 landlord_up。 */
    private fun leftLandlordTracker() = DdzTracker(DdzSettlingPolicy.HUMAN).apply {
        restore(DdzSeat.Left, listOf("J", "9", "3"), emptyList(), current = DdzSeat.Me)
    }

    /** 右家是地主(2026-11-17 13:30 素材):右家是 landlord,左家是 landlord_down。 */
    private fun rightLandlordTracker() = DdzTracker(DdzSettlingPolicy.HUMAN).apply {
        restore(DdzSeat.Right, listOf("J", "9", "3"), emptyList(), current = DdzSeat.Me)
    }

    /** 没有按钮的一帧(别家还在想):phase 是 Other,不会马上问 DouZero。 */
    private fun withoutButtons(frame: RawFrame): RawFrame {
        val rows = (DdzScreen.ROWS + DdzCards.ROWS + DdzCards.LOBBY_ROWS + DdzPopups.SCAN_ROWS + DdzCards.DOUBLE_ROWS).distinct()
        return RawFrame(frame.width, frame.height, rows.associateWith { y ->
            if (y in 440..525) IntArray(frame.width) else frame.row(y)!!.copyOf()
        })
    }

    /**
     * 第一手之前的一帧:两家都没加倍、出牌区还空着。头像和下沿那几行用 [tableWithBadges] 的素材
     * (底图自己的头像框不是读标签的那一版),只把两块出牌区涂黑、按钮清掉。
     */
    private fun beforeFirstPlay(): RawFrame {
        val table = tableWithBadges("double_badge_none")
        val rows = (DdzScreen.ROWS + DdzCards.ROWS + DdzCards.LOBBY_ROWS + DdzPopups.SCAN_ROWS + DdzCards.DOUBLE_ROWS).distinct()
        return RawFrame(table.width, table.height, rows.associateWith { y ->
            when {
                y in 440..525 -> IntArray(table.width)
                y in 220..420 -> IntArray(table.width) { x ->
                    if (x in 290..930 || x in 1425..2100) 0 else table.pixel(x, y)
                }
                else -> table.row(y)!!.copyOf()
            }
        })
    }

    /**
     * 真机 2026-11-17 16:13 精英场的第一手:右家(地主)出顺子 10 9 8 7 6 5 4,
     * 「顺子」金红特效压在右家头像下沿。用同一张牌桌做底,出牌区那几行换成素材。
     */
    private fun firstPlayStraight(landing: Boolean = false): RawFrame {
        val table = image("human_left_10_10_10_3")
        val play = image("right_straight_effect_first_play")
        val rows = (DdzScreen.ROWS + DdzCards.ROWS + DdzCards.LOBBY_ROWS + DdzPopups.SCAN_ROWS + DdzCards.DOUBLE_ROWS).distinct()
        return RawFrame(table.width, table.height, rows.associateWith { y ->
            if (y in 220..420) play.getRGB(0, y, table.width, 1, null, 0, table.width).also { row ->
                // 落下那一帧:中间那张 8 的字还认不出来,用牌面底色盖掉
                if (landing && y in 240..315) row.fill(0xFFD4D3CF.toInt(), 1762, 1812)
            }
            else table.getRGB(0, y, table.width, 1, null, 0, table.width)
        })
    }

    /** 第一手之前一帧 [beforeFirstPlay],接着出现 [firstPlay]:右家地主,返回 /act 请求里的 auction.double。 */
    private suspend fun doubleAfterFirstPlay(firstPlay: RawFrame): kotlinx.serialization.json.JsonObject {
        val asked = mutableListOf<String>()
        val saved = PhoneDdzTurn.advise
        PhoneDdzTurn.advise = { _, body -> asked += body; """{"play":[]}""" }
        try {
            val table = full("human_left_10_10_10_3")
            val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(table, table, beforeFirstPlay(), firstPlay)))
            val dispatcher = toolDispatcher(game)
            PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement(
                """{"action":"start","mode":"human","advisor":"http://mac:3426"}"""
            ).jsonObject), dispatcher.ctx)
            PhoneDdzTurn.useTracker(dispatcher.ctx, rightLandlordTracker())
            val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement(
                """{"action":"start","mode":"human","auto":true,"advisor":"http://mac:3426"}"""
            ).jsonObject), dispatcher.ctx)
            assertTrue("要问 DouZero: ${result.content} $asked", asked.isNotEmpty())
            return Json.parseToJsonElement(asked.first()).jsonObject["auction"]!!.jsonObject["double"]!!.jsonObject
        } finally {
            PhoneDdzTurn.advise = saved
        }
    }

    /** 走一遍 start(human) → 装上这一局的 tracker → wait,返回 DouZero 收到的 /act 请求体。 */
    private suspend fun askAt(
        table: RawFrame,
        tracker: DdzTracker = landlordMeTracker(),
        first: RawFrame? = null,
    ): Pair<List<String>, ToolExecution> {
        val asked = mutableListOf<String>()
        val saved = PhoneDdzTurn.advise
        PhoneDdzTurn.advise = { _, body -> asked += body; """{"play":[]}""" }
        try {
            val frames = ArrayDeque<RawFrame>()
            frames += full("human_left_10_10_10_3")
            first?.let { frames += it }
            frames += table
            val game = object : Game(PhoneDdzTurn.PACKAGE, frames) {
                override suspend fun quickTap(x: Int, y: Int): Boolean {
                    val tapped = super.quickTap(x, y)
                    this.frames.clear()
                    this.frames.add(full("human_result_leave_change_continue"))
                    return tapped
                }
            }
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

    @Test fun theRightLandlordsBadgeIsRead() {
        // 2026-11-17 13:30 精英场:右家是地主、加了倍;左家是牌背占位图,没加倍。
        // during_double 是刚加倍、after 是加倍结束出牌前、follow 是已经在出牌阶段(标签还留着)。
        for (name in listOf("double_badge_right", "double_badge_right_during", "double_badge_right_follow")) {
            val badges = DdzCards.doubleBadges(trimmed(name))
            assertEquals("$name 左家: $badges", false, badges[DdzSide.Left])
            assertEquals("$name 右家: $badges", true, badges[DdzSide.Right])
        }
    }

    @Test fun aRedAvatarWithoutABadgeIsNotDoubled() {
        // 真机 2026-11-17 14:06 精英场:左家头像本身就是红底(红背景上一只玉龙),没加倍。
        // 以前只数红底面积,左家读到 287 像素 > 250,误报成 landlord_up:true。
        val badges = DdzCards.doubleBadges(trimmed("double_badge_left_red_avatar"))
        assertEquals("左家红底头像没加倍: $badges", false, badges[DdzSide.Left])
        assertEquals("右家没加倍: $badges", false, badges[DdzSide.Right])
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

    @Test fun theRightLandlordSeatIsNamedLandlord() = runTest {
        // 2026-11-17 13:30 素材:右家是地主、加了倍;左家是牌背占位图,没加倍。
        val (asked, result) = askAt(tableWithBadges("double_badge_right"), rightLandlordTracker())
        assertTrue("要问 DouZero: ${result.content}", asked.isNotEmpty())
        val double = Json.parseToJsonElement(asked.first()).jsonObject["auction"]!!.jsonObject["double"]!!.jsonObject
        assertEquals("右家: $double", true, double["landlord"]!!.jsonPrimitive.boolean)
        assertEquals("左家: $double", false, double["landlord_down"]!!.jsonPrimitive.boolean)
    }

    @Test fun aBadgeThatLingersAfterTheFirstPlayDoesNotChangeTheAuction() = runTest {
        // follow 素材:已经在出牌阶段、标签还在。第一手出来时先把两家都定成「没有」,之后就定下来了:
        // 后面这帧即使还认得出右家的标签,也不能再把读数改掉(2026-11-17 15:20 的顺子特效就是这样误报的)。
        val first = withoutButtons(tableWithBadges("double_badge_none"))
        assertTrue("构造:第一帧还是牌桌", DdzCards.classicHumanTable(first))
        assertEquals("构造:第一帧没有按钮", DdzPhase.Other, DdzScreen.read(first, classic = true).phase)
        val (asked, result) = askAt(tableWithBadges("double_badge_right_follow"), landlordMeAfterFirstPlay(), first = first)
        assertTrue("要问 DouZero: ${result.content}", asked.isNotEmpty())
        val double = Json.parseToJsonElement(asked.first()).jsonObject["auction"]!!.jsonObject["double"]!!.jsonObject
        assertEquals("第一手以后右家的读数就定了: $double", false, double["landlord_down"]!!.jsonPrimitive.boolean)
        assertEquals("左家一直没标签: $double", false, double["landlord_up"]!!.jsonPrimitive.boolean)
    }

    @Test fun aStraightEffectAfterTheFirstPlayDoesNotChangeTheAuction() = runTest {
        // 真机 2026-11-17 15:20 精英场:开局两家都没加倍,第一手已经出了;快结束时右家出一条顺子,
        // 金红色的「顺子」特效正好压在右家头像下沿,被认成加倍标签。第一手出来以后读数就定了,后面的帧不能再改。
        val asked = mutableListOf<String>()
        val saved = PhoneDdzTurn.advise
        PhoneDdzTurn.advise = { _, body -> asked += body; """{"play":[]}""" }
        try {
            val table = tableWithBadges("double_badge_none")
            val straight = tableWithBadges("right_straight_effect_after_first_play")
            assertEquals(
                "构造:顺子特效压在右家头像下沿,单看这一帧会认成有标签: ${DdzCards.doubleBadges(straight)}",
                true, DdzCards.doubleBadges(straight)[DdzSide.Right],
            )
            val game = Game(PhoneDdzTurn.PACKAGE, ArrayDeque(listOf(table, table, withoutButtons(table), straight)))
            val dispatcher = toolDispatcher(game)
            PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement(
                """{"action":"start","mode":"human","advisor":"http://mac:3426"}"""
            ).jsonObject), dispatcher.ctx)
            PhoneDdzTurn.useTracker(dispatcher.ctx, landlordMeAfterFirstPlay())
            val result = PhoneDdzTurn.execute(Args(PhoneDdzTurn.def, Json.parseToJsonElement(
                """{"action":"start","mode":"human","auto":true,"advisor":"http://mac:3426"}"""
            ).jsonObject), dispatcher.ctx)
            assertTrue("要问 DouZero: ${result.content} $asked", asked.isNotEmpty())
            val double = Json.parseToJsonElement(asked.first()).jsonObject["auction"]!!.jsonObject["double"]!!.jsonObject
            assertEquals("第一手以后右家的读数定了,不能被顺子特效改成加倍: $double", false, double["landlord_down"]!!.jsonPrimitive.boolean)
            assertEquals("左家没加倍: $double", false, double["landlord_up"]!!.jsonPrimitive.boolean)
        } finally {
            PhoneDdzTurn.advise = saved
        }
    }

    @Test fun theRedAvatarDoesNotSendALandlordUpDouble() = runTest {
        // 真机 2026-11-17 14:06 的 trace 是 landlord_up:true;我是地主,左家是上家。
        // 左家只是红底头像、没加倍,请求体里应该是 false。
        val (asked, result) = askAt(tableWithBadges("double_badge_left_red_avatar"))
        assertTrue("要问 DouZero: ${result.content}", asked.isNotEmpty())
        val double = Json.parseToJsonElement(asked.first()).jsonObject["auction"]!!.jsonObject["double"]!!.jsonObject
        assertEquals("左家红底头像: $double", false, double["landlord_up"]!!.jsonPrimitive.boolean)
        assertEquals("右家: $double", false, double["landlord_down"]!!.jsonPrimitive.boolean)
    }

    @Test fun theFirstPlayStraightEffectDoesNotMarkTheLandlordAsDoubled() = runTest {
        // 真机 2026-11-17 16:13 精英场:三家都没加倍。地主(右家)第一手出顺子 10 9 8 7 6 5 4,
        // 「顺子」特效压在他头像下沿;这一帧出牌区已经有牌,但 tracker 还没把这手记进 history,
        // 所以 #116 的「第一手以后冻结」还没生效,旧逻辑会把特效读成加倍标签。
        val before = beforeFirstPlay()
        val firstPlay = firstPlayStraight()
        assertTrue("构造:第一手之前还是牌桌", DdzCards.classicHumanTable(before))
        assertEquals("构造:第一手之前两家都没标签: ${DdzCards.doubleBadges(before)}", mapOf(DdzSide.Left to false, DdzSide.Right to false), DdzCards.doubleBadges(before))
        assertEquals("构造:第一手之前不会有按钮", DdzPhase.Other, DdzScreen.read(before, classic = true).phase)
        assertEquals(
            "构造:第一手之前出牌区是空的: ${DdzCards.play(before, DdzSide.Left)} ${DdzCards.play(before, DdzSide.Right)}",
            listOf(DdzSeen.Empty, DdzSeen.Empty),
            listOf(DdzCards.play(before, DdzSide.Left), DdzCards.play(before, DdzSide.Right)),
        )
        assertTrue(
            "构造:右家出牌区已经摆着顺子: ${DdzCards.play(firstPlay, DdzSide.Right)}",
            DdzCards.play(firstPlay, DdzSide.Right) is DdzSeen.Cards,
        )
        assertEquals(
            "构造:单看这一帧会把右家头像下沿的特效认成标签: ${DdzCards.doubleBadges(firstPlay)}",
            true, DdzCards.doubleBadges(firstPlay)[DdzSide.Right],
        )
        val double = doubleAfterFirstPlay(firstPlay)
        assertEquals("第一手出牌区的顺子不能把地主读成加倍: $double", false, double["landlord"]!!.jsonPrimitive.boolean)
        assertEquals("左家没加倍: $double", false, double["landlord_down"]!!.jsonPrimitive.boolean)
    }

    @Test fun aFirstPlayStillLandingDoesNotMarkTheLandlordAsDoubled() = runTest {
        // 真机 2026-11-17 20:22:55:顺子落下的那一帧出牌区读成 Unsure,半秒后才是 Cards。
        // Unsure 也说明出牌区已经摆着牌(第一手之前出牌区不会有白牌面),这一帧的标签同样不能读。
        val landing = firstPlayStraight(landing = true)
        assertEquals("构造:右家出牌区还认不全: ${DdzCards.play(landing, DdzSide.Right)}", DdzSeen.Unsure, DdzCards.play(landing, DdzSide.Right))
        assertEquals(
            "构造:单看这一帧会把右家头像下沿的特效认成标签: ${DdzCards.doubleBadges(landing)}",
            true, DdzCards.doubleBadges(landing)[DdzSide.Right],
        )
        val double = doubleAfterFirstPlay(landing)
        assertEquals("出牌区认不全的第一手也不能把地主读成加倍: $double", false, double["landlord"]!!.jsonPrimitive.boolean)
        assertEquals("左家没加倍: $double", false, double["landlord_down"]!!.jsonPrimitive.boolean)
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
