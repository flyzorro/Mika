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

        val landlord = when (snapshot.seat) {
            "landlord" -> DdzSeat.Me
            "landlord_up" -> DdzSeat.Right
            else -> DdzSeat.Left
        }
        val replay = replay(landlord, history) ?: return invalid("history_mismatch")
        val ownSeat = landlordToOwnSeat(landlord, snapshot.seat)
        val ownPlayed = replay.actors.zip(history).filter { it.first == ownSeat }.sumOf { it.second.size }
        if (hand.size != initial - ownPlayed) return invalid("hand_count")

        // The phase and supplied seat must describe the actor reconstructed from the full
        // history. A runnable recovery must never install a state for a different player.
        when (snapshot.phase) {
            "call", "double" -> if (history.isNotEmpty() || snapshot.seat != "landlord") return invalid("history_phase")
            "lead" -> {
                if (replay.active != null) return invalid("history_phase")
                if (replay.actor != ownSeat) return invalid("actor")
            }
            "follow" -> {
                if (replay.active == null) return invalid("history_phase")
                if (replay.actor != ownSeat) return invalid("actor")
            }
        }
        val tracker = DdzTracker(DdzSettlingPolicy.HUMAN).also { it.restore(landlord, bottom, history, replay.actor) }
        return DdzRecoveryResult.Valid(DdzRecoveredSnapshot(snapshot, tracker))
    }

    private fun copies(rank: String) = if (rank == "SJ" || rank == "BJ") 1 else 4

    private fun landlordToOwnSeat(landlord: DdzSeat, seat: String) = when (seat) {
        "landlord" -> landlord
        "landlord_up" -> landlord.next.next
        else -> landlord.next
    }

    private data class Replay(val active: Move?, val actor: DdzSeat, val actors: List<DdzSeat>)

    private fun replay(landlord: DdzSeat, history: List<List<String>>): Replay? {
        if (history.firstOrNull()?.isEmpty() == true) return null
        var active: Move? = null
        var leader: DdzSeat? = null
        var actor = landlord
        var passes = 0
        val actors = mutableListOf<DdzSeat>()
        for (cards in history) {
            val move = classify(cards) ?: return null
            actors += actor
            if (move.kind == Kind.Pass) {
                if (active == null) return null
                passes++
                actor = actor.next
                if (passes == 2) {
                    active = null
                    actor = leader ?: return null
                    leader = null
                    passes = 0
                }
                continue
            }
            if (active != null && !beats(active, move)) return null
            active = move
            leader = actor
            actor = actor.next
            passes = 0
        }
        return Replay(active, actor, actors)
    }

    internal enum class Kind { Pass, Single, Pair, Triple, TripleSingle, Straight, PairSequence, Plane, PlaneSingles, PlanePairs, FourSingles, FourPairs, Bomb, Rocket }
    internal data class Move(val kind: Kind, val length: Int, val main: Int)

    internal fun classify(cards: List<String>): Move? {
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
        // 三带一:三张同点 + 一张单牌,比三张的点数。牌桌上常见,认不出就会被当成坏牌
        if (cards.size == 4 && counts.size == 2 && counts.any { it.value == 3 }) {
            return Move(Kind.TripleSingle, 4, order.getValue(counts.entries.first { it.value == 3 }.key))
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

    internal fun beats(previous: Move, current: Move): Boolean = when {
        current.kind == Kind.Rocket -> true
        previous.kind == Kind.Rocket -> false
        current.kind == Kind.Bomb && previous.kind != Kind.Bomb -> true
        previous.kind == Kind.Bomb && current.kind != Kind.Bomb -> false
        current.kind != previous.kind || current.length != previous.length -> false
        else -> current.main > previous.main
    }

    private fun invalid(reason: String) = DdzRecoveryResult.Invalid(DdzRecoveryFailure(reason))
}
