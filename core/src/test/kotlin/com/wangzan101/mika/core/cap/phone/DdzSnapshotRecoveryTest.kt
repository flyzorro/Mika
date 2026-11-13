package com.wangzan101.mika.core.cap.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 牌型识别要覆盖斗地主的全部常见牌型,守卫才不会被合法的一手误判成认错。 */
class DdzSnapshotRecoveryTest {
    private fun classify(cards: String) = DdzSnapshotRecovery.classify(parse(cards))

    private fun parse(cards: String) = cards.trim().takeIf { it.isNotEmpty() }?.split(' ').orEmpty()

    private fun rank(rank: String) = DdzCards.ORDER.getValue(rank)

    private fun assertMove(cards: String, kind: DdzSnapshotRecovery.Kind, length: Int, main: Int) {
        val move = classify(cards) ?: throw AssertionError("没有认出来:$cards")
        assertEquals(cards, kind, move.kind)
        assertEquals(cards, length, move.length)
        assertEquals(cards, main, move.main)
    }

    @Test fun classifiesPass() = assertMove("", DdzSnapshotRecovery.Kind.Pass, 0, -1)

    @Test fun classifiesSingle() = assertMove("3", DdzSnapshotRecovery.Kind.Single, 1, rank("3"))

    @Test fun classifiesPair() = assertMove("4 4", DdzSnapshotRecovery.Kind.Pair, 2, rank("4"))

    @Test fun classifiesTriple() = assertMove("5 5 5", DdzSnapshotRecovery.Kind.Triple, 3, rank("5"))

    @Test fun classifiesTripleSingle() = assertMove("6 6 6 4", DdzSnapshotRecovery.Kind.TripleSingle, 4, rank("6"))

    @Test fun classifiesTriplePair() = assertMove("6 6 6 4 4", DdzSnapshotRecovery.Kind.TriplePair, 5, rank("6"))

    @Test fun classifiesStraight() = assertMove("3 4 5 6 7", DdzSnapshotRecovery.Kind.Straight, 5, rank("7"))

    @Test fun classifiesPairSequence() = assertMove("3 3 4 4 5 5", DdzSnapshotRecovery.Kind.PairSequence, 6, rank("5"))

    @Test fun classifiesPlane() = assertMove("3 3 3 4 4 4", DdzSnapshotRecovery.Kind.Plane, 6, rank("4"))

    @Test fun classifiesPlaneSingles() =
        assertMove("3 3 3 4 4 4 5 6", DdzSnapshotRecovery.Kind.PlaneSingles, 8, rank("4"))

    @Test fun classifiesPlaneSinglesWithAPairAsTwoWings() =
        assertMove("3 3 3 4 4 4 5 5", DdzSnapshotRecovery.Kind.PlaneSingles, 8, rank("4"))

    @Test fun classifiesPlanePairs() =
        assertMove("3 3 3 4 4 4 5 5 6 6", DdzSnapshotRecovery.Kind.PlanePairs, 10, rank("4"))

    @Test fun classifiesFourSingles() =
        assertMove("6 6 6 6 4 5", DdzSnapshotRecovery.Kind.FourSingles, 6, rank("6"))

    @Test fun classifiesFourSinglesWithAPair() =
        assertMove("6 6 6 6 4 4", DdzSnapshotRecovery.Kind.FourSingles, 6, rank("6"))

    @Test fun classifiesFourPairs() =
        assertMove("6 6 6 6 4 4 5 5", DdzSnapshotRecovery.Kind.FourPairs, 8, rank("6"))

    @Test fun classifiesTwoQuadsAsFourPairs() =
        assertMove("6 6 6 6 4 4 4 4", DdzSnapshotRecovery.Kind.FourPairs, 8, rank("6"))

    @Test fun classifiesBomb() = assertMove("7 7 7 7", DdzSnapshotRecovery.Kind.Bomb, 4, rank("7"))

    @Test fun classifiesRocket() = assertMove("SJ BJ", DdzSnapshotRecovery.Kind.Rocket, 2, rank("BJ"))

    @Test fun rejectsShapesThatAreNotPlayable() {
        for (cards in listOf(
            "3 4",                  // 两张不同点
            "3 4 5 6",              // 顺子至少 5 张
            "10 J Q K A 2",         // 顺子不能带 2
            "3 3 4 4",              // 连对至少 3 对
            "3 3 3 5 5 5",          // 三张不连续
            "3 3 3 3 4 4 4 5",      // 飞机里不能有四张
            "3 3 3 3 4 4 5",        // 四带二对必须是两对
        )) assertNull(cards, classify(cards))
    }

    @Test fun fourTriplesWithOneOutOfSequenceUsesThreeAsTheBody() {
        // DouZero 的规则:四组三张里三组连着,剩下那组三张当三张单牌
        assertMove("3 3 3 4 4 4 5 5 5 9 9 9", DdzSnapshotRecovery.Kind.PlaneSingles, 12, rank("5"))
    }

