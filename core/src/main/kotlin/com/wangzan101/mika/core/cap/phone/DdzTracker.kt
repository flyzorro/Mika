package com.wangzan101.mika.core.cap.phone

/** 牌桌上的三家;出牌顺序是 我 → 左 → 右 → 我。 */
internal enum class DdzSeat { Me, Left, Right;
    val next: DdzSeat get() = entries[(ordinal + 1) % 3]
}

/**
 * 记这一局的出牌历史,给 DouZero 用。谁在出牌看闹钟在谁桌前(轮到自己时看按钮);闹钟一离开某家,
 * 他这一手就出完了,读他的出牌区。闹钟跳过了谁(两家出得太快,两次轮询之间就过去了),跳过的也算出完。
 * 叫完地主后第一个出牌的就是地主。读不出、对不上就记下原因,这一局不再给 DouZero 算。
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
     * 每一帧喂进来:[turn] 此刻轮到谁(没人或看不出为 null),[read] 读某家出牌区,[bottom] 读底牌。
     * 只在叫完地主、底牌翻开之后才开始记。
     */
    fun observe(turn: DdzSeat?, read: (DdzSide) -> DdzSeen, bottom: () -> List<String>?) {
        if (broken != null) return
        if (this.bottom == null) {
            this.bottom = bottom() ?: return
        }
        resolve(read)
        if (turn == null || turn == active) return
        val from = active
        active = turn
        if (from == null) {
            if (landlord == null) landlord = turn
            return
        }
        // from 出完了,中间被跳过的也出完了
        var seat: DdzSeat = from
        while (seat != turn) {
            if (seat != DdzSeat.Me) waiting.addLast(seat to READ_TRIES)
            seat = seat.next
        }
        resolve(read)
    }

    /** 自己这一手出了什么([] 是不出);工具按下去之后马上记。 */
    fun mine(cards: List<String>) {
        if (broken != null) return
        if (active != DdzSeat.Me) return fail("not_my_turn")
        if (waiting.isNotEmpty()) return fail("unread_before_me")
        history += cards
    }

    /** 轮到自己时能不能交给 DouZero:别家的都读到了、历史没断。 */
    fun ready(): Boolean = broken == null && landlord != null && bottom != null && waiting.isEmpty() && active == DdzSeat.Me

    /** 自己在 DouZero 里的位置。 */
    fun seat(): String = when (landlord) {
        DdzSeat.Me -> "landlord"
        DdzSeat.Left -> "landlord_up"
        else -> "landlord_down"
    }

    fun fail(reason: String) { if (broken == null) broken = reason }

    private fun resolve(read: (DdzSide) -> DdzSeen) {
        while (waiting.isNotEmpty()) {
            val (seat, tries) = waiting.first()
            when (val seen = read(if (seat == DdzSeat.Left) DdzSide.Left else DdzSide.Right)) {
                is DdzSeen.Cards -> history += seen.ranks
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
