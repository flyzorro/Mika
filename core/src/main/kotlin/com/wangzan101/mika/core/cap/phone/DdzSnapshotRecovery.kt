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
        // 叫分时底牌还扣着,谁也看不见:模型给了什么(空的、或者编的三张)都不算数,不拿来核对张数
        val call = snapshot.phase == "call"
        val bottom = if (call) emptyList() else snapshot.bottom
        val history = snapshot.history

        if (snapshot.phase !in setOf("call", "double", "lead", "follow")) return invalid("phase")
        if (snapshot.seat !in setOf("landlord", "landlord_up", "landlord_down")) return invalid("seat")
        // 叫分时还没有地主,自己一定是 17 张;加倍时地主已经定了,地主 20 张、农民 17 张。
        val initial = if (call || snapshot.seat != "landlord") FARMER_HAND else LANDLORD_HAND
        if (!call && bottom.size != 3) return invalid("bottom_count")
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
            "call", "double" -> if (history.isNotEmpty()) return invalid("history_phase")
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
        return DdzRecoveryResult.Valid(DdzRecoveredSnapshot(snapshot.copy(bottom = bottom), tracker))
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

    internal enum class Kind { Pass, Single, Pair, Triple, TripleSingle, TriplePair, Straight, PairSequence, Plane, PlaneSingles, PlanePairs, FourSingles, FourPairs, Bomb, Rocket }
    internal data class Move(val kind: Kind, val length: Int, val main: Int)

    internal fun classify(cards: List<String>): Move? {
        if (cards.isEmpty()) return Move(Kind.Pass, 0, -1)
        if (cards.any { it !in ranks }) return null
        val counts = cards.groupingBy { it }.eachCount()
        val values = counts.keys.map { order.getValue(it) }.sorted()
        val size = cards.size
        if (size == 2 && counts.keys == setOf("SJ", "BJ")) return Move(Kind.Rocket, 2, order.getValue("BJ"))
        if (counts.size == 1) {
            val rank = values.single()
            return when (size) {
                1 -> Move(Kind.Single, 1, rank)
                2 -> Move(Kind.Pair, 2, rank)
                3 -> Move(Kind.Triple, 3, rank)
                4 -> Move(Kind.Bomb, 4, rank)
                else -> null
            }
        }
        // 三带一、三带二:比三张的点数
        if (size == 4) {
            val triple = counts.entries.singleOrNull { it.value == 3 } ?: return null
            return Move(Kind.TripleSingle, 4, order.getValue(triple.key))
        }
        if (size == 5 && counts.size == 2) {
            val triple = counts.entries.singleOrNull { it.value == 3 } ?: return null
            return Move(Kind.TriplePair, 5, order.getValue(triple.key))
        }
        // 顺子:至少 5 张连续单牌;2 和王连不上
        if (size >= 5 && counts.values.all { it == 1 } && consecutive(values) && values.last() < order.getValue("2")) {
            return Move(Kind.Straight, size, values.last())
        }
        // 连对:至少 3 对连续;2 和王连不上
        if (size >= 6 && counts.values.all { it == 2 } && values.size >= 3 &&
            consecutive(values) && values.last() < order.getValue("2")) {
            return Move(Kind.PairSequence, size, values.last())
        }
        // 飞机不带:至少 2 个连续三张;2 和王不能进机身
        if (size >= 6 && counts.values.all { it == 3 } && values.size >= 2 &&
            consecutive(values) && values.last() < order.getValue("2")) {
            return Move(Kind.Plane, size, values.last())
        }
        // 四带二(两张单牌或一对)、四带两对(两个对子,或两个炸弹):比四张的点数
        val quads = counts.entries.filter { it.value == 4 }.map { order.getValue(it.key) }.sorted()
        if (size == 6 && quads.size == 1) {
            val rest = counts.values.filter { it != 4 }.sorted()
            if (rest == listOf(2) || rest == listOf(1, 1)) return Move(Kind.FourSingles, 6, quads.single())
        }
        if (size == 8) {
            val rest = counts.values.filter { it != 4 }.sorted()
            if (quads.size == 1 && rest == listOf(2, 2)) return Move(Kind.FourPairs, 8, quads.single())
            if (quads.size == 2) return Move(Kind.FourPairs, 8, quads.last())
        }
        return planeWithWings(size, counts)
    }

    /**
     * 飞机带翅膀:全部三张连成机身,剩下的牌当翅膀;一个对子可以当两张单牌用(和 DouZero 一致)。
     * 四张的牌既不能当机身也不能当翅膀。
     */
    private fun planeWithWings(size: Int, counts: Map<String, Int>): Move? {
        if (counts.values.any { it !in 1..3 }) return null
        val triples = counts.filterValues { it == 3 }.keys.map { order.getValue(it) }.sorted()
        if (triples.size < 2 || triples.last() >= order.getValue("2")) return null
        if (!consecutive(triples)) {
            // 四组三张:三组连着的当机身,剩下那组的三张正好当三张单牌(不能另有翅膀)
            if (triples.size != 4 || size != triples.size * 3) return null
            return when {
                consecutive(triples.drop(1)) -> Move(Kind.PlaneSingles, size, triples[3])
                consecutive(triples.dropLast(1)) -> Move(Kind.PlaneSingles, size, triples[2])
                else -> null
            }
        }
        // 机身 k 个三张:翅膀正好 k 张单牌,或者 k 个对子
        if (size - triples.size * 3 == triples.size) return Move(Kind.PlaneSingles, size, triples.last())
        val pairs = counts.values.count { it == 2 }
        val singles = counts.values.count { it == 1 }
        if (singles == 0 && pairs == triples.size) return Move(Kind.PlanePairs, size, triples.last())
        return null
    }

    private fun consecutive(values: List<Int>) = values.zipWithNext().all { (a, b) -> b == a + 1 }

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
