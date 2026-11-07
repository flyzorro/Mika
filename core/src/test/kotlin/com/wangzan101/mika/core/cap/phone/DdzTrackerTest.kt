package com.wangzan101.mika.core.cap.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DdzTrackerTest {
    private val bottom = listOf("9", "8", "3")
    private var left: DdzSeen = DdzSeen.Empty
    private var right: DdzSeen = DdzSeen.Empty
    private val tracker = DdzTracker()

    private fun see(turn: DdzSeat?) = tracker.observe(turn, { if (it == DdzSide.Left) left else right }, { bottom })

    @Test fun nothingIsRecordedUntilTheBottomCardsAreShown() {
        tracker.observe(DdzSeat.Left, { DdzSeen.Empty }, { null })
        assertNull(tracker.landlord)
        see(DdzSeat.Right)
        assertEquals(DdzSeat.Right, tracker.landlord)
    }

    @Test fun theFirstToPlayIsTheLandlordAndMovesComeInOrder() {
        see(DdzSeat.Left)
        left = DdzSeen.Cards(listOf("3"))
        see(DdzSeat.Right)
        right = DdzSeen.Pass
        see(DdzSeat.Me)
        assertEquals(DdzSeat.Left, tracker.landlord)
        assertEquals(listOf(listOf("3"), emptyList()), tracker.history)
        assertTrue(tracker.ready())
        assertEquals("landlord_up", tracker.seat())
        tracker.mine(listOf("5"))
        assertEquals(3, tracker.history.size)
    }

    @Test fun aSeatSkippedBetweenTwoFramesIsStillRead() {
        see(DdzSeat.Me)
        tracker.mine(listOf("4"))
        left = DdzSeen.Cards(listOf("6"))
        right = DdzSeen.Cards(listOf("7"))
        see(DdzSeat.Me) // 闹钟从我这里转了一圈又回来:左右都出完了
        see(DdzSeat.Right)
        see(DdzSeat.Me)
        assertEquals(listOf(listOf("4"), listOf("6"), listOf("7")), tracker.history)
        assertEquals("landlord", tracker.seat())
    }

    @Test fun cardsStillFlyingAreReadOnALaterFrame() {
        see(DdzSeat.Right)
        see(DdzSeat.Me) // 右家出完,牌还没落下
        assertFalse(tracker.ready())
        right = DdzSeen.Cards(listOf("K"))
        see(DdzSeat.Me)
        assertTrue(tracker.ready())
        assertEquals(listOf(listOf("K")), tracker.history)
    }

    @Test fun anUnreadableMoveStopsTheGameForDouZero() {
        see(DdzSeat.Left)
        left = DdzSeen.Unsure
        see(DdzSeat.Right)
        assertEquals("unsure_left", tracker.broken)
        assertFalse(tracker.ready())
    }

    @Test fun aMoveNeverSeenStopsAfterAFewFrames() {
        see(DdzSeat.Left)
        see(DdzSeat.Right)
        repeat(10) { see(DdzSeat.Right) }
        assertEquals("unread_left", tracker.broken)
    }
}
