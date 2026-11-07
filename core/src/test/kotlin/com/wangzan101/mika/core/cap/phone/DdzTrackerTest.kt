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

    @Test fun anUnreadableBottomDoesNotHoldTheGameBack() {
        tracker.observe(DdzSeat.Me, { if (it == DdzSide.Right) DdzSeen.Cards(listOf("5")) else DdzSeen.Empty }, { null })
        assertEquals(DdzSeat.Right, tracker.landlord)
        assertNull(tracker.bottom)
    }

    @Test fun playGoesMeThenRightThenLeft() {
        assertEquals(DdzSeat.Right, DdzSeat.Me.next)
        assertEquals(DdzSeat.Left, DdzSeat.Right.next)
        assertEquals(DdzSeat.Me, DdzSeat.Left.next)
    }

    @Test fun clocksDuringDoublingAreIgnoredUntilTheLandlordPlays() {
        see(DdzSeat.Left)
        see(DdzSeat.Right)
        see(DdzSeat.Left)
        assertNull(tracker.landlord)
        // 右家是地主,出了 3;下一个是左家
        right = DdzSeen.Cards(listOf("3"))
        see(DdzSeat.Left)
        left = DdzSeen.Pass
        see(DdzSeat.Me)
        assertEquals(DdzSeat.Right, tracker.landlord)
        assertEquals(listOf(listOf("3"), emptyList()), tracker.history)
        assertTrue(tracker.ready())
        assertEquals("landlord_up", tracker.seat())
        tracker.mine(listOf("5"))
        assertEquals(3, tracker.history.size)
    }

    @Test fun leadingWithTwentyCardsMakesMeTheLandlord() {
        see(DdzSeat.Left) // 加倍时的闹钟
        tracker.observe(DdzSeat.Me, { DdzSeen.Empty }, { bottom }, leadingWith20 = true)
        assertEquals(DdzSeat.Me, tracker.landlord)
        assertTrue(tracker.ready())
        tracker.mine(listOf("3", "3"))
        right = DdzSeen.Cards(listOf("4", "4"))
        left = DdzSeen.Cards(listOf("6", "6"))
        see(DdzSeat.Left)  // 右家出完
        see(DdzSeat.Me)    // 左家出完
        assertEquals(listOf(listOf("3", "3"), listOf("4", "4"), listOf("6", "6")), tracker.history)
        assertEquals("landlord", tracker.seat())
    }

    @Test fun aSeatSkippedBetweenTwoFramesIsStillRead() {
        tracker.observe(DdzSeat.Me, { DdzSeen.Empty }, { bottom }, leadingWith20 = true)
        tracker.mine(listOf("4"))
        right = DdzSeen.Cards(listOf("6"))
        left = DdzSeen.Cards(listOf("7"))
        see(DdzSeat.Me) // 闹钟从我这里转了一圈又回来:右、左都出完了
        assertEquals(listOf(listOf("4"), listOf("6"), listOf("7")), tracker.history)
    }

    @Test fun theLeftLandlordMakesMeItsNextSeat() {
        left = DdzSeen.Cards(listOf("3"))
        see(DdzSeat.Me)
        assertEquals(DdzSeat.Left, tracker.landlord)
        assertEquals("landlord_down", tracker.seat())
        assertTrue(tracker.ready())
    }

    @Test fun anUnreadableFirstPlayIsNotPinnedOnTheNextSeat() {
        right = DdzSeen.Unsure
        see(DdzSeat.Left)
        left = DdzSeen.Cards(listOf("5"))
        see(DdzSeat.Me)
        assertNull(tracker.landlord)
        assertEquals("unsure_first_play", tracker.broken)
    }

    @Test fun cardsStillFlyingAreReadOnALaterFrame() {
        left = DdzSeen.Cards(listOf("3"))
        see(DdzSeat.Me)          // 左家是地主,出完轮到我
        tracker.mine(listOf("4"))
        left = DdzSeen.Empty
        see(DdzSeat.Left)        // 右家出完,牌还没落下
        assertEquals(2, tracker.history.size)
        right = DdzSeen.Cards(listOf("K"))
        see(DdzSeat.Left)
        assertEquals(listOf(listOf("3"), listOf("4"), listOf("K")), tracker.history)
    }

    @Test fun anUnreadableMoveStopsTheGameForDouZero() {
        left = DdzSeen.Cards(listOf("3"))
        see(DdzSeat.Me)
        tracker.mine(listOf("4"))
        right = DdzSeen.Unsure
        see(DdzSeat.Left)
        assertEquals("unsure_right", tracker.broken)
        assertFalse(tracker.ready())
    }

    @Test fun aMoveNeverSeenStopsAfterAFewFrames() {
        left = DdzSeen.Cards(listOf("3"))
        see(DdzSeat.Me)
        tracker.mine(listOf("4"))
        left = DdzSeen.Empty
        see(DdzSeat.Left)
        repeat(10) { see(DdzSeat.Left) }
        assertEquals("unread_right", tracker.broken)
    }
}