    @Test fun beatsComparesTheSamePatternAndRank() {
        val low = classify("6 6 6 4 4")!!
        val high = classify("8 8 8 7 7")!!
        assertTrue(DdzSnapshotRecovery.beats(low, high))
        assertFalse(DdzSnapshotRecovery.beats(high, low))
        assertFalse(DdzSnapshotRecovery.beats(high, high))
        val bomb = classify("3 3 3 3")!!
        assertTrue(DdzSnapshotRecovery.beats(high, bomb))
        assertFalse(DdzSnapshotRecovery.beats(bomb, high))
        val rocket = classify("SJ BJ")!!
        assertTrue(DdzSnapshotRecovery.beats(bomb, rocket))
        assertFalse(DdzSnapshotRecovery.beats(rocket, bomb))
    }

    @Test fun theLiveTwentyThreeMoveHandIsLegalExceptTheMisreadRocket() {
        // 2026-11-12 实测一局(地主是右家,顺序 右→左→我,下标从 0 数):
        // 第 18 手右家真正出的是王炸,出牌区读成单张 BJ;其余每一手都合牌规。
        val history = listOf(
            "6 6 6 4 4",
            "8 8 8 7 7",
            "",
            "10 10 10 9 9",
            "",
            "J J J Q Q",
            "",
            "",
            "9 9",
            "Q Q",
            "2 2",
            "",
            "3 3 3 3",
            "A A A A",
            "",
            "",
            "5 5 5 6",
            "",
            "BJ",
            "",
            "",
            "2",
            "",
        )
        var active: DdzSnapshotRecovery.Move? = null
        var passes = 0
        val illegal = mutableListOf<Int>()
        for ((index, cards) in history.withIndex()) {
            val move = classify(cards)
            if (move == null) { illegal += index; continue }
            if (move.kind == DdzSnapshotRecovery.Kind.Pass) {
                if (active != null) {
                    passes++
                    if (passes == 2) { active = null; passes = 0 }
                }
                continue
            }
            val lead = active
            if (lead != null && !DdzSnapshotRecovery.beats(lead, move)) {
                illegal += index
                continue
            }
            active = move
            passes = 0
        }
        assertEquals(listOf(18), illegal)
    }

    /** 叫分时的一副 17 张手牌;底牌还扣着,所以快照可以不带底牌。 */
    private val callHand = listOf("BJ", "SJ", "2", "A", "K", "Q", "J", "J", "10", "10", "9", "8", "8", "8", "7", "5", "3")

    @Test fun callSnapshotWithoutBottomCardsIsAccepted() {
        val result = DdzSnapshotRecovery.recover(DdzSnapshot(callHand, emptyList(), emptyList(), "call", "landlord"))
        assertTrue(result.toString(), result is DdzRecoveryResult.Valid)
    }

    @Test fun callSnapshotAcceptsAnySeat() {
        for (seat in listOf("landlord", "landlord_up", "landlord_down")) {
            val result = DdzSnapshotRecovery.recover(
                DdzSnapshot(callHand, listOf("3", "4", "5"), emptyList(), "call", seat),
            )
            assertTrue("$seat: $result", result is DdzRecoveryResult.Valid)
        }
    }

    @Test fun callSnapshotAcceptsThreeBottomCards() {
        val result = DdzSnapshotRecovery.recover(
            DdzSnapshot(callHand, listOf("3", "4", "5"), emptyList(), "call", "landlord"),
        )
        assertTrue(result.toString(), result is DdzRecoveryResult.Valid)
    }

    @Test fun callSnapshotWithTwentyCardsIsRejected() {
        val hand = listOf(
            "3", "3", "3", "3", "4", "4", "4", "4", "5", "5", "5", "5",
            "6", "6", "6", "6", "7", "7", "7", "7",
        )
        val result = DdzSnapshotRecovery.recover(DdzSnapshot(hand, listOf("8", "9", "10"), emptyList(), "call", "landlord"))
        assertTrue(result is DdzRecoveryResult.Invalid)
        assertEquals("hand_count", (result as DdzRecoveryResult.Invalid).failure.reason)
    }

    @Test fun doubleSnapshotAcceptsAFarmer() {
        val result = DdzSnapshotRecovery.recover(
            DdzSnapshot(callHand, listOf("3", "4", "5"), emptyList(), "double", "landlord_up"),
        )
        assertTrue(result.toString(), result is DdzRecoveryResult.Valid)
    }

    @Test fun doubleSnapshotKeepsTheLandlordHandRules() {
        val landlord = listOf(
            "3", "3", "3", "3", "4", "4", "4", "4", "5", "5", "5", "5",
            "6", "6", "6", "6", "7", "7", "7", "7",
        )
        val accepted = DdzSnapshotRecovery.recover(
            DdzSnapshot(landlord, listOf("8", "9", "10"), emptyList(), "double", "landlord"),
        )
        assertTrue(accepted.toString(), accepted is DdzRecoveryResult.Valid)
        val rejected = DdzSnapshotRecovery.recover(
            DdzSnapshot(callHand, listOf("3", "4", "5"), emptyList(), "double", "landlord"),
        )
        assertTrue(rejected is DdzRecoveryResult.Invalid)
        assertEquals("hand_count", (rejected as DdzRecoveryResult.Invalid).failure.reason)
    }
}
