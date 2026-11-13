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
internal class DdzTracker(private val settling: DdzSettlingPolicy = DdzSettlingPolicy.SINGLE) {
    var landlord: DdzSeat? = null
        private set
    var bottom: List<String>? = null
        private set
    val history = mutableListOf<List<String>>()
    /** Only moves that survived visibility and settling checks are exposed here. */
    val confirmedSeats = mutableListOf<DdzSeat>()
    /** 这一局记不下去的原因;为 null 时历史可用。 */
    var broken: String? = null
        private set

    private var active: DdzSeat? = null
    private var firstUnsure = 0
    /** 地主的第一手连着几帧不合牌规:和「认不准」一样,读稳之前先等。 */
    private var firstIllegal = 0
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
    /** 还在等的几家最近一次读到的「不出」或牌:真人桌上有时只亮一帧,之后出牌区就读不出了。 */
    private val lastSeen = mutableMapOf<DdzSeat, DdzSeen>()
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
        val turn = if (rawTurn == DdzSeat.Me && meFrames < settling.meFrames) null else rawTurn
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
            confirmedSeats.clear()
            waiting.clear()
            lastSeen.clear()
        }
        if (landlord == null) {
            if (leadingWith20) {
                landlord = DdzSeat.Me
                active = DdzSeat.Me
                return
            }
            // 两家都摆着牌:地主先出、下一家压上。出牌顺序右 → 左,没轮到左家就先看右家,
            // 否则左家这一手先读稳,就把压牌的农民当成了地主(右家第一手还没读稳时左家就压上了)
            val order = if (turn == DdzSeat.Left) listOf(DdzSide.Left, DdzSide.Right) else listOf(DdzSide.Right, DdzSide.Left)
            for (side in order) {
                val seen = read(side)
                // 地主的第一手认不准:可能还在飞,等几帧;一直认不准就停,不能跳过去等下一家,否则会把下一家当成地主。
                // 只数连着的:叫分、抢地主的特效零星几帧认不准,从发牌一路攒到上限就一手没出也停了
                if (seen == DdzSeen.Unsure) {
                    firstIllegal = 0
                    if (++firstUnsure >= settling.maxUnconfirmedFrames) fail("unsure_first_play")
                    return
                }
                if (seen !is DdzSeen.Cards) continue
                val seat = seatOf(side)
                // 地主出第一手前是 20 张:剩的张数对不上出的张数(还叠成一摞,或者这家是 17 张的农民)就不算
                val left = count[seat]
                if (left != null && seen.ranks.size != FIRST - left) continue
                // 这一手不合牌规(比如把王炸认成了单张):多半还在落,和「认不准」一样先等;
                // 连着这么多帧都这样就是认错了,这一段历史不能再用
                if (illegal(seen.ranks)) {
                    if (++firstIllegal >= settling.maxUnconfirmedFrames) fail("illegal_${side.name.lowercase()}")
                    return
                }
                firstIllegal = 0
                // 读到了认得准的牌:连着的「认不准」重新数
                firstUnsure = 0
                // 张数对得上就不是叠着的一摞,两帧一样就够(别人出得快,等不起:接手时这手牌两三帧后就被收走了);
                // 一帧不够:牌还在落下的那一帧,带绶带的 3 会认成 J
                if (!steady(side to seen, if (left == null) settling.steadyFrames else settling.countedFrames)) return
                landlord = seat
                record(seat, seen.ranks)
                // 他出完了,闹钟已经到了下一家
                active = seat.next
                return
            }
            // 这一帧没有哪边认不准:连着的「认不准」「不合牌规」都重新数
            firstUnsure = 0
            firstIllegal = 0
            return
        }
        // 轮到自己先出,说明上一轮最后出牌的是自己、后面两家都没要;闯关的牌桌一轮结束就清桌,连「不出」都不留
        val seen = if (leading) { side: DdzSide -> read(side).let { if (it == DdzSeen.Empty) DdzSeen.Pass else it } } else read
        if (turn != DdzSeat.Me) leftMe = true
        // 我出的牌没人要得起:两家都被秒过(闹钟没在他们头上停过),又轮到我先出。两家都摆着「不出」,
        // 就不是我刚出完、按钮还留着的那几帧(那时桌上没有他们的「不出」)
        if (turn == DdzSeat.Me && !leftMe && leading && read(DdzSide.Left) == DdzSeen.Pass && read(DdzSide.Right) == DdzSeen.Pass) leftMe = true
        if (turn == DdzSeat.Me && !leftMe) return
        // 我方先出且上一手有效牌是我出的,轮到我前的每家都只能是不出;不等出牌区读稳
        inferOwnLeadPasses(turn, leading)
        resolve(seen)
        // 闹钟已经在这个最后出牌的人这里(没有走动的这一步):中间各家都空着就是都不出
        if (turn != null && turn == active && inferPassesToLeader(turn, read)) resolve(seen)
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
        inferOwnLeadPasses(turn, leading)
        resolve(seen)
        if (inferPassesToLeader(turn, read)) resolve(seen)
    }

    /**
     * 闹钟回到这一轮最后出牌的那一家,中间各家的出牌区都空着或写着「不出」:他们都要不起,按不出记下,不判漏。
     * 真人桌上两家的「不出」一闪就没了(一整圈不到一秒),等不到读数,再等下去就会把下一手的牌当成漏读。
     * 只在能确定的时候推:最后一手确实是这家出的([trickLeaderSeat]),中间各家都没读到新的一手(Empty 或 Pass),
     * 手上也没有这一轮读到过的新牌。别的情况照旧交给 [resolve]/[advance] 判漏。
     */
    private fun inferPassesToLeader(turn: DdzSeat?, read: (DdzSide) -> DdzSeen): Boolean {
        if (turn == null || turn != trickLeaderSeat()) return false
        // 领出的人也还在等:他手上可能有一手没记下的新牌,不能替他按不出
        if (waiting.isEmpty() || waiting.any { it.first == turn }) return false
        for ((seat, _) in waiting) {
            if (seat == DdzSeat.Me) continue
            val seen = read(if (seat == DdzSeat.Left) DdzSide.Left else DdzSide.Right)
            if (seen != DdzSeen.Empty && seen != DdzSeen.Pass) return false
            // 已经读到过他的什么(「不出」亮过一帧、或者一手牌又清掉了):交给 resolve,这里不替他推
            if (lastSeen[seat] != null) return false
        }
        while (waiting.isNotEmpty()) {
            val (seat, _) = waiting.removeFirst()
            record(seat, emptyList())
            lastSeen -= seat
        }
        return true
    }

    /** 这一轮还没人管的那手牌是谁出的;两次「不出」之后、或者还没人出牌时为 null。 */
    private fun trickLeaderSeat(): DdzSeat? {
        val index = history.indexOfLast { it.isNotEmpty() }
        if (index < 0) return null
        // 这一手之后已经两家都不出:这一轮结束了,接下来是新的领出
        if (history.size - index - 1 >= 2) return null
        return confirmedSeats.getOrNull(index)
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
            // 这家上一手还没读到又轮过他一次:先看已经看到的是不是够记,不够才判漏,别拿他下一手顶上
            if (waiting.any { it.first == seat }) {
                settle()
                if (waiting.any { it.first == seat }) return fail(DdzSessionReason.MISSED_PLAY)
            }
            waiting.addLast(seat to settling.maxUnconfirmedFrames)
            seat = seat.next
        }
    }

    /** 队头卡着、闹钟却已经走过去了:拿已经看到的一手/不出把能记的按顺序记了;队头没有能用的读数就停,交给 [advance] 判漏。 */
    private fun settle() {
        while (waiting.isNotEmpty()) {
            val seat = waiting.first().first
            if (seat == DdzSeat.Me) {
                record(seat, emptyList())
                waiting.removeFirst()
                lastSeen -= seat
                continue
            }
            val seen = lastSeen[seat] ?: return
            val played = unplayed(seat)
            val cards = (seen as? DdzSeen.Cards)?.ranks
            val usable = when {
                // 出牌区还摆着他自己上一手:不是新出的一手;张数对不上就是还没落稳
                cards != null -> cards != lastPlay[seat] && !illegal(cards) &&
                    (played == null || played == cards.size)
                seen == DdzSeen.Pass -> played == null || played == 0
                else -> false
            }
            if (!usable) return
            record(seat, cards ?: emptyList())
            waiting.removeFirst()
            lastSeen -= seat
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

    /** 这家最近两帧都一样的剩牌张数;读不到为 null。这一局记不下去了也可能还留着上一次的读数。 */
    internal fun seatCount(seat: DdzSeat): Int? = count[seat]

    /** Install a complete, already validated visible history without observing a new frame. */
    internal fun restore(landlord: DdzSeat, bottom: List<String>, moves: List<List<String>>, current: DdzSeat = landlord) {
        this.landlord = landlord
        this.bottom = bottom.toList()
        history.clear()
        confirmedSeats.clear()
        history += moves.map { it.toList() }
        active = landlord
        lastPlay.clear()
        var actor = landlord
        var leader: DdzSeat? = null
        var passes = 0
        moves.forEach { move ->
            confirmedSeats += actor
            if (move.isEmpty()) {
                passes++
                actor = actor.next
                if (passes == 2) {
                    actor = leader ?: actor
                    leader = null
                    passes = 0
                    lastPlay.clear()
                }
            } else {
                lastPlay[actor] = move.toList()
                leader = actor
                actor = actor.next
                passes = 0
            }
        }
        active = current
        waiting.clear()
        lastSeen.clear()
        broken = null
        leftMe = true
        firstUnsure = 0
        steadyKey = null
        steadyFrames = 0
        steadyFrameNo = -1L
    }

    /** Compare the currently visible opponent play areas with the recovered current trick. */
    internal fun visibleHistoryMatches(read: (DdzSide) -> DdzSeen): Boolean {
        fun matches(seat: DdzSeat, seen: DdzSeen): Boolean {
            val expected = lastPlay[seat] ?: return seen !is DdzSeen.Cards
            return seen is DdzSeen.Cards && seen.ranks == expected
        }
        return matches(DdzSeat.Left, read(DdzSide.Left)) && matches(DdzSeat.Right, read(DdzSide.Right))
    }

    /** 自己在 DouZero 里的位置。 */
    fun seat(): String = when (landlord) {
        DdzSeat.Me -> "landlord"
        // 地主在左边,我就是他的下家;在右边,我就是他的上家
        DdzSeat.Left -> "landlord_down"
        else -> "landlord_up"
    }

    fun fail(reason: String) { if (broken == null) { broken = reason; note("fail $reason") } }

    private fun record(seat: DdzSeat, cards: List<String>) {
        history += cards.toList()
        confirmedSeats += seat
        if (cards.isNotEmpty()) lastPlay[seat] = cards.toList()
        note("${seat.name}=${cards.joinToString("")}")
    }

    private fun lastNonEmptyWasMine(): Boolean {
        val index = history.indexOfLast { it.isNotEmpty() }
        return index >= 0 && confirmedSeats.getOrNull(index) == DdzSeat.Me
    }

    private fun inferOwnLeadPasses(turn: DdzSeat?, leading: Boolean) {
        if (turn != DdzSeat.Me || !leading || waiting.isEmpty() || waiting.any { it.first == DdzSeat.Me } || !lastNonEmptyWasMine()) return
        while (waiting.isNotEmpty()) {
            val (seat, _) = waiting.removeFirst()
            record(seat, emptyList())
            lastSeen -= seat
        }
    }

    private fun note(event: String) {
        trace.addLast("$frameNo:$event")
        if (trace.size > TRACE) trace.removeFirst()
    }

    private fun resolve(read: (DdzSide) -> DdzSeen) {
        // 队头还没读稳,后面几家却已经出过了:先记住他们的读数,等排到队头再记(见下面的 seen_then_cleared)
        for ((seat, _) in waiting.drop(1)) {
            if (seat == DdzSeat.Me) continue
            // 不补 ?:前面几家还没记,[trickLead] 是旧的那一手,拿它筛补法会把几种都行的错当成只有一种
            val seen = read(if (seat == DdzSeat.Left) DdzSide.Left else DdzSide.Right)
            val guessed = seen is DdzSeen.Cards && seat != landlord && seen.ranks.any { it.startsWith(DdzCards.UNKNOWN) }
            // 出牌区还摆着他自己上一手:那是「不出」,不是又出了一手
            val oldPlay = seen is DdzSeen.Cards && seen.ranks == lastPlay[seat]
            if (!oldPlay && (seen == DdzSeen.Pass || (seen is DdzSeen.Cards && !guessed))) lastSeen[seat] = seen
        }
        while (waiting.isNotEmpty()) {
            val (seat, tries) = waiting.first()
            if (seat == DdzSeat.Me) { record(seat, emptyList()); waiting.removeFirst(); continue }
            val raw = read(if (seat == DdzSeat.Left) DdzSide.Left else DdzSide.Right)
            val seen = if (raw is DdzSeen.Cards) filledUnknown(raw.ranks) ?: raw else raw
            // 只有地主的牌压着绶带;农民的牌带着猜的那一张就是没认准
            val guessed = seen is DdzSeen.Cards && seat != landlord && seen.ranks.any { it.startsWith(DdzCards.UNKNOWN) }
            // 剩的张数说这家出了几张:读到的张数对不上(还叠着、还没落下)、或者明明出了牌却像「不出」,都再等等
            val played = unplayed(seat)
            if (seen == DdzSeen.Pass || (seen is DdzSeen.Cards && !guessed)) lastSeen[seat] = seen
            // 读到的这一手不合牌规(单张压三带一之类,多半是认错了):不记,和「认不准」一样再等几帧
            val lastCards = (lastSeen[seat] as? DdzSeen.Cards)?.ranks
            val breaksRules = when {
                // 出牌区还摆着他自己上一手:是「不出」,不是又出了一手(下面按张数算)
                seen is DdzSeen.Cards && !guessed && seen.ranks != lastPlay[seat] -> illegal(seen.ranks)
                seen == DdzSeen.Empty && lastCards != null && lastCards != lastPlay[seat] &&
                    (played == null || played == lastCards.size) -> illegal(lastCards)
                else -> false
            }
            when {
                breaksRules -> {
                    if (tries <= 1) return fail("illegal_${seat.name.lowercase()}")
                    waiting[0] = seat to tries - 1
                    return
                }
                seen is DdzSeen.Cards && !guessed && (played == null || played == seen.ranks.size) -> {
                    // 地主最后一张的 ? 后面猜的点数一帧一个样:比稳不稳时只看 ?,不看猜的;照着猜的补出来的也不算
                    val key = DdzSeen.Cards((raw as? DdzSeen.Cards)?.ranks?.map { if (it.startsWith(DdzCards.UNKNOWN)) DdzCards.UNKNOWN else it } ?: seen.ranks)
                    if (!steady(seat to key, if (played == null) settling.steadyFrames else settling.countedFrames)) return
                    record(seat, seen.ranks)
                }
                // 「不出」也一样:飞过来的牌缩在头像边上那一下,也会落在「不出」那一条里
                seen == DdzSeen.Pass && (played == null || played == 0) -> {
                    if (!steady(seat to seen)) return
                    record(seat, emptyList())
                }
                // 读到过「不出」或一手牌、之后出牌区一直空着(没有别的牌落下来):字被清掉、或这一手认不出了,
                // 还是读到的那一手。认错了 DouZero 按牌规核对会报 history_mismatch,不会照着出牌
                seen == DdzSeen.Empty && lastSeen[seat] != null &&
                    (played == null || played == ((lastSeen[seat] as? DdzSeen.Cards)?.ranks?.size ?: 0)) -> {
                    if (!steady(seat to "seen_then_cleared")) return
                    record(seat, (lastSeen[seat] as? DdzSeen.Cards)?.ranks ?: emptyList())
                }
                // 张数没少、出牌区好一阵都空着或者还摆着他上一手:是「不出」。字被清桌(炸弹、新一轮)抹掉了,
                // 或者管不了王时游戏替他不出,桌上留着他上一手
                played == 0 && tries <= settling.maxUnconfirmedFrames - PASS_BY_COUNT &&
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
            lastSeen -= seat
        }
    }

    /**
     * 一手只带一张 ?:把手机猜的点数挨个换上去,只有一种换法既合牌规、又压得住这一轮时才认这一手。
     * 农民出的牌没有绶带,以前带着 ? 只能干等到 unsure_* 停掉整局;可猜的几个点数常常只有一个凑得成牌型
     * (如 [2, ?2,5,3] 只有 2 是一对)。几种都说得通就仍走原来的路:农民接着等,地主的 ? 交给 DouZero 补。
     * 只试手机猜的点数,不自造;已经记下的出牌里这个点数用满了的也不补。[classify] 和 [beats] 就是 #40 那道判罚。
     */
    private fun filledUnknown(ranks: List<String>): DdzSeen.Cards? {
        val unknown = ranks.indices.filter { ranks[it].startsWith(DdzCards.UNKNOWN) }
        if (unknown.size != 1) return null
        val at = unknown.single()
        val guesses = ranks[at].removePrefix(DdzCards.UNKNOWN).split(",").filter { it in DdzCards.RANKS }.distinct()
        val lead = trickLead()
        val options = guesses.mapNotNull { guess ->
            val filled = ranks.toMutableList().also { it[at] = guess }
            val move = DdzSnapshotRecovery.classify(filled) ?: return@mapNotNull null
            if (lead != null && !DdzSnapshotRecovery.beats(lead, move)) return@mapNotNull null
            filled.takeIf { copiesLeft(it, at) }
        }
        return options.singleOrNull()?.let { DdzSeen.Cards(it) }
    }

    /** 这一手加上已经记下的出牌,这个点数没超过一副牌里的张数(王只有一张);手牌不在 tracker 里,不细算。 */
    private fun copiesLeft(filled: List<String>, at: Int): Boolean {
        val rank = filled[at]
        val used = filled.count { it == rank } + history.sumOf { move -> move.count { it == rank } }
        return used <= if (rank == "SJ" || rank == "BJ") 1 else 4
    }

    /**
     * 同一家同一个读数连着 [settling.steadyFrames] 帧才算数:几张牌飞过来时先叠成一摞,只看得到最上面那张(7773 读成 3)。
     * 算数后从头数,下一家从这一帧起数。
     */
    private fun steady(key: Any, frames: Int = settling.steadyFrames): Boolean {
        if (key != steadyKey) { steadyKey = key; steadyFrames = 0; steadyFrameNo = -1 }
        if (steadyFrameNo != frameNo) { steadyFrameNo = frameNo; steadyFrames++ }
        if (steadyFrames < frames) return false
        steadyKey = null
        return true
    }

    /** 这一手读到的牌合不合牌规:不是牌型,或者跟牌压不过这一轮还没人管的那手。带 ? 的没认准,不判。 */
    private fun illegal(cards: List<String>): Boolean {
        if (cards.isEmpty() || cards.any { it.startsWith(DdzCards.UNKNOWN) }) return false
        val move = DdzSnapshotRecovery.classify(cards) ?: return true
        val lead = trickLead() ?: return false
        return !DdzSnapshotRecovery.beats(lead, move)
    }

    /** 这一轮还没人管的那手牌;两次「不出」之后、或者历史里有认不准的牌(判不了)都为 null。 */
    private fun trickLead(): DdzSnapshotRecovery.Move? {
        var active: DdzSnapshotRecovery.Move? = null
        var passes = 0
        for (cards in history) {
            val move = DdzSnapshotRecovery.classify(cards) ?: return null
            if (move.kind == DdzSnapshotRecovery.Kind.Pass) {
                if (active != null) passes++
                if (passes == 2) { active = null; passes = 0 }
            } else {
                active = move
                passes = 0
            }
        }
        return active
    }

    private fun seatOf(side: DdzSide) = if (side == DdzSide.Left) DdzSeat.Left else DdzSeat.Right

    private companion object {
        const val FIRST = 20
        const val FARMER = 17
        /** 张数没少、出牌区空了这么多帧才按张数算不出。 */
        const val PASS_BY_COUNT = 4
        const val TRACE = 24
    }
}
