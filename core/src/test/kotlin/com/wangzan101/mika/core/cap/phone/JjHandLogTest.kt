package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.screen.uistate.VisibleTextLine
import org.junit.Assert.*
import org.junit.Test
import com.wangzan101.mika.core.screen.uistate.RawFrame
import javax.imageio.ImageIO

class JjHandLogTest {
    private val hand = JjHandLog(1)
    private fun line(text: String, vararg bounds: Int) = VisibleTextLine(text, bounds.toList())

    @Test fun archivedSeaPayoutOverridesTrackerAndReadsDecimalMultiplier() {
        // jj-ddz-rec1/f0097.png. Exclude rank, the other seats and the unsigned balance.
        val payout = listOf(line("-450", 302, 588, 416, 672), line("+900", 621, 225, 734, 316),
            line("100x4.5", 534, 27, 598, 65), line("262", 310, 684, 357, 729))
        val trackerResult = hand.snapshot("session", null, "hand_gap", null).copy(
            win = true, winner = "me", history_complete = true, spring = false)
        val row = JjHandLog.enrich(trackerResult, payout, settlement = true)
        assertEquals(false, row.win)
        assertEquals(-450, row.score_change)
        assertEquals("settlement_score", row.evidence)
        assertEquals(4.5, row.multiplier)
        assertEquals("settlement", row.stakes_source)
        assertNull(row.winner)
        assertNull(row.spring)
        assertFalse(row.history_complete)
    }

    @Test fun ambiguousOrUnsignedPayoutLeavesUnknowns() {
        val row = hand.snapshot("session", null, "hand_gap", null)
        assertNull(row.role)
        assertNull(row.advisor_reachable)
        val lines = listOf("+30", "-30", "500").map { line(it, 285, 508, 375, 614) }
        val result = JjHandLog.enrich(row, lines, settlement = true)
        assertEquals("unknown", result.evidence)
        assertNull(result.win)
        assertNull(result.score_change)
        assertNull(result.multiplier)
    }

    @Test fun badgeZeroSurvivesMissingFinalPlayAndUnknownRoleStaysUnknown() {
        // Keep the printed 0 from the right-hand "10" on replay row 22; center it on the badge.
        val sheet = ImageIO.read(javaClass.getResource("/ddz-jj/opponent-counts-1125.png"))
        val rows = JjDdzScreen.ROWS.associateWith { y ->
            IntArray(2412).also { pixels ->
                if (y in 448..490 && y % 2 == 0) {
                    val src = 22 * 43 + y - 448
                    sheet.getRGB(0, src, 100, 1, pixels, 265, 100)
                    sheet.getRGB(142, src, 26, 1, pixels, 2121, 26)
                }
            }
        }
        val frame = RawFrame(2412, 1080, rows)
        val counts = JjDdzScreen.counts(frame)
        assertEquals(mapOf(DdzSide.Left to 1, DdzSide.Right to 0), counts)
        val view = JjView(DdzPhase.Other, emptyList(), null)
        hand.ending.observe(frame, view, counts, 0, false, byteArrayOf(1))
        assertNull(hand.snapshot("s", null, "new_hand", null).win)
        hand.stableLandlord = DdzSeat.Right
        val lost = hand.snapshot("s", null, "new_hand", null)
        assertEquals("badge_zero", lost.evidence)
        assertEquals("right", lost.winner)
        assertEquals(false, lost.win)
        assertFalse(lost.history_complete)
        assertNull(lost.spring)
        hand.stableLandlord = DdzSeat.Left
        assertEquals(true, hand.snapshot("s", null, "new_hand", null).win)
        hand.ending.ownHandCleared()
        assertNull(hand.snapshot("s", null, "new_hand", null).winner)
    }

    @Test fun landlordAndFarmerVictoriesNeedOneSeatAtZeroAndAnIntactHistory() {
        val tracker = DdzTracker()
        val moves = listOf("3", "4", "5", "6", "7").flatMap { rank ->
            listOf(List(4) { rank }, emptyList(), emptyList())
        }.dropLast(2)
        tracker.restore(DdzSeat.Right, emptyList(), moves)
        var result = hand.snapshot("s", null, "hand_gap", tracker)
        assertEquals("tracker_zero", result.evidence)
        assertEquals("landlord_up", result.role)
        assertEquals("right", result.winner)
        assertEquals(false, result.win)
        assertEquals(true, result.spring)
        assertEquals(5, result.bombs)

        // Right farmer finishes before the left landlord. Both farmers win.
        tracker.restore(DdzSeat.Left, emptyList(), listOf(listOf("3"), listOf("4"), listOf("5")) +
            listOf("6", "7", "8", "9").flatMap { listOf(emptyList(), emptyList(), List(4) { _ -> it }) })
        result = hand.snapshot("s", null, "hand_gap", tracker)
        assertEquals("right", result.winner)
        assertEquals(true, result.win)
        assertEquals(true, result.spring)
        tracker.fail("missed_play")
        assertNull(hand.snapshot("s", null, "hand_gap", tracker).win)
    }
}
