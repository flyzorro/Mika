package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.screen.uistate.RawFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.imageio.ImageIO

class JjHiddenRocketTest {
    private fun moves(s: String) = s.split('/').map { if (it.isEmpty()) emptyList() else it.split(' ') }
    private val beforeFour = moves("6 6//9 9/K K/2 2///6/7/2///5 5 5 3//8 8 8 4/Q Q Q 4/10 10 10 10//")
    private val hand = "K Q J J J 9 8 3 3"
    private val tracker = DdzTracker(DdzSettlingPolicy.HUMAN)
    private val session = JjDdzTurn.Session("", 1).also { it.tracker = tracker }
    private val emptyView = JjView(DdzPhase.Other, emptyList(), null)

    private fun counts(left: Int, right: Int) = mapOf(DdzSide.Left to left, DdzSide.Right to right)

    private fun observe(
        turn: DdzSeat?, hand: String, left: String = "", right: String = "", mine: String = "",
        badges: Map<DdzSide, Int>? = null, explosion: Boolean = false,
        phase: DdzPhase = if (turn == DdzSeat.Me) DdzPhase.Follow else DdzPhase.Other,
    ) {
        fun boxes(ranks: String, x: Int, y: Int, height: Int) = ranks.split(' ').filter { it.isNotEmpty() }.mapIndexed { i, rank ->
            val cx = x + 60.0 * i
            JjCard(rank, cx, y.toDouble(), cx + 36, (y + height).toDouble(), 0.99)
        }
        val table = JjCards.table(boxes(hand, 500, 772, 84) + boxes(left, 670, 270, 54) +
            boxes(right, 1630, 270, 54) + boxes(mine, 1100, 520, 54), 2412)
        if (hand.isEmpty()) JjDdzTurn.observeClearedTable(session, table, emptyView, badges, explosion)
        else JjDdzTurn.observe(session, table, JjView(phase, emptyList(), turn), counts = badges)
    }

    @Test fun myConfirmedRocketReturnsTheLeadOnAnEmptyTable() {
        // 16:04:08 dump 0–8: follow a straight, select both jokers. Four empty /cards replies
        // (dump 9–11), then six Lead frames (12–17) with the same 14 cards and no pass bubbles.
        val straight = "10 9 8 7 6 5 4 3"
        val before = moves("4/6/J/Q/A/2///9 9///5 5/7 7/J J///Q Q///$straight/")
        val after = "2 2 A K K Q J 10 9 8 8 8 6 3"
        tracker.restore(DdzSeat.Me, emptyList(), before, DdzSeat.Me)
        repeat(4) { observe(DdzSeat.Me, "BJ SJ $after", left = "PASS", right = straight) }
        tracker.mine(listOf("SJ", "BJ"), jjHandAfterPlay = after.split(' '))
        session.acted = true
        assertEquals(before + moves("SJ BJ//"), tracker.history)
        assertFalse(tracker.ready())
        repeat(4) { observe(null, "", explosion = it == 2) }
        repeat(6) { observe(DdzSeat.Me, after, phase = DdzPhase.Lead) }
        tracker.checkJjDecision(after.split(' '), leading = true)
        assertEquals(before + moves("SJ BJ//"), tracker.history)
        assertTrue("trace=${tracker.trace}", tracker.ready())
        assertFalse(session.acted)
    }

    @Test fun myRocketWaitsForThePostPlayHandAndLeadButtons() {
        val after = "2 2 A K K Q J 10 9 8 8 8 6 4 3"
        val before = "BJ SJ $after"
        tracker.restore(DdzSeat.Left, emptyList(), moves("A"), DdzSeat.Me)
        repeat(3) { observe(DdzSeat.Me, before, left = "A") }
        tracker.mine(listOf("SJ", "BJ"), jjHandAfterPlay = after.split(' '))
        repeat(3) { observe(DdzSeat.Me, before, phase = DdzPhase.Lead) }
        repeat(3) { observe(DdzSeat.Me, after.replace("4", "5"), phase = DdzPhase.Lead) }
        repeat(3) { observe(DdzSeat.Me, after, phase = DdzPhase.Follow) }
        assertEquals(moves("A/SJ BJ//"), tracker.history)
        assertFalse(tracker.ready())
        assertNull(tracker.broken)
    }

