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
        assertTrue(ranks.last() == want.last() || ranks.last() == DdzCards.UNKNOWN)
    }

    @Test fun theLandlordsRibbonCardIsReadOrLeftForTheRules() {
        assertRibbonPlay("6 6", DdzCards.play(frame("table_66_33"), DdzSide.Left))
        assertRibbonPlay("9 9", DdzCards.play(frame("table_left99"), DdzSide.Left))
        assertRibbonPlay("10 10 9 9 8 8", DdzCards.play(frame("hand_t1"), DdzSide.Left))
        assertRibbonPlay("9 9 9 8 8", DdzCards.play(frame("vd_right_99988"), DdzSide.Right))
    }

    @Test fun aSingleRibbonCardItCannotReadIsUnsureNotGuessed() {
        val frame = frame("table_left10_right8")
        assertEquals(DdzSeen.Unsure, DdzCards.play(frame, DdzSide.Left))
        assertEquals(DdzSeen.Cards(listOf("8")), DdzCards.play(frame, DdzSide.Right))
    }

    @Test fun aGreyedOutEarlierPlayIsNotCounted() {
        val frame = frame("table_leftdim_rightQ")
        assertEquals(DdzSeen.Empty, DdzCards.play(frame, DdzSide.Left))
        assertEquals(DdzSeen.Cards(listOf("Q")), DdzCards.play(frame, DdzSide.Right))
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
