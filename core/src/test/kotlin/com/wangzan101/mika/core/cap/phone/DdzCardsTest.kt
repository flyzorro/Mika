package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.uistate.RawFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun readsWhatEachOpponentPlayed() {
        assertEquals(DdzSeen.Cards(listOf("5", "5")), DdzCards.play(frame("table_left55"), DdzSide.Left))
        assertEquals(DdzSeen.Cards(listOf("K", "K")), DdzCards.play(frame("table_rightKK"), DdzSide.Right))
        assertEquals(DdzSeen.Cards(listOf("3", "3")), DdzCards.play(frame("table_66_33"), DdzSide.Right))
    }

    @Test fun theLandlordsRibbonCardIsFilledInFromTheShape() {
        assertEquals(DdzSeen.Cards(listOf("6", "6")), DdzCards.play(frame("table_66_33"), DdzSide.Left))
        assertEquals(DdzSeen.Cards(listOf("9", "9")), DdzCards.play(frame("table_left99"), DdzSide.Left))
        assertEquals(DdzSeen.Cards("10 10 9 9 8 8".split(' ')), DdzCards.play(frame("hand_t1"), DdzSide.Left))
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
    }

    @Test fun seesWhoseTurnItIs() {
        assertEquals(DdzSide.Right, DdzCards.clock(frame("table_left55")))
        assertEquals(DdzSide.Left, DdzCards.clock(frame("table_rightKK")))
        assertNull(DdzCards.clock(frame("hand_t1")))
    }
}
