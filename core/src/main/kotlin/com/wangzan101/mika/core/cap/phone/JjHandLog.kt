package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.screen.uistate.VisibleTextLine
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Scoped to the JJ drive loop, including HTTP retries on the IO dispatcher. */
internal class JjTimeoutCounter(val count: () -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<JjTimeoutCounter>
}

@Serializable
internal data class JjBidButton(val level: Int, val enabled: Boolean)

@Serializable
internal data class JjBid(val level: Int?, val requested: Int?, val source: String?, val buttons: List<JjBidButton>)

@Serializable
internal data class JjHandResult(
    val time: Long, val session: String, val hand: Int, val end: String,
    val role: String?, val landlord: String?, val bid: JjBid?,
    val win: Boolean?, val evidence: String, val winner: String?, val score_change: Int?,
    val stakes: String?, val multiplier: Double?, val stakes_source: String?,
    val bombs: Int, val rockets: Int, val spring: Boolean?, val history_complete: Boolean,
    val failures: Map<String, Int>, val tracker_breaks: Map<String, Int>,
    val advisor_host: String?, val advisor_reachable: Boolean?,
)

/** Only the last table JPEG and a possible payout JPEG survive the poll. Never OCR on a turn. */
internal class JjHandLog(val index: Int) {
    var endedAt: Long? = null
    var tableShot: ByteArray? = null
    var settlementShot: ByteArray? = null
    var stableLandlord: DdzSeat? = null
    var bid: JjBid? = null
    var advisorReachable: Boolean? = null
    val failures = mutableMapOf<String, Int>()
    val breaks = mutableMapOf<String, Int>()

    fun failure(key: String) { failures.merge(key, 1, Int::plus) }

    fun advisorReply(received: Boolean) {
        if (received || advisorReachable == null) advisorReachable = received
        if (!received) failure("advisor_no_reply")
    }

    fun snapshot(session: String, host: String?, end: String, tracker: DdzTracker?): JjHandResult {
        val lord = if (tracker?.broken == "landlord_mismatch") stableLandlord else tracker?.landlord ?: stableLandlord
        val history = tracker?.history.orEmpty()
        val completeWinner = tracker?.takeIf {
            it.broken == null && it.landlord != null && it.landlord == lord &&
                "tap_failed" !in failures && end !in listOf("stopped", "replaced", "redeal")
        }?.finishedSeat()
        val win = completeWinner?.let { (it == lord) == (lord == DdzSeat.Me) }
        val spring = completeWinner?.let { winner ->
            val played = tracker.confirmedSeats.filterIndexed { i, _ -> history[i].isNotEmpty() }
            if (winner == lord) played.all { it == lord } else played.count { it == lord } == 1
        }
        val kinds = history.map { DdzSnapshotRecovery.classify(it)?.kind }
        return JjHandResult(
            time = endedAt ?: System.currentTimeMillis(), session = session, hand = index, end = end,
            role = lord?.myRole(), landlord = lord?.name?.lowercase(), bid = bid,
            win = win, evidence = if (win == null) "unknown" else "tracker_zero",
            winner = completeWinner?.name?.lowercase(), score_change = null,
            stakes = null, multiplier = null, stakes_source = null,
            bombs = kinds.count { it == DdzSnapshotRecovery.Kind.Bomb },
            rockets = kinds.count { it == DdzSnapshotRecovery.Kind.Rocket },
            spring = spring, history_complete = completeWinner != null,
            failures = failures.toMap(), tracker_breaks = breaks.toMap(), advisor_host = host, advisor_reachable = advisorReachable,
        )
    }

    companion object {
        private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "jjhands").apply { isDaemon = true } }
        private val stake = Regex("""(?<![\d.])([1-9]\d{0,6})\s*[xX×хХ]\s*([1-9]\d*(?:\.\d+)?)(?![\d.])""")
        private val signedScore = Regex("""[+\-]\d{1,8}""")

        private fun List<VisibleTextLine>.texts(x: IntRange, y: IntRange) = filter { line ->
            val b = line.bounds
            b.size == 4 && b[0] in x && b[2] in x && b[1] in y && b[3] in y &&
                (line.confidence ?: 1f) >= 0.7f
        }.map { it.text.trim() }

        private fun stakes(lines: List<VisibleTextLine>) = lines.texts(400..750, 0..85)
            .flatMap { stake.findAll(it).map { m -> "${m.groupValues[1]}x${m.groupValues[2]}" }.toList() }.distinct().singleOrNull()

        internal fun enrich(record: JjHandResult, settlement: List<VisibleTextLine>, table: List<VisibleTextLine>): JjHandResult {
            // Own payout above the revealed hand, beach +10 (x≈.29, y≈.51), sea −450 (.31, .59).
            // Reject rank, balance, other seats and conflicting readings from the two OCR models.
            val score = settlement.texts(270..520, 475..685).map { it.filterNot(Char::isWhitespace).replace('−', '-').replace('－', '-').replace('＋', '+') }
                .filter { signedScore.matches(it) }.mapNotNull { it.toIntOrNull() }.distinct().singleOrNull()
            val paidWin = score?.takeUnless { it == 0 }?.let { it > 0 }
            val atEnd = stakes(settlement)
            val shown = atEnd ?: stakes(table)
            val conflict = paidWin != null && record.win != null && paidWin != record.win
            return record.copy(end = if (paidWin != null) "settlement" else record.end, win = paidWin ?: record.win, score_change = score,
                evidence = if (paidWin != null) "settlement_score" else record.evidence,
                winner = record.winner.takeUnless { conflict }, spring = record.spring.takeUnless { conflict },
                history_complete = record.history_complete && !conflict,
                stakes = shown, multiplier = shown?.substringAfter('x')?.toDoubleOrNull(),
                stakes_source = when { atEnd != null -> "settlement"; shown != null -> "last_table"; else -> null })
        }

        fun write(record: JjHandResult, payout: ByteArray?, lastTable: ByteArray?,
                  read: suspend (ByteArray) -> List<VisibleTextLine>?, dumpDirectory: () -> File?) {
            writer.execute {
                fun lines(shot: ByteArray?) = shot?.let {
                    runCatching { runBlocking { withTimeoutOrNull(4_000) { read(it) } } }.getOrNull()
                }.orEmpty()
                runCatching {
                    val settlement = lines(payout)
                    val table = if (payout === lastTable || stakes(settlement) != null) emptyList() else lines(lastTable)
                    val json = Json.encodeToString(enrich(record, settlement, table))
                    println("JJTRACE hand_result $json")
                    val dir = checkNotNull(dumpDirectory()?.parentFile) { "external files unavailable" }.resolve("jjhands")
                    check(dir.isDirectory || dir.mkdirs()) { "mkdir $dir" }
                    val date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(record.time))
                    dir.resolve("$date.jsonl").appendText("$json\n")
                }.onFailure { println("JJTRACE hand_log_error ${it.message}") }
            }
        }
    }
}