    @Test fun playingMyLastTwoCardsAsARocketDoesNotAppendPasses() {
        val before = moves("3 3 3 4 4 4 5 5 5 6 6 6 7 7 7 8 8 8//")
        tracker.restore(DdzSeat.Me, emptyList(), before, DdzSeat.Me)
        tracker.mine(listOf("BJ", "SJ"), jjHandAfterPlay = emptyList())
        assertEquals(before + listOf(listOf("BJ", "SJ")), tracker.history)
        assertFalse(tracker.awaitingRocketLead)
    }

    private fun replayJ(
        cards: String = hand, animation: Map<DdzSide, Int>? = counts(5, 9),
        after: Map<DdzSide, Int>? = counts(3, 9), prefix: List<List<String>> = beforeFour,
        otherPlay: String = "", explosion: Boolean = true,
    ) {
        tracker.restore(DdzSeat.Left, emptyList(), prefix, DdzSeat.Me)
        tracker.mine(listOf("4"))
        repeat(3) { observe(DdzSeat.Right, cards, mine = "4") }
        repeat(5) { observe(DdzSeat.Left, cards, right = "J", mine = "4") }
        assertEquals(prefix + moves("4/J"), tracker.history)
        repeat(3) { observe(null, "", badges = animation, explosion = explosion && it == 1) }
        repeat(7) { observe(DdzSeat.Me, cards, left = "7 7", right = otherPlay, badges = after) }
    }

    @Test fun hiddenRocketOverJRestoresBothAutoPassesAndThePairLead() {
        replayJ()
        assertEquals(beforeFour + moves("4/J/SJ BJ///7 7"), tracker.history)
        assertEquals(listOf(DdzSeat.Left, DdzSeat.Me, DdzSeat.Right, DdzSeat.Left), tracker.confirmedSeats.takeLast(4))
        assertTrue("trace=${tracker.trace}", tracker.ready())
    }

    @Test fun handResultIncludesRecoveredRocketButLeavesUnfinishedReplayUnscored() {
        replayJ()
        val result = JjHandLog(3).snapshot("replay", "mac.local", "hand_gap", tracker)
        assertEquals(3, result.hand)
        assertEquals("landlord_down", result.role)
        assertEquals("left", result.landlord)
        assertEquals(1, result.bombs)
        assertEquals(1, result.rockets)
        assertFalse(result.history_complete)
        assertNull(result.win)
        assertNull(result.spring)
        assertEquals("unknown", result.evidence)
    }

    @Test fun hiddenRocketAfterRightPassPreservesTheNextAutoPass() {
        val history = moves("4 4/K K///5 5 8 8 9 9 9 10 10 10//")
        tracker.restore(DdzSeat.Left, emptyList(), history, DdzSeat.Me)
        tracker.mine(moves("6 A A A").single())
        repeat(3) { observe(DdzSeat.Right, "2", mine = "A A A 6") }
        observe(DdzSeat.Left, "2", right = "PASS", mine = "A A A 6")
        repeat(5) { observe(null, "", badges = counts(16, 17), explosion = it == 3) }
        observe(DdzSeat.Left, "2", badges = counts(16, 17))
        repeat(2) { observe(DdzSeat.Right, "2", left = "3 3 3 6 6", mine = "PASS", badges = counts(11, 17)) }
        assertEquals(history + moves("6 A A A//SJ BJ///3 3 3 6 6"), tracker.history)
        observe(DdzSeat.Left, "2", right = "PASS", mine = "PASS")
        repeat(3) { observe(DdzSeat.Left, "2") }
        repeat(5) { observe(DdzSeat.Right, "2", left = "7 7", mine = "PASS") }
        assertNull("trace=${tracker.trace}", tracker.broken)
        assertEquals(history + moves("6 A A A//SJ BJ///3 3 3 6 6///7 7/"), tracker.history)
    }

