package com.wangzan101.mika.core.cap.phone

/** A validated snapshot that can be installed atomically into a human session. */
internal data class DdzRecoveredSnapshot(
    val snapshot: DdzSnapshot,
    val tracker: DdzTracker,
)

internal data class DdzRecoveryFailure(val reason: String)

internal sealed interface DdzRecoveryResult {
    data class Valid(val value: DdzRecoveredSnapshot) : DdzRecoveryResult
    data class Invalid(val failure: DdzRecoveryFailure) : DdzRecoveryResult
}

/**
 * Validates and replays all visible moves in a snapshot before it is installed.
 * Hidden opponent cards are deliberately left unknown: only the supplied hand,
 * bottom cards, and confirmed history consume cards from the deck.
 */
internal object DdzSnapshotRecovery {
    private const val LANDLORD_HAND = 20
    private const val FARMER_HAND = 17
    private val ranks = DdzCards.RANKS
    private val order = DdzCards.ORDER

    fun recover(snapshot: DdzSnapshot): DdzRecoveryResult {
        val hand = snapshot.hand
        val bottom = snapshot.bottom
        val history = snapshot.history
        val initial = if (snapshot.seat == "landlord") LANDLORD_HAND else FARMER_HAND

        if (snapshot.phase !in setOf("call", "double", "lead", "follow")) return invalid("phase")
        if (snapshot.seat !in setOf("landlord", "landlord_up", "landlord_down")) return invalid("seat")
        if (bottom.size != 3) return invalid("bottom_count")
        if (hand.isEmpty() || hand.size > LANDLORD_HAND) return invalid("hand_count")
        if (snapshot.phase == "call" || snapshot.phase == "double") {
            if (history.isNotEmpty()) return invalid("history_phase")
            if (hand.size != initial) return invalid("hand_count")
        }

        val all = mutableListOf<String>()
        all += hand
        all += bottom
        history.forEach { all += it }
        val counts = all.groupingBy { it }.eachCount()
        if (counts.keys.any { it !in ranks }) {
            return invalid(if (counts.keys.any { it.startsWith("?") }) "ambiguous_card" else "invalid_card")
        }
        if (counts.any { (rank, count) -> count > copies(rank) }) return invalid("duplicate_card")

        val replay = replay(history) ?: return invalid("history_mismatch")
        val ownIndex = ownIndex(snapshot.seat)
        val ownPlayed = history.withIndex()
            .filter { (index, _) -> index % 3 == ownIndex }
            .sumOf { (_, move) -> move.size }
        if (hand.size > initial - ownPlayed) return invalid("hand_count")
        if (history.isNotEmpty() && ownPlayed > initial) return invalid("hand_count")

        val landlord = when (snapshot.seat) {
            "landlord" -> DdzSeat.Me
            "landlord_up" -> DdzSeat.Right
            else -> DdzSeat.Left
        }
        val tracker = DdzTracker(DdzSettlingPolicy.HUMAN).also { it.restore(landlord, bottom, history) }
        // A follow state must have an outstanding opponent play. Lead can be the
        // first move, so an empty history is valid only for a landlord snapshot.
        if (snapshot.phase == "follow" && replay.active == null) return invalid("history_phase")
        if (snapshot.phase == "lead" && replay.active != null) return invalid("history_phase")
        if (snapshot.phase == "lead" && history.isEmpty() && snapshot.seat != "landlord") return invalid("history_phase")
        return DdzRecoveryResult.Valid(DdzRecoveredSnapshot(snapshot, tracker))
    }

    private fun copies(rank: String) = if (rank == "SJ" || rank == "BJ") 1 else 4

    private fun ownIndex(seat: String) = when (seat) {
        "landlord" -> 0
        // History starts with the landlord and proceeds landlord -> right -> left.
        // In this ordering the player's turn is index 2 when the landlord is right,
        // and index 1 when the landlord is left.
        "landlord_up" -> 2
        else -> 1
    }

    private data class Replay(val active: Move?)

    private fun replay(history: List<List<String>>): Replay? {
        if (history.firstOrNull()?.isEmpty() == true) return null
        var active: Move? = null
        var passes = 0
        for (cards in history) {
            val move = classify(cards) ?: return null
            if (move.kind == Kind.Pass) {
                if (active == null) return null
                passes++
                if (passes == 2) {
                    active = null
                    passes = 0
                }
                continue
            }
            if (active != null && !beats(active, move)) return null
            active = move
            passes = 0
        }
        return Replay(active)
    }

