package com.wangzan101.mika.core.cap.phone

/** The explicit automation mode selected for a phone斗地主 session. */
internal enum class DdzMode(val wire: String) {
    Single("single"),
    Human("human"),
}

/** Observable lifecycle states for the phone tool's active session. */
internal enum class DdzSessionState(val wire: String) {
    Idle("idle"),
    Lobby("lobby"),
    Calling("calling"),
    Doubling("doubling"),
    Playing("playing"),
    Result("result"),
    Paused("paused"),
    Stopped("stopped"),
}

/** Stable reasons callers can use to decide whether to inspect or recover a session. */
internal object DdzSessionReason {
    const val USER_STOP = "user_stop"
    const val INVALID_SNAPSHOT = "invalid_snapshot"
    const val UNKNOWN_SCENE = "unknown_scene"
    const val REJECTED_ACTION = "rejected_action"
    const val HISTORY_MISMATCH = "history_mismatch"
    const val HAND_DRIFT = "hand_drift"
    const val SERVICE_UNAVAILABLE = "service_unavailable"
    const val APP_SWITCH = "app_switch"
    const val LOBBY_RETURN = "lobby_return"
    const val UNKNOWN_POPUP = "unknown_popup"
    const val INVALID_ADVISOR_PLAY = "invalid_advisor_play"
    const val SNAPSHOT_MISMATCH = "snapshot_mismatch"
    const val INVALID_ACTOR = "actor"
}

/** A deliberately small seam for policy decisions that happen before a model play call. */
internal interface DdzDecisionPolicy {
    fun callLevel(hand: List<String>): Int
    fun shouldDouble(hand: List<String>, bottom: List<String>): Boolean
}

/**
 * A deterministic first-pass policy for a classic human game.
 *
 * Its call score is the existing conservative rule used by single-player mode. Doubling is
 * reserved for an unusually strong, visible hand; all uncertain or ordinary hands pass.
 */
internal object ConservativeDdzPolicy : DdzDecisionPolicy {
    override fun callLevel(hand: List<String>): Int {
        val score = callScore(hand)
        return when {
            score >= 7 -> 3
            score >= 5 -> 2
            else -> 0
        }
    }

    override fun shouldDouble(hand: List<String>, bottom: List<String>): Boolean {
        val count = hand.groupingBy { it }.eachCount()
        val score = callScore(hand)
        val jokers = (if ("BJ" in count) 1 else 0) + (if ("SJ" in count) 1 else 0)
        val bombs = count.count { (rank, n) -> n == 4 && rank != "BJ" && rank != "SJ" }
        // Bottom cards are visible input to the seam, but are never treated as a reason to
        // double by themselves. This keeps an uncertain recognition from becoming aggressive.
        return score >= 9 && jokers == 2 && bombs >= 1 && bottom.size == 3
    }

    private fun callScore(hand: List<String>): Int {
        val count = hand.groupingBy { it }.eachCount()
        return (if ("BJ" in count) 3 else 0) + (if ("SJ" in count) 2 else 0) +
            (count["2"] ?: 0) +
            2 * count.count { (rank, n) -> n == 4 && rank != "BJ" && rank != "SJ" } +
            (count["A"] ?: 0) / 2
    }
}

/** Public-to-the-phone-module policy seam used by focused behavior tests and callers. */
internal object HumanDdzPolicy : DdzDecisionPolicy by ConservativeDdzPolicy

internal data class DdzSnapshot(
    val hand: List<String>,
    val bottom: List<String>,
    val history: List<List<String>>,
    val phase: String,
    val seat: String,
)
