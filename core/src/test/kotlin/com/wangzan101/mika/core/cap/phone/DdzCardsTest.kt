package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.uistate.RawFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.imageio.ImageIO

class DdzCardsTest {
    private fun frame(name: String): RawFrame {
        val image = ImageIO.read(javaClass.getResourceAsStream("/ddz/$name.jpg")!!)
        return RawFrame(image.width, image.height, (DdzScreen.ROWS + DdzCards.ROWS).distinct().associateWith { image.getRGB(0, it, image.width, 1, null, 0, image.width) })
    }

    private fun hand(name: String) = frame(name).let { DdzCards.hand(it, DdzScreen.read(it)) }

    @Test fun readsHandsLeftToRight() {
        assertEquals("SJ 2 2 2 A A A A Q J 10 9 9 8 7 3 3".split(' '), hand("hand_t1"))
        // 闯关牌桌:手牌更高
        assertEquals("BJ SJ 2 2 A A Q Q Q Q J J 10 9 9 5 4".split(' '), hand("vd_stage_call"))
        assertEquals("BJ SJ 2 2 A A A K Q J J 10 10 9 9 8 5".split(' '), hand("hand_k2"))
    }

    @Test fun readsTheHandOnMikasWiderDisplay() {
        assertEquals("BJ SJ 2 2 A A A K Q J J 10 10 9 9 8 5".split(' '), hand("vd_hand_follow"))
        // 地主 20 张、最后一张带绶带
        assertEquals("BJ SJ 2 2 A A K Q J 10 9 8 7 6 6 4 4 3 3 3".split(' '), hand("vd_landlord_lead"))
    }

    @Test fun readsWhatEachOpponentPlayed() {
        assertRibbonPlay("5 5", DdzCards.play(frame("table_left55"), DdzSide.Left))
        assertEquals(DdzSeen.Cards(listOf("K", "K")), DdzCards.play(frame("table_rightKK"), DdzSide.Right))
        assertEquals(DdzSeen.Cards(listOf("3", "3")), DdzCards.play(frame("table_66_33"), DdzSide.Right))
    }

    /** 绶带那张要么认准,要么留成 ? 交给 DouZero 服务按牌规补;别的牌必须认准。 */
    private fun assertRibbonPlay(expected: String, seen: DdzSeen) {
        val ranks = (seen as DdzSeen.Cards).ranks
        val want = expected.split(' ')
        assertEquals(want.dropLast(1), ranks.dropLast(1))
        // 认不准时带的猜测不一定对(99988 的 8 猜成了 7、J、5):多张的牌型由服务按牌规补
        assertTrue("$ranks", ranks.last() == want.last() || ranks.last().startsWith(DdzCards.UNKNOWN))
    }

    @Test fun theLandlordsRibbonCardIsReadOrLeftForTheRules() {
        assertRibbonPlay("6 6", DdzCards.play(frame("table_66_33"), DdzSide.Left))
        assertRibbonPlay("9 9", DdzCards.play(frame("table_left99"), DdzSide.Left))
        assertRibbonPlay("10 10 9 9 8 8", DdzCards.play(frame("hand_t1"), DdzSide.Left))
        assertRibbonPlay("9 9 9 8 8", DdzCards.play(frame("vd_right_99988"), DdzSide.Right))
    }

    @Test fun aSingleRibbonCardItCannotReadIsLeftForTheRulesWithGuesses() {
        val frame = frame("table_left10_right8")
        // 单张什么都说得通,只能靠猜:最像的就是 10
        assertEquals(DdzSeen.Cards(listOf("?10,K,9")), DdzCards.play(frame, DdzSide.Left))
        assertEquals(DdzSeen.Cards(listOf("8")), DdzCards.play(frame, DdzSide.Right))
    }

    @Test fun aGreyedOutEarlierPlayIsNotCounted() {
        val frame = frame("table_leftdim_rightQ")
        assertEquals(DdzSeen.Empty, DdzCards.play(frame, DdzSide.Left))
        assertEquals(DdzSeen.Cards(listOf("Q")), DdzCards.play(frame, DdzSide.Right))
    }

    @Test fun readsTheBiggerHigherPlaysOnAStageTable() {
        val frame = frame("vd_stage_leftpass_rightQQQ6")
        assertEquals(DdzSeen.Pass, DdzCards.play(frame, DdzSide.Left))
        assertEquals(DdzSeen.Cards("Q Q Q 6".split(' ')), DdzCards.play(frame, DdzSide.Right))
        assertEquals(null, DdzCards.clock(frame))
        // 自己刚出的顺子在中间,不算任何一家的;右家的闹钟在他张数框的里侧
        val clock = frame("vd_stage_right_clock")
        assertEquals(DdzSeen.Empty, DdzCards.play(clock, DdzSide.Left))
        assertEquals(DdzSeen.Empty, DdzCards.play(clock, DdzSide.Right))
        assertEquals(DdzSide.Right, DdzCards.clock(clock))
    }

    @Test fun tellsAPassFromAnEmptyArea() {
        val frame = frame("table_leftpass")
        assertEquals(DdzSeen.Pass, DdzCards.play(frame, DdzSide.Left))
        assertEquals(DdzSeen.Empty, DdzCards.play(frame, DdzSide.Right))
    }

    @Test fun readsTheBottomCards() {
        assertEquals(listOf("9", "8", "3"), DdzCards.bottom(frame("table_left55")))
        // 6 和 5 在底牌那么小的字上只差几笔
        assertEquals(listOf("6", "9", "J"), DdzCards.bottom(frame("vd_bottom_69J")))
    }

    @Test fun seesWhoseTurnItIs() {
        assertEquals(DdzSide.Right, DdzCards.clock(frame("table_left55")))
        assertEquals(DdzSide.Left, DdzCards.clock(frame("table_rightKK")))
        assertNull(DdzCards.clock(frame("hand_t1")))
    }
}
