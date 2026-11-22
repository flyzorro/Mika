package com.wangzan101.mika.core.cap.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JjTrackerReplayTest {
    @Test fun leftBombClearedBeforeTheClockReturnedStillCounts() {
        val history = moves("3/8/J/K/A//2/SJ//BJ///J J J 4 4//3 3 2 2 2///Q/A///6/Q//A///10 9 8 7 6///A")
        val tracker = replay("1212", DdzSeat.Right, DdzSeat.Left, history, myPassAt = 253)
        assertHistory(tracker, history + moves("5 5 5 5///9 9 9 4 4///3"))
    }

    @Test fun rightStraightAndTheTwoPassesSurviveTheClockGap() {
        val history = moves("6/10/A/2//SJ///8 9 10 J Q K///4 5 6 7 8")
        val tracker = replay("1259", DdzSeat.Right, DdzSeat.Right, history)
        assertHistory(tracker, history + moves("A K Q J 10///4 4/5 5"))
    }

    @Test fun rightBombOverBigJokerIsKeptBeforeTheNextLead() {
        val history = moves("7 6 5 4 3///7 7/J J//2 2///Q Q Q 3///A A A 10 10/8 8 8 8///7/A/BJ/")
        val tracker = replay("1520", DdzSeat.Left, DdzSeat.Right, history)
        assertHistory(tracker, history + moves("K K K K///6 6/"))
    }

    @Test fun overlappingRankReadAndOneRightClockDoNotSkipMyTurn() {
        val history = moves("8 8 7 7 6 6")
        val tracker = replay("1528", DdzSeat.Right, DdzSeat.Left, history)
        assertHistory(tracker, history + moves("J J 10 10 9 9"))
    }

    @Test fun aFifthThreeWaitsForTheOverlappingEightBeforeEnteringHistory() {
        val history = moves("3 3/4 4/8 8///10 9 8 7 6 5///Q Q Q 7")
        val tracker = DdzTracker(DdzSettlingPolicy.HUMAN).apply {
            restore(DdzSeat.Left, emptyList(), history, DdzSeat.Left)
        }
        val session = JjDdzTurn.Session("", 1).apply { this.tracker = tracker }
        val hand = moves("SJ 2 A K Q J J J 10 10 10 9 5 3 3").single().mapIndexed { i, rank ->
            JjCard(rank, 507.5 + i * 96, 771.9, 546.8 + i * 96, 849.3, 0.999)
        }
        // 16:51:07–11: K K K with a stronger 3 and an intermittent, overlapping 8.
        val kings = listOf(
            JjCard("K", 671.2, 271.0, 705.9, 323.4, 0.996),
            JjCard("K", 728.5, 271.3, 765.3, 323.6, 0.998),
            JjCard("K", 783.2, 270.9, 819.8, 323.3, 0.999),
        )
        val three = JjCard("3", 843.7, 271.6, 875.9, 324.3, 0.852)
        val eight = JjCard("8", 843.4, 270.9, 877.3, 324.1, 0.524)
        val view = JjView(DdzPhase.Follow, emptyList(), DdzSeat.Me)

        repeat(4) { JjDdzTurn.observe(session, JjCards.table(hand + kings + three, 2412), view) }
        assertEquals(history, tracker.history)

        repeat(3) { JjDdzTurn.observe(session, JjCards.table(hand + kings + three + eight, 2412), view) }
        assertHistory(tracker, history + moves("K K K 8"))
    }

    @Test fun anAlreadyHeldJokerWithoutAnAlternativeStaysUnsure() {
        val session = JjDdzTurn.Session("", 1)
        val table = JjCards.table(listOf(
            JjCard("SJ", 507.5, 771.9, 546.8, 954.3, 0.999),
            JjCard("SJ", 671.2, 271.0, 705.9, 323.4, 0.996),
        ), 2412)
        assertEquals(DdzSeen.Unsure, JjDdzTurn.steady(session, table, DdzSide.Left))
    }

    @Test fun aClearedPlayIsCheckedAgainAfterThePreviousPlayIsRecorded() {
        val tracker = DdzTracker(DdzSettlingPolicy.HUMAN).apply {
            restore(DdzSeat.Right, emptyList(), emptyList())
        }
        val session = JjDdzTurn.Session("", 1).apply { this.tracker = tracker }
        fun boxes(ranks: List<String>, x: Double, y: Double, height: Double) = ranks.mapIndexed { i, rank ->
            JjCard(rank, x + i * 57, y, x + i * 57 + 35, y + height, 0.99)
        }
        val hand = boxes(listOf("8", "8", "8"), 500.0, 772.0, 78.0)
        val right = boxes(listOf("K", "K", "K", "8"), 1450.0, 271.0, 53.0)
        val left = boxes(listOf("A", "A", "A", "8"), 670.0, 271.0, 53.0)
        val view = JjView(DdzPhase.Follow, emptyList(), DdzSeat.Me)
        repeat(2) { JjDdzTurn.observe(session, JjCards.table(hand + right + left, 2412), view) }
        repeat(5) { JjDdzTurn.observe(session, JjCards.table(hand + right, 2412), view) }
        assertEquals(moves("K K K 8"), tracker.history)
        assertNull(tracker.broken)
    }

    @Test fun anOvercallIsNotTreatedAsTwoPassesIfMyHandCouldBeatIt() {
        val history = moves("6/10/A/2//SJ///8 9 10 J Q K///4 5 6 7 8")
        val tracker = replay("1259", DdzSeat.Right, DdzSeat.Right, history, throughFrame = 74) { boxes ->
            boxes.map { if (it.cy >= 700) it.copy(rank = "3") else it } // A bomb could beat the straight.
        }
        assertEquals(history, tracker.history)
    }

    @Test fun aMissingOpponentPassDoesNotBecomeAPass() {
        val history = moves("6/10/A/2//SJ///8 9 10 J Q K///4 5 6 7 8")
        val tracker = replay("1259", DdzSeat.Right, DdzSeat.Right, history, throughFrame = 74) { boxes ->
            boxes.filterNot { it.rank == "PASS" && it.cy < 450 }
        }
        assertEquals(history, tracker.history)
    }

    @Test fun aPassAlreadyOnTheTableWhenTheStraightArrivesDoesNotCount() {
        val history = moves("6/10/A/2//SJ///8 9 10 J Q K///4 5 6 7 8")
        val tracker = replay("1259", DdzSeat.Right, DdzSeat.Right, history, throughFrame = 74) { boxes ->
            // Use the same shape and position as the actual pass on server log line 21141.
            boxes + JjCard("PASS", 669.0, 217.4, 792.2, 267.7, 0.999)
        }
        assertEquals(history, tracker.history)
    }

    private fun replay(
        incident: String,
        landlord: DdzSeat,
        active: DdzSeat,
        history: List<List<String>>,
        throughFrame: Int = Int.MAX_VALUE,
        myPassAt: Int? = null,
        changeBoxes: (List<JjCard>) -> List<JjCard> = { it },
    ): DdzTracker {
        val tracker = DdzTracker(DdzSettlingPolicy.HUMAN).apply { restore(landlord, emptyList(), history, active) }
        val session = JjDdzTurn.Session("", 1).apply { this.tracker = tracker }
        val lines = javaClass.getResourceAsStream("/ddz-jj/replay-$incident.tsv")!!.bufferedReader().use { it.readLines() }
        val hand = JjCards.parse(lines[1])!!
        for (line in lines.drop(2)) {
            val (logLine, frame, clock, boxes) = line.split('\t', limit = 4)
            val frameNo = frame.toInt()
            if (frameNo > throughFrame) break
            val turn = if (clock == "-") null else DdzSeat.valueOf(clock)
            val phase = if (turn == DdzSeat.Me) DdzPhase.Follow else DdzPhase.Other
            val table = JjCards.table(changeBoxes(hand + JjCards.parse(boxes)!!), 2412)
            JjDdzTurn.observe(session, table, JjView(phase, emptyList(), turn))
            assertNull("/cards line $logLine, frame $frame, trace=${tracker.trace}", tracker.broken)
            // The live fallback also tapped pass at this point. In the replay the tracker can record it.
            if (frameNo == myPassAt && tracker.ready()) tracker.mine(emptyList())
        }
        return tracker
    }

    private fun assertHistory(tracker: DdzTracker, expected: List<List<String>>) {
        assertEquals("trace=${tracker.trace}", expected, tracker.history)
        assertTrue("trace=${tracker.trace}", tracker.ready())
    }

    private fun moves(text: String) = text.split('/').map { if (it.isEmpty()) emptyList() else it.split(' ') }
}
