package com.wangzan101.mika.core.cap.phone

/** 牌桌上的三家,按出牌顺序:我 → 右 → 左 → 我(逆时针;下家在右边,上家在左边)。 */
internal enum class DdzSeat { Me, Right, Left;
    val next: DdzSeat get() = entries[(ordinal + 1) % 3]
}

/**
 * 记这一局的出牌历史,给 DouZero 用。谁在出牌看闹钟在谁桌前(轮到自己时看按钮);闹钟一离开某家,
 * 他这一手就出完了,读他的出牌区。闹钟跳过了谁(两家出得太快,两次轮询之间就过去了),跳过的也算出完。
 * 地主的第一手一定是出牌:底牌翻开后头一回在谁的出牌区看到牌,谁就是地主,这手就是第一手;
 * 轮到自己先出、手里 20 张,地主就是自己。在那之前的闹钟是加倍时的,不算。读不出、对不上就记下原因,这一局不再给 DouZero 算。
 */
internal class DdzTracker {
    var landlord: DdzSeat? = null
        private set
    var bottom: List<String>? = null
        private set
    val history = mutableListOf<List<String>>()
    /** 这一局记不下去的原因;为 null 时历史可用。 */
    var broken: String? = null
        private set

    private var active: DdzSeat? = null
    /** 出完了、还没读到他出了什么的几家,按出牌顺序。 */
    private val waiting = ArrayDeque<Pair<DdzSeat, Int>>()

    /**
     * 每一帧喂进来:[turn] 此刻轮到谁(没人或看不出为 null),[read] 读某家出牌区,[bottom] 读底牌,
     * [leadingWith20] 此刻轮到自己先出、手里 20 张,[leading] 此刻轮到自己先出。
     */
    fun observe(turn: DdzSeat?, read: (DdzSide) -> DdzSeen, bottom: () -> List<String>?, leadingWith20: Boolean = false, leading: Boolean = false) {
        if (broken != null) return
        // 底牌只是给 DouZero 的参考,认不出(比如里面有张王,竖着写的字太小)也照样记;叫分、加倍时出牌区只有字,不会当成牌
        if (this.bottom == null) this.bottom = bottom()
        if (landlord == null) {
            if (leadingWith20) {
                landlord = DdzSeat.Me
                active = DdzSeat.Me
                return
            }
            for (side in DdzSide.entries) {
                val seen = read(side)
                // 地主的第一手认不准:不能等下一家,否则会把下一家当成地主
                if (seen == DdzSeen.Unsure) return fail("unsure_first_play")
                if (seen !is DdzSeen.Cards) continue
                val seat = if (side == DdzSide.Left) DdzSeat.Left else DdzSeat.Right
                landlord = seat
                history += seen.ranks
                // 他出完了,闹钟已经到了下一家
                active = seat.next
                return
            }
            return
        }
        // 轮到自己先出,说明上一轮最后出牌的是自己、后面两家都没要;闯关的牌桌一轮结束就清桌,连「不出」都不留
        val seen = if (leading) { side: DdzSide -> read(side).let { if (it == DdzSeen.Empty) DdzSeen.Pass else it } } else read
        resolve(seen)
        if (turn == null || turn == active) return
        val from = active ?: return
        active = turn
        // from 出完了,中间被跳过的也出完了
        var seat: DdzSeat = from
        while (seat != turn) {
            if (seat != DdzSeat.Me) waiting.addLast(seat to READ_TRIES)
            seat = seat.next
        }
        resolve(seen)
    }

    /** 自己这一手出了什么([] 是不出);工具按下去之后马上记。 */
    fun mine(cards: List<String>) {
        if (broken != null) return
        if (active != DdzSeat.Me) return fail("not_my_turn")
        if (waiting.isNotEmpty()) return fail("unread_before_me")
        history += cards
        // 出完就轮到下家:两家出得太快、闹钟又回到我这里时,才看得出中间那两手
        active = DdzSeat.Me.next
    }

    /** 轮到自己时能不能交给 DouZero:别家的都读到了、历史没断。 */
    fun ready(): Boolean = broken == null && landlord != null && waiting.isEmpty() && active == DdzSeat.Me

    /** 自己在 DouZero 里的位置。 */
    fun seat(): String = when (landlord) {
        DdzSeat.Me -> "landlord"
        // 地主在左边,我就是他的下家;在右边,我就是他的上家
        DdzSeat.Left -> "landlord_down"
        else -> "landlord_up"
    }

    fun fail(reason: String) { if (broken == null) broken = reason }

    private fun resolve(read: (DdzSide) -> DdzSeen) {
        while (waiting.isNotEmpty()) {
            val (seat, tries) = waiting.first()
            when (val seen = read(if (seat == DdzSeat.Left) DdzSide.Left else DdzSide.Right)) {
                // 只有地主的牌压着绶带;农民的牌认不准就是认不准,不能按猜的补
                is DdzSeen.Cards -> if (seat != landlord && seen.ranks.any { it.startsWith(DdzCards.UNKNOWN) }) {
                    return fail("unsure_${seat.name.lowercase()}")
                } else history += seen.ranks
                DdzSeen.Pass -> history += emptyList<String>()
                DdzSeen.Unsure -> return fail("unsure_${seat.name.lowercase()}")
                // 牌还在飞过来:下一帧再读,读几次都空就算漏了
                DdzSeen.Empty -> {
                    if (tries <= 1) return fail("unread_${seat.name.lowercase()}")
                    waiting[0] = seat to tries - 1
                    return
                }
            }
            waiting.removeFirst()
        }
    }

    private companion object {
        const val READ_TRIES = 8
    }
}