    private enum class Kind { Pass, Single, Pair, Triple, Straight, PairSequence, Plane, PlaneSingles, PlanePairs, FourSingles, FourPairs, Bomb, Rocket }
    private data class Move(val kind: Kind, val length: Int, val main: Int)

    private fun classify(cards: List<String>): Move? {
        if (cards.isEmpty()) return Move(Kind.Pass, 0, -1)
        if (cards.any { it !in ranks }) return null
        val counts = cards.groupingBy { it }.eachCount()
        val sorted = counts.keys.map { order.getValue(it) }.sorted()
        if (cards.size == 2 && counts.keys == setOf("SJ", "BJ")) return Move(Kind.Rocket, 2, order.getValue("BJ"))
        if (counts.size == 1) {
            val rank = sorted.single()
            return when (cards.size) {
                1 -> Move(Kind.Single, 1, rank)
                2 -> Move(Kind.Pair, 2, rank)
                3 -> Move(Kind.Triple, 3, rank)
                4 -> Move(Kind.Bomb, 4, rank)
                else -> null
            }
        }
        if (cards.size >= 5 && counts.values.all { it == 1 } && consecutive(sorted) && sorted.last() < order.getValue("2")) {
            return Move(Kind.Straight, cards.size, sorted.last())
        }
        if (cards.size >= 6 && cards.size % 2 == 0 && counts.values.all { it == 2 } &&
            consecutive(sorted) && sorted.last() < order.getValue("2")) {
            return Move(Kind.PairSequence, cards.size, sorted.last())
        }
        if (cards.size >= 6 && cards.size % 3 == 0 && counts.values.all { it == 3 } &&
            consecutive(sorted) && sorted.last() < order.getValue("2")) {
            return Move(Kind.Plane, cards.size, sorted.last())
        }
        val tripleRanks = counts.filterValues { it >= 3 }.keys.map { order.getValue(it) }.sorted()
        for (run in consecutiveRuns(tripleRanks, minLength = 2)) {
            val n = run.size
            val tripleCards = n * 3
            val remainder = cards.size - tripleCards
            if (cards.size == tripleCards + n && counts.entries.sumOf { entry -> entry.value - (if (order.getValue(entry.key) in run) 3 else 0) } == n) {
                return Move(Kind.PlaneSingles, cards.size, run.last())
            }
            if (cards.size == tripleCards + n * 2) {
                val rest = counts.mapValues { (rank, count) -> count - (if (order.getValue(rank) in run) 3 else 0) }
                if (rest.values.all { it == 0 || it == 2 } && rest.values.sum() == n * 2) {
                    return Move(Kind.PlanePairs, cards.size, run.last())
                }
            }
            if (remainder == 2 && counts.entries.sumOf { entry -> entry.value - (if (order.getValue(entry.key) in run) 3 else 0) } == 2) {
                return Move(Kind.FourSingles, cards.size, run.last())
            }
            if (remainder == 4) {
                val rest = counts.mapValues { (rank, count) -> count - (if (order.getValue(rank) in run) 3 else 0) }
                if (rest.values.all { it == 0 || it == 2 } && rest.values.sum() == 4) {
                    return Move(Kind.FourPairs, cards.size, run.last())
                }
            }
        }
        return null
    }

    private fun consecutive(values: List<Int>) = values.zipWithNext().all { (a, b) -> b == a + 1 }

    private fun consecutiveRuns(values: List<Int>, minLength: Int): List<List<Int>> {
        if (values.isEmpty()) return emptyList()
        val runs = mutableListOf<List<Int>>()
        var start = 0
        for (i in 1..values.size) {
            if (i == values.size || values[i] != values[i - 1] + 1) {
                if (i - start >= minLength) {
                    for (from in start..i - minLength) for (to in from + minLength..i) runs += values.subList(from, to)
                }
                start = i
            }
        }
        return runs
    }

    private fun beats(previous: Move, current: Move): Boolean = when {
        current.kind == Kind.Rocket -> true
        previous.kind == Kind.Rocket -> false
        current.kind == Kind.Bomb && previous.kind != Kind.Bomb -> true
        previous.kind == Kind.Bomb && current.kind != Kind.Bomb -> false
        current.kind != previous.kind || current.length != previous.length -> false
        else -> current.main > previous.main
    }

    private fun invalid(reason: String) = DdzRecoveryResult.Invalid(DdzRecoveryFailure(reason))
}
