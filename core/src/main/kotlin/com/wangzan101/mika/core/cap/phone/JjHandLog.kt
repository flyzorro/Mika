package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.screen.uistate.RawFrame
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
    val advisor_host: String?, val advisor_reachable: Boolean?, val ocr_ms: Long? = null,
    val end_frames: Int = 0, val end_probes: Int = 0, val ocr_frames: Int = 0,
)

/** A three-second window of original game JPEGs. The ordinary poll and the sampler both
 * feed it, so a slow /cards response cannot consume the whole payout interval. */
internal class JjEndFrames {
    companion object {
        const val INTERVAL_MS = 200L
        private const val WINDOW_MS = 3_000L
        private const val MAX_FRAMES = 16
        private const val OCR_CANDIDATES = 5
    }

    private val frames = mutableListOf<ByteArray>()
    private var started: Long? = null
    private var lastAt: Long? = null
    private var dealVisible = false
    private var preferred: ByteArray? = null
    private var resultPage: ByteArray? = null
    private val zeroSeats = mutableSetOf<DdzSeat>()
    val winner @Synchronized get() = zeroSeats.singleOrNull()
    val sawResult @Synchronized get() = resultPage != null
    @Volatile var probes = 0
    @Volatile var captured = 0
        private set

    @Synchronized fun ownHandCleared() { zeroSeats += DdzSeat.Me }

    /** A rocket or another effect hid the cards; the main loop has recognized the same hand again. */
    @Synchronized fun resumed(at: Long) {
        if (sawResult || started == null || lastAt?.let { it > at } == true) return
        frames.clear()
        zeroSeats.clear()
        preferred = null
        started = null
        lastAt = null
        dealVisible = false
    }

    @Synchronized fun samplingFinished(now: Long) = dealVisible || sawResult || started?.let { now - it > WINDOW_MS } == true

    /** Return whether this observation needs a JPEG. A probe checks eligibility before encoding;
     * normal polls and completed captures supply their existing bytes. */
    @Synchronized fun observe(
        frame: RawFrame, view: JjView, counts: Map<DdzSide, Int>?, at: Long, empty: Boolean, jpeg: ByteArray?,
    ): Boolean {
        if (lastAt?.let { it > at } == true) return false
        if (view.phase == DdzPhase.Result) {
            if (resultPage == null && jpeg != null) {
                resultPage = jpeg
                captured++
                if (JjDdzScreen.payoutVisible(frame)) preferred = jpeg
            }
            return resultPage == null
        }
        if (sawResult) return false
        if (view.phase != DdzPhase.Other || dealVisible) return false
        val zeros = counts.orEmpty().filterValues { it == 0 }.keys
        val hint = view.turn == null && JjDdzScreen.payoutVisible(frame)
        if (zeros.isEmpty() && !hint) {
            if (JjDdzScreen.handAtBottom(frame) && !empty) {
                // Keep the previous window until the game loop identifies the deal or resumes it.
                dealVisible = started != null
                return false
            }
            if (view.turn != null && !empty) return false
        }
        for (side in zeros) zeroSeats += if (side == DdzSide.Left) DdzSeat.Left else DdzSeat.Right
        if (started == null) started = at
        if (samplingFinished(at) || frames.size >= MAX_FRAMES) return false
        if (lastAt?.let { at - it < INTERVAL_MS } == true && !(hint && preferred == null)) return false
        if (jpeg != null) {
            frames += jpeg
            captured++
            lastAt = at
            if (hint && preferred == null) preferred = jpeg
        }
        return true
    }

    @Synchronized fun samples() = frames + listOfNotNull(resultPage)

    /** Reserve room for the visual hint and Result, then sample the middle of each remaining group. */
    @Synchronized fun candidates(): List<ByteArray> {
        val priority = listOfNotNull(preferred, resultPage).distinct()
        val rest = frames.filterNot { it in priority }
        val slots = minOf(OCR_CANDIDATES - priority.size, rest.size)
        val spaced = (0 until slots).map { rest[(2 * it + 1) * rest.size / (2 * slots)] }
        return (listOfNotNull(preferred) + spaced + listOfNotNull(resultPage)).distinct()
    }
}