    @Test fun rightCanAlsoLeadAgainAfterAHiddenRocket() {
        val ranks = "2 A K K Q Q J J 10 10 9 9 8 8 6 3"
        tracker.restore(DdzSeat.Left, emptyList(), moves("4/5"), DdzSeat.Right)
        observe(DdzSeat.Right, ranks, left = "4", mine = "5")
        repeat(3) { observe(null, "", badges = counts(19, 15), explosion = it == 1) }
        repeat(4) { observe(DdzSeat.Left, ranks, right = "7 7", badges = counts(19, 13)) }
        assertNull(tracker.broken)
        assertEquals(moves("4/5/SJ BJ///7 7"), tracker.history)
    }

    @Test fun fourMissingCardsDoNotIdentifyARocket() {
        replayJ(animation = counts(3, 9), after = counts(1, 9))
        assertFalse(tracker.ready())
        assertEquals(beforeFour + moves("4/J"), tracker.history)
    }

    @Test fun aJokerInHandPreventsInference() {
        replayJ(cards = hand.replace("K", "BJ"))
        assertFalse(tracker.ready())
    }

    @Test fun aJokerInHistoryPreventsInference() {
        replayJ(prefix = beforeFour.toMutableList().also { it[9] = listOf("SJ") })
        assertFalse(tracker.ready())
    }

    @Test fun unreadableBadgesLeaveTheGapUnresolved() {
        replayJ(animation = null, after = null)
        assertFalse(tracker.ready())
    }

    @Test fun theFinalBadgeCanAccountForBothPlaysWhenSmokeCoversTheStand() {
        replayJ(animation = null)
        assertEquals(beforeFour + moves("4/J/SJ BJ///7 7"), tracker.history)
        assertTrue(tracker.ready())
    }

    @Test fun aBlankTableAloneDoesNotIdentifyARocket() {
        replayJ(explosion = false)
        assertFalse(tracker.ready())
    }

    @Test fun anotherReadablePlayPreventsAutoPasses() {
        replayJ(otherPlay = "A")
        assertFalse(tracker.ready())
    }

    @Test fun postAnimationBadgeMustIncludeTheVisibleLead() {
        replayJ(after = counts(4, 9))
        assertFalse(tracker.ready())
    }

    @Test fun readsBothStandsThroughTheTwoRecordedAnimations() {
        // 两段实拍的立牌裁图，另留桌心两行实际采样的像素。
        val image = ImageIO.read(javaClass.getResourceAsStream("/ddz-jj/rocket-observations.png"))
        val expected = listOf(counts(7, 9), counts(5, 9), counts(3, 9), counts(16, 17), counts(11, 17))
        for ((i, badges) in expected.withIndex()) {
            val rows = (448..490 step 2).associateWith { y ->
                IntArray(2412).also { row ->
                    val srcY = i * 45 + y - 448
                    image.getRGB(0, srcY, 100, 1, row, 265, 100)
                    image.getRGB(100, srcY, 100, 1, row, 2090, 100)
                }
            }
            val fire = listOf(600, 675).mapIndexed { line, y ->
                y to IntArray(2412).also { row ->
                    for ((j, x) in (603 until 1809 step 4).withIndex()) row[x] = image.getRGB(j, i * 45 + 43 + line)
                }
            }
            val frame = RawFrame(2412, 1080, rows + fire)
            assertEquals("frame $i", badges, JjDdzScreen.counts(frame))
            assertEquals("fire $i", i == 1 || i == 3, JjDdzScreen.explosion(frame))
        }
    }
}
