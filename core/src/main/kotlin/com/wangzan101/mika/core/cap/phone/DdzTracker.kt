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
    private var firstUnsure = 0
    /** 自己出完以后见过不是自己的回合了没有:刚按下出牌,按钮还会在画面上留几帧,那不是又轮到自己。 */
    private var leftMe = true
    /** 第几帧;同一帧里 resolve 会调两次,「不出」要按帧数。 */
    private var frameNo = 0L
    private var steadyKey: Any? = null
    private var steadyFrames = 0
    private var steadyFrameNo = -1L
    /** 最近几件事(闹钟换到谁、给谁记了什么),记不下去时随原因交出去,查是哪一步错的。 */
    val trace = ArrayDeque<String>()
    private var lastTurn: DdzSeat? = null
    private var meFrames = 0
    /** 出完了、还没读到他出了什么的几家,按出牌顺序。 */
    private val waiting = ArrayDeque<Pair<DdzSeat, Int>>()
    /** 左右两家剩几张:上一帧的读数,和连着两帧一样的读数(一帧认错不算)。 */
    private val countSeen = mutableMapOf<DdzSeat, Int?>()
    private val count = mutableMapOf<DdzSeat, Int?>()
    /** 每家最近出的一手:管不了时游戏替他不出,桌上还摆着这一手。 */
    private val lastPlay = mutableMapOf<DdzSeat, List<String>>()

    /**
     * 每一帧喂进来:[turn] 此刻轮到谁(没人或看不出为 null),[read] 读某家出牌区,[bottom] 读底牌,
     * [leadingWith20] 此刻轮到自己先出、手里 20 张,[leading] 此刻轮到自己先出。
     */
    fun observe(
        rawTurn: DdzSeat?, read: (DdzSide) -> DdzSeen, bottom: () -> List<String>?, leadingWith20: Boolean = false, leading: Boolean = false,
        counts: () -> Map<DdzSide, Int?> = { emptyMap() },
    ) {
        if (broken != null) return
        frameNo++
        // 轮到我要连着两帧:炸弹的特效会让一帧像是我的按钮,当成轮到我又走开,就会给我记一手不出
        meFrames = if (rawTurn == DdzSeat.Me) meFrames + 1 else 0
        val turn = if (rawTurn == DdzSeat.Me && meFrames < ME_FRAMES) null else rawTurn
        if (turn != lastTurn) { note("t=${turn?.name ?: "-"}"); lastTurn = turn }
        val read2 = counts()
        for (side in DdzSide.entries) {
            val seat = seatOf(side)
            val now = read2[side]
            count[seat] = now.takeIf { it != null && it == countSeen[seat] }
            countSeen[seat] = now
        }
        // 底牌只是给 DouZero 的参考,认不出(比如里面有张王,竖着写的字太小)也照样记;叫分、加倍时出牌区只有字,不会当成牌
        if (this.bottom == null) this.bottom = bottom()
        // 轮到自己先出、手里 20 张,一定是地主、还没人出过牌:之前认成别家出的牌(比如翻底牌的动画)作废
        if (leadingWith20 && landlord != DdzSeat.Me) {
            landlord = null
            history.clear()
            waiting.clear()
        }
        if (landlord == null) {
            if (leadingWith20) {
                landlord = DdzSeat.Me
                active = DdzSeat.Me
                return
            }
            for (side in DdzSide.entries) {
                val seen = read(side)
                // 地主的第一手认不准:可能还在飞,等几帧;一直认不准就停,不能跳过去等下一家,否则会把下一家当成地主
                if (seen == DdzSeen.Unsure) {
                    if (++firstUnsure >= READ_TRIES) fail("unsure_first_play")
                    return
                }
                if (seen !is DdzSeen.Cards) continue
                val seat = seatOf(side)
                // 地主出第一手前是 20 张:剩的张数对不上出的张数(还叠成一摞,或者这家是 17 张的农民)就不算
                val left = count[seat]
                if (left != null && seen.ranks.size != FIRST - left) continue
                // 张数对得上就不是叠着的一摞,两帧一样就够(别人出得快,等不起:接手时这手牌两三帧后就被收走了);
                // 一帧不够:牌还在落下的那一帧,带绶带的 3 会认成 J
                if (!steady(side to seen, if (left == null) STEADY_FRAMES else COUNTED_FRAMES)) return
                landlord = seat
                record(seat, seen.ranks)
                // 他出完了,闹钟已经到了下一家
                active = seat.next
                return
            }
            return
        }
        // 轮到自己先出,说明上一轮最后出牌的是自己、后面两家都没要;闯关的牌桌一轮结束就清桌,连「不出」都不留
        val seen = if (leading) { side: DdzSide -> read(side).let { if (it == DdzSeen.Empty) DdzSeen.Pass else it } } else read
        if (turn != DdzSeat.Me) leftMe = true
        if (turn == DdzSeat.Me && !leftMe) return
        resolve(seen)
        // 张数少了的那家出过牌了:闹钟还没离开他(出得太快、清桌太快)也当他这一手出完,马上读
        for (seat in listOf(DdzSeat.Left, DdzSeat.Right)) {
            if (active != DdzSeat.Me && waiting.none { it.first == seat } && (unplayed(seat) ?: 0) > 0 && reaches(seat)) {
                note("n=${seat.name}${count[seat]}")
                advance(seat.next)
                resolve(seen)
            }
        }
        if (turn == null || turn == active) return
        advance(turn)
        resolve(seen)
    }

    /**
     * 闹钟从 [active] 走到了 [turn]:中间的几家都出完了。走过自己(包括从自己这里走开)而工具没出过牌([mine] 会把
     * [active] 挪到下家),就是游戏替我不出了:管不了王炸时根本不轮到我。
     */
    private fun advance(turn: DdzSeat) {
        val from = active ?: return
        active = turn
        var seat: DdzSeat = from
        while (seat != turn) {
            waiting.addLast(seat to READ_TRIES)
            seat = seat.next
        }
    }

    /** 从 [active] 往后数、不经过自己就到得了 [seat]。 */
    private fun reaches(seat: DdzSeat): Boolean {
        var at = active ?: return false
        while (at != seat) {
            at = at.next
            if (at == DdzSeat.Me) return false
        }
        return true
    }

    /** [seat] 出了、还没记下的张数:按记下的历史该剩几张,减去读到的剩几张。读不到、对不上为 null。 */
    private fun unplayed(seat: DdzSeat): Int? {
        val now = count[seat] ?: return null
        val lord = landlord ?: return null
        var at = lord
        var played = 0
        for (move in history) {
            if (at == seat) played += move.size
            at = at.next
        }
        return ((if (seat == lord) FIRST else FARMER) - played - now).takeIf { it >= 0 }
    }

    /** 自己这一手出了什么([] 是不出);工具按下去之后马上记。 */
    fun mine(cards: List<String>) {
        if (broken != null) return
        if (active != DdzSeat.Me) return fail("not_my_turn")
        if (waiting.isNotEmpty()) return fail("unread_before_me")
        record(DdzSeat.Me, cards)
        leftMe = false
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

    fun fail(reason: String) { if (broken == null) { broken = reason; note("fail $reason") } }

    private fun record(seat: DdzSeat, cards: List<String>) {
        history += cards
        if (cards.isNotEmpty()) lastPlay[seat] = cards
        note("${seat.name}=${cards.joinToString("")}")
    }

    private fun note(event: String) {
        trace.addLast("$frameNo:$event")
        if (trace.size > TRACE) trace.removeFirst()
    }

    private fun resolve(read: (DdzSide) -> DdzSeen) {
        while (waiting.isNotEmpty()) {
            val (seat, tries) = waiting.first()
            if (seat == DdzSeat.Me) { record(seat, emptyList()); waiting.removeFirst(); continue }
            val seen = read(if (seat == DdzSeat.Left) DdzSide.Left else DdzSide.Right)
            // 只有地主的牌压着绶带;农民的牌带着猜的那一张就是没认准
            val guessed = seen is DdzSeen.Cards && seat != landlord && seen.ranks.any { it.startsWith(DdzCards.UNKNOWN) }
            // 剩的张数说这家出了几张:读到的张数对不上(还叠着、还没落下)、或者明明出了牌却像「不出」,都再等等
            val played = unplayed(seat)
            when {
                seen is DdzSeen.Cards && !guessed && (played == null || played == seen.ranks.size) -> {
                    if (!steady(seat to seen, if (played == null) STEADY_FRAMES else COUNTED_FRAMES)) return
                    record(seat, seen.ranks)
                }
                // 「不出」也一样:飞过来的牌缩在头像边上那一下,也会落在「不出」那一条里
                seen == DdzSeen.Pass && (played == null || played == 0) -> {
                    if (!steady(seat to seen)) return
                    record(seat, emptyList())
                }
                // 张数没少、出牌区好一阵都空着或者还摆着他上一手:是「不出」。字被清桌(炸弹、新一轮)抹掉了,
                // 或者管不了王时游戏替他不出,桌上留着他上一手
                played == 0 && tries <= READ_TRIES - PASS_BY_COUNT &&
                    (seen == DdzSeen.Empty || seen == DdzSeen.Unsure || (seen is DdzSeen.Cards && seen.ranks == lastPlay[seat])) -> {
                    if (!steady(seat to "count_pass")) return
                    record(seat, emptyList())
                }
                // 牌还在飞过来(空着,或者正在落下认不准):下一帧再读,读几次还不行才算漏了、认不准
                else -> {
                    if (tries <= 1) return fail("${if (seen == DdzSeen.Empty) "unread" else "unsure"}_${seat.name.lowercase()}")
                    waiting[0] = seat to tries - 1
                    return
                }
            }
            waiting.removeFirst()
        }
    }

    /**
     * 同一家同一个读数连着 [STEADY_FRAMES] 帧才算数:几张牌飞过来时先叠成一摞,只看得到最上面那张(7773 读成 3)。
     * 算数后从头数,下一家从这一帧起数。
     */
    private fun steady(key: Any, frames: Int = STEADY_FRAMES): Boolean {
        if (key != steadyKey) { steadyKey = key; steadyFrames = 0; steadyFrameNo = -1 }
        if (steadyFrameNo != frameNo) { steadyFrameNo = frameNo; steadyFrames++ }
        if (steadyFrames < frames) return false
        steadyKey = null
        return true
    }

    private fun seatOf(side: DdzSide) = if (side == DdzSide.Left) DdzSeat.Left else DdzSeat.Right

    private companion object {
        const val FIRST = 20
        const val FARMER = 17
        /** 张数没少、出牌区空了这么多帧才按张数算不出。 */
        const val PASS_BY_COUNT = 4
        /** 一手牌读几帧还认不准才放弃:飞机、炸弹的特效会盖住牌三四秒。 */
        const val READ_TRIES = 16
        const val STEADY_FRAMES = 3
        /** 轮到我的画面要连着这么多帧才算。 */
        const val ME_FRAMES = 2
        /** 张数对得上的一手要连着几帧读得一样。 */
        const val COUNTED_FRAMES = 2
        const val TRACE = 24
    }
}