internal class JjHandLog(val index: Int) {
    var endedAt: Long? = null
    var tableShot: ByteArray? = null
    val ending = JjEndFrames()
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
        val badgeWinner = ending.winner.takeUnless { end in listOf("stopped", "replaced", "redeal") }
        val winner = badgeWinner ?: completeWinner
        val win = when {
            winner == DdzSeat.Me -> true
            winner == null || lord == null -> null
            else -> (winner == lord) == (lord == DdzSeat.Me)
        }
        val historyComplete = completeWinner != null && completeWinner == winner
        val spring = completeWinner?.let { confirmed ->
            if (confirmed != winner) return@let null
            val played = tracker.confirmedSeats.filterIndexed { i, _ -> history[i].isNotEmpty() }
            if (confirmed == lord) played.all { it == lord } else played.count { it == lord } == 1
        }
        val kinds = history.map { DdzSnapshotRecovery.classify(it)?.kind }
        return JjHandResult(
            time = endedAt ?: System.currentTimeMillis(), session = session, hand = index, end = end,
            role = lord?.myRole(), landlord = lord?.name?.lowercase(), bid = bid,
            win = win, evidence = when {
                badgeWinner != null -> "badge_zero"
                win != null -> "tracker_zero"
                else -> "unknown"
            },
            winner = winner?.name?.lowercase(), score_change = null,
            stakes = null, multiplier = null, stakes_source = null,
            bombs = kinds.count { it == DdzSnapshotRecovery.Kind.Bomb },
            rockets = kinds.count { it == DdzSnapshotRecovery.Kind.Rocket },
            spring = spring, history_complete = historyComplete, end_frames = ending.captured, end_probes = ending.probes,
            failures = failures.toMap(), tracker_breaks = breaks.toMap(), advisor_host = host, advisor_reachable = advisorReachable,
        )
    }

    companion object {
        private val format = Json { encodeDefaults = true }
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

        internal fun enrich(record: JjHandResult, lines: List<VisibleTextLine>, settlement: Boolean): JjHandResult {
            // 16:26:15 harbour −600 starts at x=.247; 16:27:40 yacht −300 at .189.
            // Both end above y=.68; the signed total below and opponent payouts above are separate.
            // Reject rank, balance, other seats and conflicting readings from the two OCR models.
            val score = lines.texts(175..520, 475..685).map { it.filterNot(Char::isWhitespace).replace('−', '-').replace('－', '-').replace('＋', '+') }
                .filter { signedScore.matches(it) }.mapNotNull { it.toIntOrNull() }.distinct().singleOrNull().takeIf { settlement }
            val paidWin = score?.takeUnless { it == 0 }?.let { it > 0 }
            val shown = stakes(lines)
            val conflict = paidWin != null && record.win != null && paidWin != record.win
            return record.copy(end = if (paidWin != null) "settlement" else record.end, win = paidWin ?: record.win, score_change = score,
                evidence = if (paidWin != null) "settlement_score" else record.evidence,
                winner = record.winner.takeUnless { conflict }, spring = record.spring.takeUnless { conflict },
                history_complete = record.history_complete && !conflict,
                stakes = shown, multiplier = shown?.substringAfter('x')?.toDoubleOrNull(),
                stakes_source = shown?.let { if (settlement) "settlement" else "last_table" })
        }

        fun write(record: JjHandResult, candidates: List<ByteArray>, lastTable: ByteArray?,
                  read: suspend (ByteArray) -> List<VisibleTextLine>?, dumpDirectory: () -> File?,
                  dumpUnknown: () -> Unit) {
            writer.execute {
                runCatching {
                    var result = record
                    var attempts = 0
                    var elapsed = 0L
                    fun ocr(shot: ByteArray): List<VisibleTextLine> {
                        attempts++
                        val started = System.nanoTime()
                        return try {
                            runCatching { runBlocking { withTimeoutOrNull(4_000) { read(shot) } } }.getOrNull().orEmpty()
                        } finally {
                            elapsed += System.nanoTime() - started
                        }
                    }
                    if (record.end !in listOf("stopped", "replaced", "redeal")) {
                        for (shot in candidates) {
                            val lines = ocr(shot)
                            if (shot === lastTable) result = enrich(record, lines, settlement = false)
                            val candidate = enrich(record, lines, settlement = true)
                            if (candidate.evidence == "settlement_score") {
                                result = candidate
                                break
                            }
                        }
                        if (result.evidence != "settlement_score" && lastTable != null && lastTable !in candidates) {
                            result = enrich(record, ocr(lastTable), settlement = false)
                        }
                    }
                    result = result.copy(ocr_ms = elapsed.takeIf { attempts > 0 }?.div(1_000_000), ocr_frames = attempts)
                    if (result.win == null) dumpUnknown()
                    val json = format.encodeToString(result)
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
