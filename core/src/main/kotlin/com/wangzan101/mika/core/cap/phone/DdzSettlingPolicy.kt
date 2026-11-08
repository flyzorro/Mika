package com.wangzan101.mika.core.cap.phone

/**
 * How long a tracker may wait for a visible opponent transition to settle.
 * Human opponents get more time than the fast animation path used by single-player.
 */
internal data class DdzSettlingPolicy(
    val maxUnconfirmedFrames: Int,
    val steadyFrames: Int,
    val countedFrames: Int,
    val meFrames: Int,
) {
    init {
        require(maxUnconfirmedFrames > 0)
        require(steadyFrames > 0)
        require(countedFrames > 0)
        require(meFrames > 0)
    }

    companion object {
        val SINGLE = DdzSettlingPolicy(maxUnconfirmedFrames = 16, steadyFrames = 3, countedFrames = 2, meFrames = 2)
        val HUMAN = DdzSettlingPolicy(maxUnconfirmedFrames = 48, steadyFrames = 3, countedFrames = 2, meFrames = 2)
    }
}
