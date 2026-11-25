package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.screen.uistate.RawFrame
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JjOpponentTurnsReplayTest {
    private fun moves(text: String) = text.split('/').map { if (it.isEmpty()) emptyList() else it.split(' ') }

    // History through the last confirmed action, reconstructed from the saved /act requests and trace.
    private val beforeAces = "4/5/K/2//SJ///4 6 7 7 7 8 8 8///3 3 10 10 10/J J J 6 6///2 2///9 9 9 K///A A///5 5"
    private val beforeJoker = "5 5//9 9/J J//Q Q///Q/2//SJ///5 5 6 6 7 7///A/2"
    private val beforeBomb = "3 3/9 9/10 10/J J//K K/A A///9//K///3/K/2///4/9/2/SJ///7//2///7 6 5 4 3///Q Q Q 4/"
    private val beforePairs = "3 3/4 4/A A/2 2///5 5//8 8/A A//"
    private val beforeThrees = "4 4/Q Q//"
    private val beforeKings = "5 5//A A///3 4 5 6 7 8 9 10///3/6/8/2///Q Q//"

    @Test fun silentPassWithOneSixPreservesTwosAndTheNextNines() {
        val tracker = replaySilentPass(beforeKings, listOf("6"))
        assertNull("trace=${tracker.trace}", tracker.broken)
        assertEquals(moves("$beforeKings/K K/2 2///9 9"), tracker.history)
        assertEquals(listOf(DdzSeat.Right, DdzSeat.Left, DdzSeat.Me, DdzSeat.Right), tracker.confirmedSeats.takeLast(4))
    }

    @Test fun silentPassIsNotInferredWhenMyRocketCanBeatTheTwos() {
        // A seven-card straight earlier leaves two cards now. Both jokers are still available.
        val before = beforeKings.replace("9 10", "9")
        val tracker = replaySilentPass(before, listOf("BJ", "SJ"))
        assertFalse(tracker.confirmedSeats.drop(moves(before).size + 1).contains(DdzSeat.Me))
    }

    @Test fun clockLeavingMeDoesNotPassMyHigherPairOnASettledTrick() {
        val before = moves(beforeKings.replace("9 10", "9") + "/7 7/9 9/")
        val tracker = DdzTracker(DdzSettlingPolicy.HUMAN).apply { restore(DdzSeat.Right, emptyList(), before, DdzSeat.Me) }
        val session = JjDdzTurn.Session("", 1).also { it.tracker = tracker }
        val hand = listOf(1100.0, 1196.0).map { x -> JjCard("J", x, 770.0, x + 40, 850.0, 0.99) }
        repeat(3) {
            JjDdzTurn.observe(session, JjCards.table(hand, 2412), JjView(DdzPhase.Other, emptyList(), DdzSeat.Right))
        }
        assertEquals(before, tracker.history)
    }

    private fun replaySilentPass(before: String, hand: List<String>): DdzTracker {
        val tracker = DdzTracker(DdzSettlingPolicy.HUMAN)
        tracker.restore(DdzSeat.Right, emptyList(), moves(before), DdzSeat.Me)
        tracker.mine(listOf("K", "K"), jjHandAfterPlay = hand)
        val session = JjDdzTurn.Session("", 1).also {
            it.tracker = tracker
            it.expectedHand = hand
        }
        val frames = javaClass.getResourceAsStream("/ddz-jj/replay-162209.tsv")!!.bufferedReader().use { it.readLines() }
        for (line in frames.drop(1)) {
            val (frame, clock, left, right, boxes) = line.split('\t')
            val turn = clock.takeUnless { it == "-" }?.let(DdzSeat::valueOf)
            val cards = JjCards.parse(boxes)!!.flatMap { card ->
                if (card.cy < 700) listOf(card) else hand.mapIndexed { i, rank ->
                    card.copy(rank = rank, x0 = card.x0 + 96 * i, x1 = card.x1 + 96 * i)
                }
            }
            JjDdzTurn.observe(session, JjCards.table(cards, 2412), JjView(DdzPhase.Other, emptyList(), turn),
                counts = mapOf(DdzSide.Left to left.toInt(), DdzSide.Right to right.toInt()))
            if (frame == "14") assertEquals(moves("$before/K K"), tracker.history)
        }
        return tracker
    }

    @Test fun leftAcesAndBothPassesSurviveTheClockGap() {
        val tracker = replay("124355", beforeAces, DdzSeat.Left)
        assertEquals(moves("$beforeAces/A A///4"), tracker.history)
        assertTrue(tracker.ready())
    }

    @Test fun singleFrameBigJokerIsConfirmedByBothBadgeDrops() {
        val tracker = replay("133156", beforeJoker, DdzSeat.Left)
        assertEquals(moves("$beforeJoker/BJ///7"), tracker.history)
        assertTrue(tracker.ready())
    }

    @Test fun rightBombAndNewPairSurviveMyStalePassBubble() {
        val tracker = replay("133504", beforeBomb, DdzSeat.Right, landlord = DdzSeat.Left)
        assertEquals(moves("$beforeBomb/8 8 8 8///7 7/"), tracker.history)
        assertTrue(tracker.ready())
    }

    @Test fun wrappedFivePairsSurviveTheTractorAndTheNextLead() {
        val tracker = replay("130135", beforePairs, DdzSeat.Right)
        val expected = moves("$beforePairs/K K Q Q J J 10 10 9 9//")
        assertEquals(expected, tracker.history)
    }

    @Test fun myFourthThreeResolvesTheRepeatedWeakKicker() {
        val tracker = replay("135332", beforeThrees, DdzSeat.Left)
        assertEquals(moves("$beforeThrees/3 3 3 8"), tracker.history)
        assertTrue(tracker.ready())
    }

    @Test fun lateJackCanSettleWithTheAlreadyRepeatedAceAtTheDeadline() {
        val before = "8 7 6 5 4 3/Q J 10 9 8 7///3 3//2 2//"
        val tracker = replay("134423", before, DdzSeat.Right, deadline = "72191")
        assertEquals(moves("$before/J/A"), tracker.history)
        assertTrue(tracker.ready())
    }

    @Test fun bothPlausibleKickersStayUnresolved() {
        val tracker = replay("135332", beforeThrees, DdzSeat.Left) { cards ->
            cards.map { if (it.cy > 700 && it.rank == "3") it.copy(rank = "7") else it }
        }
        assertEquals(moves(beforeThrees), tracker.history)
        assertFalse(tracker.ready())
    }

    @Test fun aThirdLabelOnTheWeakKickerAlsoCountsAsAnAlternative() {
        val tracker = replay("135332", beforeThrees, DdzSeat.Left) { cards ->
            val eight = cards.firstOrNull { it.cy < 450 && it.rank == "8" }
            if (eight == null) cards else cards + eight.copy(rank = "4", score = 0.51)
        }
        assertEquals(moves(beforeThrees), tracker.history)
        assertFalse(tracker.ready())
    }

    @Test fun aBadgeDropWithoutTheOtherPassCannotCompleteTheTrick() {
        val tracker = replay("133156", beforeJoker, DdzSeat.Left) { cards ->
            cards.filterNot { it.rank == "PASS" && it.cx > 1400 }
        }
        assertEquals(moves(beforeJoker), tracker.history)
        assertFalse(tracker.ready())
    }

    @Test fun aJokerFlashWithoutFinalBadgeEvidenceIsInsufficient() {
        val tracker = replay("133156", beforeJoker, DdzSeat.Left, omitFinalCount = true)
        assertEquals(moves(beforeJoker), tracker.history)
        assertFalse(tracker.ready())
    }

    private fun replay(
        case: String, before: String, seat: DdzSeat, landlord: DdzSeat = DdzSeat.Right,
        omitFinalCount: Boolean = false, deadline: String? = null, changeBoxes: (List<JjCard>) -> List<JjCard> = { it },
    ): DdzTracker {
        val tracker = DdzTracker(DdzSettlingPolicy.HUMAN).apply { restore(landlord, emptyList(), moves(before), seat) }
        val session = JjDdzTurn.Session("", 1).also { it.tracker = tracker }
        val lines = javaClass.getResourceAsStream("/ddz-jj/opponent-turns-1125.tsv")!!.bufferedReader()
            .useLines { lines -> lines.filter { it.startsWith("$case\t") }.toList() }
        val hand = JjCards.parse(lines.first().substringAfterLast('\t'))!!
        val badges = ImageIO.read(javaClass.getResourceAsStream("/ddz-jj/opponent-counts-1125.png"))
        val clock = PhoneDdzTurn.clock
        try {
            for (line in lines.drop(1)) {
                val fields = line.split('\t')
                PhoneDdzTurn.clock = { fields[2].toLong() }
                val turn = fields[3].takeUnless { it == "-" }?.let(DdzSeat::valueOf)
                val row = fields[4].toIntOrNull()
                val counts = row?.let {
                    val rows = (448..490 step 2).associateWith { y ->
                        IntArray(2412).also { pixels ->
                            val src = it * 43 + y - 448
                            badges.getRGB(0, src, 100, 1, pixels, 265, 100)
                            badges.getRGB(100, src, 100, 1, pixels, 2090, 100)
                        }
                    }
                    JjDdzScreen.counts(RawFrame(2412, 1080, rows))
                }
                val table = JjCards.table(changeBoxes(hand + JjCards.parse(fields.last())!!), 2412)
                val phase = if (turn == DdzSeat.Me) DdzPhase.Follow else DdzPhase.Other
                JjDdzTurn.observe(session, table, JjView(phase, emptyList(), turn), settleExpired = fields[1] == deadline,
                    counts = counts.takeUnless { omitFinalCount && table.left == DdzSeen.Cards(listOf("7")) })
                assertNull("log=${fields[1]} trace=${tracker.trace}", tracker.broken)
            }
        } finally {
            PhoneDdzTurn.clock = clock
        }
        return tracker
    }
}
