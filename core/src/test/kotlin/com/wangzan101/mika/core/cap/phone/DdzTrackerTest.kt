package com.wangzan101.mika.core.cap.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DdzTrackerTest {
    private val bottom = listOf("9", "8", "3")
    private var left: DdzSeen = DdzSeen.Empty
    private var right: DdzSeen = DdzSeen.Empty
    private val tracker = DdzTracker()

    private fun see(turn: DdzSeat?) = tracker.observe(turn, { if (it == DdzSide.Left) left else right }, { bottom })
    private var counts = mapOf<DdzSide, Int?>()
    private fun seeCounted(turn: DdzSeat?) = tracker.observe(turn, { if (it == DdzSide.Left) left else right }, { bottom }, counts = { counts })

    @Test fun anUnreadableBottomDoesNotHoldTheGameBack() {
        repeat(4) { tracker.observe(DdzSeat.Me, { if (it == DdzSide.Right) DdzSeen.Cards(listOf("5")) else DdzSeen.Empty }, { null }) }
        assertEquals(DdzSeat.Right, tracker.landlord)
        assertNull(tracker.bottom)
    }

    @Test fun playGoesMeThenRightThenLeft() {
        assertEquals(DdzSeat.Right, DdzSeat.Me.next)
        assertEquals(DdzSeat.Left, DdzSeat.Right.next)
        assertEquals(DdzSeat.Me, DdzSeat.Left.next)
    }

    @Test fun clocksDuringDoublingAreIgnoredUntilTheLandlordPlays() {
        see(DdzSeat.Left)
        see(DdzSeat.Right)
        see(DdzSeat.Left)
        assertNull(tracker.landlord)
        // 右家是地主,出了 3;下一个是左家
        right = DdzSeen.Cards(listOf("3"))
        repeat(3) { see(DdzSeat.Left) }
        left = DdzSeen.Pass
        repeat(4) { see(DdzSeat.Me) }
        assertEquals(DdzSeat.Right, tracker.landlord)
        assertEquals(listOf(listOf("3"), emptyList()), tracker.history)
        assertEquals(listOf(DdzSeat.Right, DdzSeat.Left), tracker.confirmedSeats)
        assertTrue(tracker.ready())
        assertEquals("landlord_up", tracker.seat())
        tracker.mine(listOf("5"))
        assertEquals(3, tracker.history.size)
    }

    @Test fun whenBothSidesShowCardsBeforeTheLandlordIsKnownRightPlayedFirst() {
        // Live 2026-11-12 11:45 (single-player classic): the landlord (Right) led the straight 5..K and
        // Left beat it with 6..A before Right's read settled. Left was checked first, so Left became the
        // landlord; Right's later single (with the ribbon) read as ?2 and the hand failed missed_play.
        right = DdzSeen.Cards(listOf("K", "Q", "J", "10", "9", "8", "7", "6", "5"))
        see(DdzSeat.Left)
        left = DdzSeen.Cards(listOf("A", "K", "Q", "J", "10", "9", "8", "7", "6"))
        see(null)
        repeat(4) { see(DdzSeat.Me) }
        assertEquals(DdzSeat.Right, tracker.landlord)
        assertEquals(listOf(right, left).map { (it as DdzSeen.Cards).ranks }, tracker.history)
    }

    @Test fun aPassShownForOneFrameThenClearedIsStillAPass() {
        // Live 2026-11-09 run 10 (human table): Right's 不出 was visible for one frame and cleared
        // the next; the tracker waited on Right until the turn clock ran out (unread_right).
        tracker.observe(DdzSeat.Me, { DdzSeen.Empty }, { bottom }, leadingWith20 = true)
        tracker.mine(listOf("3"))
        right = DdzSeen.Pass
        see(DdzSeat.Left)                 // clock left Right; 不出 shows for this one frame
        right = DdzSeen.Empty
        repeat(2) { see(DdzSeat.Left) }   // 不出 already cleared
        left = DdzSeen.Cards(listOf("5"))
        repeat(4) { see(DdzSeat.Me) }     // Left played; our turn
        assertEquals(listOf(listOf("3"), emptyList(), listOf("5")), tracker.history)
        assertTrue(tracker.ready())
    }

    @Test fun aPlayReadOnceThenClearedIsStillThatPlay() {
        // Live 2026-11-10 run 13: the landlord's single (ribbon over the rank) read once as "?",
        // then the area read empty every frame; the tracker waited until the clock ran out.
        tracker.observe(DdzSeat.Me, { DdzSeen.Empty }, { bottom }, leadingWith20 = true)
        tracker.mine(listOf("3"))
        right = DdzSeen.Cards(listOf("5"))
        see(DdzSeat.Left)                 // Right's 5 visible for one frame
        right = DdzSeen.Empty
        repeat(3) { see(DdzSeat.Left) }   // then never readable again
        left = DdzSeen.Pass
        repeat(4) { see(DdzSeat.Me) }
        assertEquals(listOf(listOf("3"), listOf("5"), emptyList()), tracker.history)
        assertTrue(tracker.ready())
    }

    @Test fun aPlaySeenButNeverRecordedIsNotAPassWhenItsAreaClears() {
        // Live 2026-11-13 16:02 土豪场 (human): Right led 888 777 55 44 but only nine cards were read
        // (the tenth wrapped onto a second row), so the play never passed the rules check and was never
        // recorded. Left's bomb then cleared the table and Right's 不出 for the bomb showed up: the
        // tracker took it as Right's pass for the airplane, so the history lost the hand and DouZero
        // failed (douzero_ambiguous_card). A 不出 that shows after the area was cleared belongs to the
        // next trick; the unread play has to fail instead of being recorded as a pass.
        tracker.restore(
            DdzSeat.Left, bottom,
            listOf(
                listOf("3"), listOf("4"), listOf("5"), listOf("6"), emptyList(), emptyList(),
                listOf("7"), emptyList(), listOf("A", "K", "Q", "J", "10"), emptyList(), emptyList(),
            ),
            DdzSeat.Right,
        )
        right = DdzSeen.Unsure
        // 闹钟走到左家,右家的牌还在飞
        see(DdzSeat.Left)
        // 只读到九张:不合牌规,一直没记下
        right = DdzSeen.Cards("8 8 8 7 7 7 5 5 4".split(' '))
        repeat(4) { see(DdzSeat.Left) }
        // 左家的炸弹还在落
        left = DdzSeen.Unsure
        see(null)
        // 炸弹 6666 清桌(最后一张压着绶带)
        left = DdzSeen.Cards(listOf("6", "6", "6", "?6,8,3"))
        repeat(2) { see(DdzSeat.Me) }
        // 轮到右家,出牌区空着
        right = DdzSeen.Empty
        repeat(2) { see(DdzSeat.Right) }
        // 右家管不了炸弹:这是下一轮的「不出」
        left = DdzSeen.Empty
        right = DdzSeen.Pass
        see(DdzSeat.Left)
        // 不能把「不出」记成右家这一手:这一局判漏,交给简单规则
        assertEquals("missed_play", tracker.broken)
        assertEquals(listOf("A", "K", "Q", "J", "10"), tracker.history[tracker.confirmedSeats.lastIndexOf(DdzSeat.Right)])
    }

    @Test fun playsBehindTheFrontAreRememberedAndSettledWhenTheClockMovesOn() {
        // Live 2026-11-12 12:50 (single-player classic): Right's SJ was recorded late; Left's 不出 then
        // showed for only two frames while Right's new 554433 sat behind it in the queue. When the clock
        // passed Left again, advance() saw it still unrecorded and failed missed_play.
        tracker.restore(
            DdzSeat.Left, bottom,
            listOf(listOf("3"), emptyList(), emptyList(), listOf("2"), emptyList()),
            DdzSeat.Right,
        )
        right = DdzSeen.Cards(listOf("SJ"))
        repeat(3) { see(DdzSeat.Left) }
        assertEquals(listOf("SJ"), tracker.history.last())
        // 左家「不出」只亮两帧(不够 steady);右家的新一手排在队尾,左家还在队头
        left = DdzSeen.Pass
        right = DdzSeen.Empty
        see(DdzSeat.Right)
        see(DdzSeat.Right)
        left = DdzSeen.Empty
        right = DdzSeen.Cards(listOf("5", "5", "4", "4", "3", "3"))
        see(DdzSeat.Left)
        // 两家都过了、闹钟回到右家:以前这里直接 missed_play,右家的 554433 根本没读过
        right = DdzSeen.Empty
        see(DdzSeat.Right)
        assertNull(tracker.broken)
        assertEquals(
            listOf(
                listOf("3"), emptyList(), emptyList(), listOf("2"), emptyList(), listOf("SJ"),
                emptyList(), emptyList(), listOf("5", "5", "4", "4", "3", "3"),
            ),
            tracker.history,
        )
    }

    @Test fun aSeatBehindTheFrontWithNothingUsableStillFails() {
        // Same live hand: Left's 不出 settles, but Right's new lead was only ever the stacked ?Q,6,8.
        // Recording a made-up play would be worse than dropping the hand, so this must still fail.
        tracker.restore(
            DdzSeat.Left, bottom,
            listOf(listOf("3"), emptyList(), emptyList(), listOf("2"), emptyList()),
            DdzSeat.Right,
        )
        right = DdzSeen.Cards(listOf("SJ"))
        repeat(3) { see(DdzSeat.Left) }
        left = DdzSeen.Pass
        right = DdzSeen.Empty
        see(DdzSeat.Right)
        see(DdzSeat.Right)
        left = DdzSeen.Empty
        right = DdzSeen.Cards(listOf("?Q,6,8"))
        see(DdzSeat.Left)
        right = DdzSeen.Empty
        see(DdzSeat.Right)
        // 左家的「不出」先记下;右家这一手只有认不准的读数,不能拿它顶上
        assertNull(tracker.broken)
        assertEquals(listOf(listOf("SJ"), emptyList(), emptyList()), tracker.history.takeLast(3))
        see(DdzSeat.Left)
        assertEquals("missed_play", tracker.broken)
        assertFalse(tracker.history.any { it.size == 6 })
    }

    @Test fun aGuessBehindTheFrontIsNotFilledAgainstAStaleLead() {
        // Behind the front, history still lacks Left's and our passes, so the lead looks like Right's 5.
        // Against 5 only the 9 in ?9,3 fits, but Right is leading a new trick where 3 fits too: don't fill.
        tracker.restore(
            DdzSeat.Left, bottom,
            listOf(listOf("3"), emptyList(), emptyList(), listOf("4"), emptyList()),
            DdzSeat.Right,
        )
        right = DdzSeen.Cards(listOf("5"))
        repeat(3) { see(DdzSeat.Left) }
        assertEquals(listOf("5"), tracker.history.last())
        left = DdzSeen.Pass
        right = DdzSeen.Empty
        see(DdzSeat.Right)
        see(DdzSeat.Right)
        left = DdzSeen.Empty
        right = DdzSeen.Cards(listOf("?9,3"))
        see(DdzSeat.Left)
        right = DdzSeen.Empty
        see(DdzSeat.Right)
        see(DdzSeat.Left)
        assertFalse(tracker.history.any { it == listOf("9") })
        assertEquals("missed_play", tracker.broken)
    }

    @Test fun theLandlordsRibbonCardIsSteadyEvenWhenItsGuessesChange() {
        // Live 2026-11-10 runs 13/16: the guesses behind "?" changed from frame to frame, so three equal
        // reads never happened and the landlord's play was never recorded.
        see(DdzSeat.Right)
        right = DdzSeen.Cards(listOf("3"))   // the landlord (Right) leads
        repeat(3) { see(DdzSeat.Left) }
        left = DdzSeen.Pass
        repeat(4) { see(DdzSeat.Me) }
        tracker.mine(listOf("4"))
        val guesses = listOf("?7,4,J", "?5,J,7", "?4,7,J", "?J,5,7")
        repeat(4) { i ->
            right = DdzSeen.Cards(listOf("5", "5", "5", guesses[i]))
            see(DdzSeat.Left)
        }
        assertEquals(listOf("5", "5", "5"), tracker.history.last().take(3))
    }

    @Test fun bothPassingTooFastToSeeStillGivesMeTheLead() {
        // Live 2026-11-10 run 11: nobody could beat our BJ, both were passed instantly (the clock
        // never showed on them), and our lead was taken for our own lingering buttons until autoplay.
        tracker.observe(DdzSeat.Me, { DdzSeen.Empty }, { bottom }, leadingWith20 = true)
        tracker.mine(listOf("BJ"))
        left = DdzSeen.Pass
        right = DdzSeen.Pass
        repeat(5) { tracker.observe(DdzSeat.Me, { if (it == DdzSide.Left) left else right }, { bottom }, leading = true) }
        assertEquals(listOf(listOf("BJ"), emptyList(), emptyList()), tracker.history)
        assertTrue(tracker.ready())
    }

    @Test fun myLeadInfersPassesWhenOneAreaWasCleared() {
        left = DdzSeen.Cards(listOf("5"))
        repeat(4) { see(DdzSeat.Me) }
        tracker.mine(listOf("9", "9"))
        right = DdzSeen.Empty
        left = DdzSeen.Pass
        see(DdzSeat.Right)
        see(DdzSeat.Left)
        see(DdzSeat.Me)
        tracker.observe(DdzSeat.Me, { if (it == DdzSide.Left) left else right }, { bottom }, leading = true)
        assertNull(tracker.broken)
        assertEquals(listOf(listOf("5"), listOf("9", "9"), emptyList(), emptyList()), tracker.history)
        assertTrue(tracker.ready())
    }

    @Test fun leadInferenceIsSkippedWhenLastPlayWasNotMine() {
        left = DdzSeen.Cards(listOf("5"))
        repeat(4) { see(DdzSeat.Me) }
        tracker.mine(emptyList())
        right = DdzSeen.Cards(listOf("7"))
        see(DdzSeat.Right)
        see(DdzSeat.Left)
        see(DdzSeat.Left)
        see(DdzSeat.Left)
        see(DdzSeat.Me)
        left = DdzSeen.Pass
        right = DdzSeen.Empty
        tracker.observe(DdzSeat.Me, { if (it == DdzSide.Left) left else right }, { bottom }, leading = true)
        assertEquals(listOf(listOf("5"), emptyList(), listOf("7")), tracker.history)
        assertFalse(tracker.ready())
    }

    @Test fun myOwnLingeringButtonsAfterALeadAreNotANewTurn() {
        tracker.observe(DdzSeat.Me, { DdzSeen.Empty }, { bottom }, leadingWith20 = true)
        tracker.mine(listOf("3"))
        // Buttons still on screen right after our lead; the table shows nothing from the others yet.
        repeat(5) { tracker.observe(DdzSeat.Me, { DdzSeen.Empty }, { bottom }, leading = true) }
        assertEquals(listOf(listOf("3")), tracker.history)
        assertFalse(tracker.ready())
    }

    @Test fun aPlayThatNeverShowedBreaksTheHistoryInsteadOfBorrowingTheNextOne() {
        // Live 2026-11-10 run 10: Right beat our SJ but the play was never on screen (the trick
        // cleared fast); Right's next lead 7 was recorded as its answer to SJ and DouZero refused.
        tracker.observe(DdzSeat.Me, { DdzSeen.Empty }, { bottom }, leadingWith20 = true)
        tracker.mine(listOf("SJ"))
        repeat(3) { see(DdzSeat.Left) }   // Right played, nothing readable
        left = DdzSeen.Pass
        repeat(2) { see(DdzSeat.Me) }     // Left passed; the game passes for us (要不起)
        left = DdzSeen.Empty
        repeat(2) { see(DdzSeat.Right) }  // new trick, Right to lead
        right = DdzSeen.Cards(listOf("7"))
        repeat(4) { see(DdzSeat.Left) }   // Right led 7
        assertEquals("missed_play", tracker.broken)
        assertFalse(tracker.history.contains(listOf("7")))
    }

    @Test fun leadingWithTwentyCardsMakesMeTheLandlord() {
        see(DdzSeat.Left) // 加倍时的闹钟
        tracker.observe(DdzSeat.Me, { DdzSeen.Empty }, { bottom }, leadingWith20 = true)
        assertEquals(DdzSeat.Me, tracker.landlord)
        assertTrue(tracker.ready())
        tracker.mine(listOf("3", "3"))
        right = DdzSeen.Cards(listOf("4", "4"))
        left = DdzSeen.Cards(listOf("6", "6"))
        repeat(3) { see(DdzSeat.Left) }  // 右家出完
        repeat(4) { see(DdzSeat.Me) }    // 左家出完
        assertEquals(listOf(listOf("3", "3"), listOf("4", "4"), listOf("6", "6")), tracker.history)
        assertEquals("landlord", tracker.seat())
    }

    @Test fun aSeatSkippedBetweenTwoFramesIsStillRead() {
        tracker.observe(DdzSeat.Me, { DdzSeen.Empty }, { bottom }, leadingWith20 = true)
        tracker.mine(listOf("4"))
        right = DdzSeen.Cards(listOf("6"))
        left = DdzSeen.Cards(listOf("7"))
        see(null) // 我的按钮没了,两家的闹钟都没赶上
        repeat(6) { see(DdzSeat.Me) } // 闹钟从我这里转了一圈又回来:右、左都出完了
        assertEquals(listOf(listOf("4"), listOf("6"), listOf("7")), tracker.history)
    }

    @Test fun theLeftLandlordMakesMeItsNextSeat() {
        left = DdzSeen.Cards(listOf("3"))
        repeat(4) { see(DdzSeat.Me) }
        assertEquals(DdzSeat.Left, tracker.landlord)
        assertEquals("landlord_down", tracker.seat())
        assertTrue(tracker.ready())
    }

    @Test fun scatteredUnsureFramesDuringBiddingDoNotAddUpToAFailure() {
        // Live 2026-11-11 single-player: 叫分、抢地主的特效零星几帧读成「认不准」,从发牌攒到 16 帧,
        // 一手还没出就 unsure_first_play 停了;只数连着的才对
        repeat(40) { i ->
            right = if (i % 2 == 0) DdzSeen.Unsure else DdzSeen.Empty
            see(DdzSeat.Left)
        }
        assertNull(tracker.broken)
        assertNull(tracker.landlord)
        right = DdzSeen.Cards(listOf("3"))
        repeat(3) { see(DdzSeat.Left) }
        assertEquals(DdzSeat.Right, tracker.landlord)
        assertEquals(listOf(listOf("3")), tracker.history)
    }

    @Test fun sixteenConsecutiveUnsureFramesBeforeTheFirstPlayStillFail() {
        right = DdzSeen.Unsure
        repeat(15) { see(DdzSeat.Left) }
        assertNull(tracker.broken)
        see(DdzSeat.Left)
        assertEquals("unsure_first_play", tracker.broken)
    }

    @Test fun anUnreadableFirstPlayIsNotPinnedOnTheNextSeat() {
        right = DdzSeen.Unsure
        see(DdzSeat.Left)
        // 还在飞的话等几帧;一直认不准就停
        assertNull(tracker.broken)
        repeat(20) { see(DdzSeat.Left) }
        left = DdzSeen.Cards(listOf("5"))
        repeat(2) { see(DdzSeat.Me) }
        assertNull(tracker.landlord)
        assertEquals("unsure_first_play", tracker.broken)
    }

    @Test fun cardsStillFlyingAreReadOnALaterFrame() {
        left = DdzSeen.Cards(listOf("3"))
        repeat(4) { see(DdzSeat.Me) }          // 左家是地主,出完轮到我
        tracker.mine(listOf("4"))
        left = DdzSeen.Empty
        see(DdzSeat.Left)        // 右家出完,牌还没落下
        assertEquals(2, tracker.history.size)
        right = DdzSeen.Cards(listOf("K"))
        repeat(3) { see(DdzSeat.Left) }
        assertEquals(listOf(listOf("3"), listOf("4"), listOf("K")), tracker.history)
    }

    @Test fun anUnreadableMoveStopsTheGameForDouZero() {
        left = DdzSeen.Cards(listOf("3"))
        repeat(4) { see(DdzSeat.Me) }
        tracker.mine(listOf("4"))
        right = DdzSeen.Unsure
        see(DdzSeat.Left)
        // 正在落下的牌一时认不准:再读几帧
        assertNull(tracker.broken)
        repeat(20) { see(DdzSeat.Left) }
        assertEquals("unsure_right", tracker.broken)
        assertFalse(tracker.ready())
    }

    @Test fun aCardStillLandingIsReadOnceItSettles() {
        left = DdzSeen.Cards(listOf("3"))
        repeat(4) { see(DdzSeat.Me) }
        tracker.mine(listOf("4"))
        right = DdzSeen.Unsure
        see(DdzSeat.Left)
        right = DdzSeen.Cards(listOf("10"))
        repeat(3) { see(DdzSeat.Left) }
        assertNull(tracker.broken)
        assertEquals(listOf(listOf("3"), listOf("4"), listOf("10")), tracker.history)
    }

    @Test fun aMoveNeverSeenStopsAfterAFewFrames() {
        left = DdzSeen.Cards(listOf("3"))
        repeat(4) { see(DdzSeat.Me) }
        tracker.mine(listOf("4"))
        left = DdzSeen.Empty
        see(DdzSeat.Left)
        repeat(20) { see(DdzSeat.Left) }
        assertEquals("unread_right", tracker.broken)
    }

    @Test fun leadingWithTwentyCardsUndoesAPlayMisreadBeforeIt() {
        right = DdzSeen.Cards(listOf("?9,Q,6"))
        repeat(3) { see(null) }
        assertEquals(DdzSeat.Right, tracker.landlord)
        tracker.observe(DdzSeat.Me, { DdzSeen.Empty }, { bottom }, leadingWith20 = true, leading = true)
        assertEquals(DdzSeat.Me, tracker.landlord)
        assertTrue(tracker.history.isEmpty())
        assertTrue(tracker.ready())
    }

    @Test fun myButtonsLingeringRightAfterIPlayAreNotMyNextTurn() {
        tracker.observe(DdzSeat.Me, { DdzSeen.Empty }, { bottom }, leadingWith20 = true, leading = true)
        tracker.mine(listOf("9", "10", "J", "Q", "K"))
        // 出牌按钮还留在画面上、两家的牌还没出:不能当成两家都不要、又轮到我
        repeat(4) { tracker.observe(DdzSeat.Me, { DdzSeen.Empty }, { bottom }, leading = true) }
        assertEquals(1, tracker.history.size)
        assertFalse(tracker.ready())
        // 右家出了更大的顺子,左家不要,这才轮到我
        right = DdzSeen.Cards("A K Q J 10".split(' '))
        left = DdzSeen.Pass
        see(DdzSeat.Right)
        repeat(6) { see(DdzSeat.Me) }
        assertEquals(listOf("A", "K", "Q", "J", "10"), tracker.history[1])
        assertEquals(emptyList<String>(), tracker.history[2])
        assertTrue(tracker.ready())
    }

    @Test fun aPassSeenForOneFrameIsNotYetAPass() {
        tracker.observe(DdzSeat.Me, { DdzSeen.Empty }, { bottom }, leadingWith20 = true, leading = true)
        // 真正的跟牌必须压得过:同是顺子,大的一手才跟得上
        tracker.mine("7 6 5 4 3".split(' '))
        see(DdzSeat.Right)
        // 右家出的牌刚飞出来,缩在头像边上,那一帧像「不出」
        right = DdzSeen.Pass
        see(DdzSeat.Left)
        right = DdzSeen.Cards("8 7 6 5 4".split(' '))
        repeat(3) { see(DdzSeat.Left) }
        assertEquals(listOf("8", "7", "6", "5", "4"), tracker.history[1])
    }

    @Test fun cardsStackedWhileFlyingInAreNotTakenForTheWholePlay() {
        // 地主出 7773:飞过来时先叠成一摞,那一帧只看得到最上面带绶带的 3
        left = DdzSeen.Cards(listOf("3"))
        see(DdzSeat.Left)
        left = DdzSeen.Cards("7 7 7 3".split(' '))
        repeat(4) { see(DdzSeat.Me) }
        assertEquals(listOf(listOf("7", "7", "7", "3")), tracker.history)
    }

    @Test fun aTurnTheGameSkipsForMeIsMyPass() {
        // 右家是地主,出了 3;左家不要;我出顺子,地主王炸,左家不要,我管不了,游戏不轮到我,地主接着出 9
        right = DdzSeen.Cards(listOf("3"))
        repeat(3) { see(DdzSeat.Left) }
        left = DdzSeen.Pass
        repeat(4) { see(DdzSeat.Me) }
        tracker.mine("8 9 10 J Q".split(' '))
        right = DdzSeen.Cards(listOf("SJ", "BJ"))
        repeat(3) { see(DdzSeat.Left) }
        repeat(3) { see(DdzSeat.Right) }
        assertEquals(listOf(listOf("SJ", "BJ"), emptyList(), emptyList()), tracker.history.takeLast(3))
        right = DdzSeen.Cards(listOf("9"))
        left = DdzSeen.Cards(listOf("10"))
        repeat(3) { see(DdzSeat.Left) }
        repeat(4) { see(DdzSeat.Me) }
        assertEquals(listOf(listOf("9"), listOf("10")), tracker.history.takeLast(2))
        assertEquals(8, tracker.history.size)
        assertTrue(tracker.ready())
    }

    @Test fun aPlayClearedBeforeTheClockMovesIsCaughtByTheCount() {
        // 左家地主先出对 3(20 张剩 18);对 4 才跟得上,地主再用对 A 压
        counts = mapOf(DdzSide.Left to 18, DdzSide.Right to 17)
        left = DdzSeen.Cards(listOf("3", "3"))
        repeat(4) { seeCounted(DdzSeat.Me) }
        tracker.mine(listOf("4", "4"))
        // 右家不要、地主马上出对 A:闹钟一直没看到,两家的牌都摆着
        right = DdzSeen.Pass
        left = DdzSeen.Cards(listOf("A", "A"))
        counts = mapOf(DdzSide.Left to 16, DdzSide.Right to 17)
        repeat(6) { seeCounted(null) }
        assertEquals(listOf(listOf("3", "3"), listOf("4", "4"), emptyList(), listOf("A", "A")), tracker.history)
        assertTrue(tracker.ready())
    }

    @Test fun aPassWhoseTextWasClearedIsReadFromTheCount() {
        counts = mapOf(DdzSide.Left to 19, DdzSide.Right to 17)
        left = DdzSeen.Cards(listOf("3"))
        repeat(4) { seeCounted(DdzSeat.Me) }
        tracker.mine(listOf("2"))
        // 右家不要,地主也不要(张数都没少),清了桌,又轮到我:两家的「不出」都没留下
        right = DdzSeen.Empty
        left = DdzSeen.Empty
        seeCounted(DdzSeat.Right)
        repeat(12) { seeCounted(DdzSeat.Left) }
        assertNull(tracker.broken)
        assertEquals(listOf(listOf("3"), listOf("2"), emptyList()), tracker.history)
    }

    @Test fun cardsStillStackedAreNotTakenWhileTheCountSaysMore() {
        // 地主出 7773:叠成一摞时只看得到 3,张数已经少了四张
        counts = mapOf(DdzSide.Left to 16, DdzSide.Right to 17)
        left = DdzSeen.Cards(listOf("3"))
        repeat(6) { seeCounted(DdzSeat.Me) }
        assertTrue(tracker.history.isEmpty())
        left = DdzSeen.Cards("7 7 7 3".split(' '))
        repeat(4) { seeCounted(DdzSeat.Me) }
        assertEquals(listOf(listOf("7", "7", "7", "3")), tracker.history)
    }

    @Test fun aFirstPlayConfirmedByTheCountIsTakenAtOnce() {
        // 接手时地主(右家)的顺子只在桌上留了两帧:张数 20 → 12,正好八张,不用等
        counts = mapOf(DdzSide.Left to 17, DdzSide.Right to 12)
        right = DdzSeen.Cards("A K Q J 10 9 8 7".split(' '))
        left = DdzSeen.Pass
        repeat(2) { seeCounted(DdzSeat.Me) }
        repeat(2) { seeCounted(DdzSeat.Me) }
        assertEquals(DdzSeat.Right, tracker.landlord)
        assertEquals(listOf("A", "K", "Q", "J", "10", "9", "8", "7"), tracker.history.first())
    }

    @Test fun aRocketThatSkipsMeRightAfterItsCountDroppedIsMyPass() {
        // 右家地主出 4445,左家农民王炸:张数一少就当轮到我了;我管不了,闹钟直接到了地主
        counts = mapOf(DdzSide.Left to 17, DdzSide.Right to 16)
        right = DdzSeen.Cards("4 4 4 5".split(' '))
        repeat(2) { seeCounted(null) }
        left = DdzSeen.Pass
        repeat(4) { seeCounted(DdzSeat.Me) }
        tracker.mine(emptyList())
        right = DdzSeen.Pass
        left = DdzSeen.Cards(listOf("BJ", "SJ"))
        counts = mapOf(DdzSide.Left to 15, DdzSide.Right to 16)
        repeat(3) { seeCounted(null) }
        repeat(3) { seeCounted(DdzSeat.Right) }
        assertEquals(listOf(listOf("BJ", "SJ"), emptyList()), tracker.history.takeLast(2))
        assertEquals(6, tracker.history.size)
    }

    @Test fun aPassTheGameMadeForSomeoneLeavesTheirLastPlayOnTheTable() {
        // 右家地主出 3(剩 19);左家不要;我出 2;右家小王;左家大王;我管不了;右家也管不了,桌上还摆着他的小王
        counts = mapOf(DdzSide.Left to 17, DdzSide.Right to 19)
        right = DdzSeen.Cards(listOf("3"))
        repeat(2) { seeCounted(null) }
        left = DdzSeen.Pass
        repeat(4) { seeCounted(DdzSeat.Me) }
        tracker.mine(listOf("2"))
        right = DdzSeen.Cards(listOf("SJ"))
        counts = mapOf(DdzSide.Left to 17, DdzSide.Right to 18)
        repeat(3) { seeCounted(DdzSeat.Left) }
        left = DdzSeen.Cards(listOf("BJ"))
        counts = mapOf(DdzSide.Left to 16, DdzSide.Right to 18)
        repeat(3) { seeCounted(null) }
        repeat(20) { seeCounted(DdzSeat.Left) }
        assertNull(tracker.broken)
        assertEquals(listOf(listOf("SJ"), listOf("BJ"), emptyList(), emptyList()), tracker.history.takeLast(4))
    }

    @Test fun oneFrameThatLooksLikeMyTurnDoesNotMoveTheTurn() {
        // 我是地主出顺子,右家炸弹;炸弹的特效有一帧像我的按钮,接着轮到左家:不能给我记一手不出
        repeat(2) { tracker.observe(DdzSeat.Me, { DdzSeen.Empty }, { bottom }, leadingWith20 = true, leading = true) }
        tracker.mine("3 4 5 6 7 8 9".split(' '))
        right = DdzSeen.Cards("10 10 10 10".split(' '))
        repeat(3) { see(DdzSeat.Left) }
        see(DdzSeat.Me)
        repeat(3) { see(DdzSeat.Left) }
        assertNull(tracker.broken)
        assertEquals(2, tracker.history.size)
    }

    @Test fun aGuessedCardIsOnlyTrustedFromTheLandlord() {
        // 我是地主,左家是农民:他那张认不准的不能按猜的补
        tracker.observe(DdzSeat.Me, { DdzSeen.Empty }, { bottom }, leadingWith20 = true, leading = true)
        tracker.mine(listOf("9"))
        right = DdzSeen.Pass
        left = DdzSeen.Cards(listOf("?4,K,J"))
        see(null)
        repeat(21) { see(DdzSeat.Me) }
        assertEquals("unsure_left", tracker.broken)
    }

    /** 我是地主先出一对 4,两家都还没出:右家那一手从第一帧起就是我们要读的。 */
    private fun iLeadAPairOfFours() {
        tracker.observe(DdzSeat.Me, { DdzSeen.Empty }, { bottom }, leadingWith20 = true, leading = true)
        tracker.mine(listOf("4", "4"))
    }

    @Test fun aFarmerPairWithOneUnsureCardIsFilledWhenOnlyOneGuessFits() {
        // Live 2026-11-12 11:14: 右家压一对 2 读成 [2, ?2,5,3]。猜的 5、3 都成不了牌型,只有 2 是一对。
        iLeadAPairOfFours()
        right = DdzSeen.Cards(listOf("2", "?2,5,3"))
        repeat(4) { see(DdzSeat.Left) }
        assertNull(tracker.broken)
        assertEquals(listOf(listOf("4", "4"), listOf("2", "2")), tracker.history)
    }

    @Test fun aFarmerPairOfTensWithOneUnsureCardIsFilled() {
        // 同一局的下一手: [10, ?10,K,9],只有 10 补得成一对
        iLeadAPairOfFours()
        right = DdzSeen.Cards(listOf("10", "?10,K,9"))
        repeat(4) { see(DdzSeat.Left) }
        assertNull(tracker.broken)
        assertEquals(listOf("10", "10"), tracker.history.last())
    }

    @Test fun aFourWithTwoWhereEveryGuessFitsStaysUnresolved() {
        // 左家的 3333+2+黑 10 读成 [3,3,3,3,2, ?K,7,J]:K、7、J 都是四带二,认不出是哪张,不能替它定
        iLeadAPairOfFours()
        right = DdzSeen.Cards(listOf("3", "3", "3", "3", "2", "?K,7,J"))
        repeat(20) { see(DdzSeat.Left) }
        assertEquals("unsure_right", tracker.broken)
        assertFalse(tracker.history.any { it.size > 2 })
    }

    @Test fun anAmbiguousLandlordCardStillGoesToDouZero() {
        // 地主(右家)先出,那条绶带压着的牌换成 7、4、J 都是三带一:几种都说得通,还是把 ? 交给 DouZero
        right = DdzSeen.Cards(listOf("5", "5", "5", "?7,4,J"))
        repeat(3) { see(DdzSeat.Left) }
        assertEquals(DdzSeat.Right, tracker.landlord)
        assertEquals(listOf(listOf("5", "5", "5", "?7,4,J")), tracker.history)
    }

    @Test fun whenILeadAgainTheClearedTableMeansBothPassed() {
        // 地主是我,先出的顺子没人要;闯关的牌桌清了桌,两家的「不出」都没留下
        tracker.observe(DdzSeat.Me, { DdzSeen.Empty }, { bottom }, leadingWith20 = true, leading = true)
        tracker.mine(listOf("5", "6", "7", "8", "9"))
        see(DdzSeat.Right)
        // 两家的「不出」各要连着看到几帧
        repeat(6) { tracker.observe(DdzSeat.Me, { DdzSeen.Empty }, { bottom }, leading = true) }
        assertNull(tracker.broken)
        assertTrue(tracker.ready())
        assertEquals(listOf(listOf("5", "6", "7", "8", "9"), emptyList(), emptyList()), tracker.history)
    }

    /**
     * 2026-11-12 实测:右家(地主)先出 4445,左家 5556 压过,我不要;之后右家的出牌区一直读成 [BJ]。
     * 真正出的是王炸 SJ+BJ,单张压不过三带一,这手 [BJ] 不能记进历史。
     */
    private fun liveTrickBeforeTheIllegalRead() {
        right = DdzSeen.Cards("4 4 4 5".split(' '))
        repeat(3) { see(DdzSeat.Left) }
        left = DdzSeen.Cards("5 5 5 6".split(' '))
        repeat(4) { see(DdzSeat.Me) }
        tracker.mine(emptyList())
        assertEquals(
            listOf(listOf("4", "4", "4", "5"), listOf("5", "5", "5", "6"), emptyList()),
            tracker.history,
        )
    }

    @Test fun anIllegalFollowIsNeverRecordedAndFailsAsIllegalRight() {
        liveTrickBeforeTheIllegalRead()
        right = DdzSeen.Cards(listOf("BJ"))
        see(DdzSeat.Left)
        assertNull(tracker.broken)
        repeat(DdzSettlingPolicy.SINGLE.maxUnconfirmedFrames - 1) { see(DdzSeat.Left) }
        assertEquals("illegal_right", tracker.broken)
        assertFalse(tracker.history.contains(listOf("BJ")))
        assertEquals(3, tracker.history.size)
        assertFalse(tracker.ready())
    }

    @Test fun aLegalFollowThatBeatsTheTrickIsStillRecorded() {
        liveTrickBeforeTheIllegalRead()
        right = DdzSeen.Cards(listOf("SJ", "BJ"))
        repeat(DdzSettlingPolicy.SINGLE.steadyFrames) { see(DdzSeat.Left) }
        assertNull(tracker.broken)
        assertEquals(listOf("SJ", "BJ"), tracker.history.last())
    }

    @Test fun aReadWithAnUnknownCardIsNotRejected() {
        liveTrickBeforeTheIllegalRead()
        right = DdzSeen.Cards(listOf("?9"))
        repeat(DdzSettlingPolicy.SINGLE.steadyFrames) { see(DdzSeat.Left) }
        assertNull(tracker.broken)
        assertEquals(listOf("?9"), tracker.history.last())
    }

    @Test fun humanTrackerKeepsAnUnsettledOpponentPlayLongerThanSinglePlayer() {
        val human = DdzTracker(DdzSettlingPolicy.HUMAN)
        val right: DdzSeen = DdzSeen.Unsure
        human.observe(DdzSeat.Me, { DdzSeen.Empty }, { bottom }, leadingWith20 = true)
        human.mine(listOf("4"))
        human.observe(DdzSeat.Left, { if (it == DdzSide.Right) right else DdzSeen.Empty }, { null })
        repeat(DdzSettlingPolicy.SINGLE.maxUnconfirmedFrames) {
            human.observe(DdzSeat.Left, { if (it == DdzSide.Right) right else DdzSeen.Empty }, { null })
        }
        assertNull(human.broken)
        repeat(DdzSettlingPolicy.HUMAN.maxUnconfirmedFrames) {
            human.observe(DdzSeat.Left, { if (it == DdzSide.Right) right else DdzSeen.Empty }, { null })
        }
        assertEquals("unsure_right", human.broken)
    }

    @Test fun theClockComingBackToTheLastLeaderDefersTheFastPassesUntilHeMoves() {
        // 2026-11-13 08:39:26 大师场:地主(右家)出顺子 J109876,左家和我都要不起,闹钟一圈不到一秒又回到右家,
        // 两家的出牌区一直空着(「不出」没读到)。不能一到右家就推两家不出(最后那家的牌可能还在飞);
        // 等右家又出 KKK8(闹钟走开、他亮出新的一手),两家还是空的,才按不出记下。
        val straight = listOf("J", "10", "9", "8", "7", "6")
        tracker.restore(DdzSeat.Right, bottom, listOf(straight), DdzSeat.Left)
        left = DdzSeen.Empty
        right = DdzSeen.Empty
        repeat(3) { see(DdzSeat.Right) }
        assertNull(tracker.broken)
        // 闹钟到了右家、还没走开:先不记
        assertEquals(listOf(straight), tracker.history)
        // 地主紧接着出 KKK8:闹钟右 → 左 → 右,两家又都秒过
        right = DdzSeen.Cards(listOf("K", "K", "K", "8"))
        repeat(3) { see(DdzSeat.Left) }
        assertEquals(listOf(straight, emptyList(), emptyList(), listOf("K", "K", "K", "8")), tracker.history)
        // 再等右家下一手走开,才把这一轮后面的两次不出记下
        right = DdzSeen.Empty
        repeat(3) { see(DdzSeat.Right) }
        see(DdzSeat.Left)
        assertNull(tracker.broken)
        assertEquals(
            listOf(straight, emptyList(), emptyList(), listOf("K", "K", "K", "8"), emptyList(), emptyList()),
            tracker.history,
        )
    }

    @Test fun aFastPassAfterAPendingPlayIsRecordedWhenTheLeaderMovesOn() {
        // 2026-11-13 08:42:45 同一场:地主(右家)出对 2,还没读稳,闹钟已经从右家转了一圈回到右家;
        // 以前左家一直卡在 waiting 里,地主下一手时 advance 判 missed_play。现在等右家下一手走开再记两家不出。
        tracker.restore(
            DdzSeat.Right, bottom,
            listOf(listOf("K", "K"), emptyList(), emptyList()), DdzSeat.Right,
        )
        left = DdzSeen.Empty
        right = DdzSeen.Cards(listOf("2", "2"))
        see(DdzSeat.Left)    // 右家出对 2,牌还在飞;闹钟到左家
        see(DdzSeat.Right)   // 闹钟左 → 右:左家和我都没要到
        see(DdzSeat.Right)   // 对 2 读稳记下
        assertNull(tracker.broken)
        // 闹钟到了右家,先不把两家记成不出
        assertEquals(listOf(listOf("K", "K"), emptyList(), emptyList(), listOf("2", "2")), tracker.history)
        right = DdzSeen.Empty
        see(DdzSeat.Left)    // 右家下一手让闹钟走开:两家出牌区都空着,这才按不出记下
        assertNull(tracker.broken)
        assertEquals(
            listOf(listOf("K", "K"), emptyList(), emptyList(), listOf("2", "2"), emptyList(), emptyList()),
            tracker.history,
        )
    }

    @Test fun anUnreadableMiddleSeatStillFailsAsMissedPlay() {
        // 闹钟回到地主,可左家的出牌区一直认不准(可能真出了一手):不能当成不出,照旧判漏、不记没读到的牌
        val straight = listOf("J", "10", "9", "8", "7", "6")
        tracker.restore(DdzSeat.Right, bottom, listOf(straight), DdzSeat.Left)
        left = DdzSeen.Unsure
        right = DdzSeen.Empty
        see(DdzSeat.Right)   // 左家读不准:不推成不出
        assertNull(tracker.broken)
        see(DdzSeat.Left)
        see(DdzSeat.Right)   // 闹钟又转过左家:左家还卡在队头,判漏
        assertEquals("missed_play", tracker.broken)
        assertEquals(listOf(straight), tracker.history)
    }

    @Test fun thePlayAndPassBehindMyLingeringButtonsAreStillRecorded() {
        // 2026-11-13 14:37:52 土豪场:我出 5678910 后按钮还留在画面上,右家过、左家压 KQJ1098,游戏又替我秒过。
        // 闹钟已经到别家,再按「轮到我」喂进来,这几帧以前全被丢掉:右家的「不出」和左家的 KQJ1098 都没记,
        // 后来把左家的 Q 当成压 5678910 的一手,DouZero 回放对不上(还因为前面有个 ? 报成 ambiguous_card)。
        tracker.restore(
            DdzSeat.Left, bottom,
            listOf(listOf("6", "?5,6,8"), listOf("K", "K"), emptyList(), emptyList()),
            DdzSeat.Me,
        )
        tracker.mine(listOf("5", "6", "7", "8", "9", "10"))
        // 按画面认:先出/跟牌时闹钟藏起来才是我的回合;按钮还留着、闹钟在别家时,连着两帧都指着别家才算
        val turn = PhoneDdzTurn.Turn()
        fun feed(phase: DdzPhase, clock: DdzSide?, l: DdzSeen, r: DdzSeen) {
            left = l
            right = r
            val seat = PhoneDdzTurn.trackedSeat(turn, phase, clock)
            tracker.observe(
                seat,
                { if (it == DdzSide.Left) left else right },
                { bottom },
                leading = PhoneDdzTurn.trackedLeading(phase, seat),
            )
        }
        // 我的按钮还留着,闹钟已经在左家(停了很多帧):右家先亮「不出」
        repeat(4) { feed(DdzPhase.Lead, DdzSide.Left, DdzSeen.Empty, DdzSeen.Pass) }
        // 轮到我看到左家的牌(游戏还没替我出):认准后记下 KQJ1098
        repeat(3) { feed(DdzPhase.Follow, null, DdzSeen.Unsure, DdzSeen.Empty) }
        repeat(3) { feed(DdzPhase.Follow, null, DdzSeen.Cards("K Q J 10 9 8".split(' ')), DdzSeen.Empty) }
        // 游戏替我秒过,闹钟到右家;右家再亮「不出」
        repeat(3) { feed(DdzPhase.Other, DdzSide.Right, DdzSeen.Cards("K Q J 10 9 8".split(' ')), DdzSeen.Empty) }
        repeat(3) { feed(DdzPhase.Other, DdzSide.Left, DdzSeen.Empty, DdzSeen.Pass) }
        // 左家接着领出 Q
        repeat(2) { feed(DdzPhase.Follow, null, DdzSeen.Empty, DdzSeen.Empty) }
        repeat(3) { feed(DdzPhase.Follow, null, DdzSeen.Cards(listOf("Q")), DdzSeen.Empty) }
        assertNull(tracker.broken)
        assertEquals(
            listOf(
                listOf("6", "?5,6,8"), listOf("K", "K"), emptyList(), emptyList(), listOf("5", "6", "7", "8", "9", "10"),
                // 右家过、左家的 KQJ1098、我自动不出、右家再过
                emptyList(), listOf("K", "Q", "J", "10", "9", "8"), emptyList(), emptyList(),
                // 左家领出 Q
                listOf("Q"),
            ),
            tracker.history,
        )
    }

    @Test fun aLateBlowupIsNotTakenForAPassJustBecauseTheClockReachedTheLeader() {
        // 2026-11-13 15:08:06 土豪场:地主(左家)出 ?2,J,5, 我过,右家压 BJ。闹钟已经到左家,右家的牌还在飞,
        // 出牌区一时是空的;以前这时就按「闹钟到了领出的那家」把右家记成不出,接着三家连出三个不出,回放对不上。
        // 三家坐一圈,最后一个等着的人后面总是领出的那家,所以「闹钟到了他那里」本身什么都证明不了。
        tracker.restore(
            DdzSeat.Left, bottom,
            listOf(listOf("3"), emptyList(), listOf("A"), listOf("?2", "J", "5"), emptyList()),
            DdzSeat.Right,
        )
        fun feed(phase: DdzPhase, clock: DdzSide?, l: DdzSeen, r: DdzSeen) {
            left = l
            right = r
            val seat = PhoneDdzTurn.rawSeat(phase, clock)
            tracker.observe(
                seat,
                { if (it == DdzSide.Left) left else right },
                { bottom },
                leading = PhoneDdzTurn.trackedLeading(phase, seat),
            )
        }
        // 闹钟到左家,右家的牌还在飞(一时读成空,一时只有一个认不准的 ?)
        repeat(2) { feed(DdzPhase.Other, DdzSide.Left, DdzSeen.Empty, DdzSeen.Empty) }
        repeat(2) { feed(DdzPhase.Other, DdzSide.Left, DdzSeen.Empty, DdzSeen.Cards(listOf("?"))) }
        // 左家过、轮到我:右家的 BJ 才亮出来
        repeat(6) { feed(DdzPhase.Follow, null, DdzSeen.Pass, DdzSeen.Cards(listOf("BJ"))) }
        assertNull(tracker.broken)
        // 右家出的是 BJ,不是「不出」;之后才是左家的不出
        assertEquals(
            listOf(
                listOf("3"), emptyList(), listOf("A"), listOf("?2", "J", "5"), emptyList(),
                listOf("BJ"), emptyList(),
            ),
            tracker.history,
        )
    }

    @Test fun aLateBombIsNotTakenForAPassJustBecauseTheClockReachedTheLeader() {
        // 2026-11-13 15:11:43 同一场第 3 局:我是地主,左家出 A,我过,右家压炸弹 4444。闹钟已经到左家,
        // 右家的牌还在飞,出牌区一时是空的;以前把右家记成不出,历史成了 [[A],[],[]],回放又对不上。
        tracker.restore(
            DdzSeat.Me, bottom,
            listOf(listOf("Q"), listOf("6"), listOf("A"), emptyList()),
            DdzSeat.Right,
        )
        fun feed(phase: DdzPhase, clock: DdzSide?, l: DdzSeen, r: DdzSeen) {
            left = l
            right = r
            val seat = PhoneDdzTurn.rawSeat(phase, clock)
            tracker.observe(
                seat,
                { if (it == DdzSide.Left) left else right },
                { bottom },
                leading = PhoneDdzTurn.trackedLeading(phase, seat),
            )
        }
        // 闹钟到左家,右家的炸弹还在飞
        repeat(2) { feed(DdzPhase.Other, DdzSide.Left, DdzSeen.Empty, DdzSeen.Empty) }
        // 炸弹亮出来,左家过、轮到我
        repeat(6) { feed(DdzPhase.Follow, null, DdzSeen.Pass, DdzSeen.Cards(listOf("4", "4", "4", "4"))) }
        assertNull(tracker.broken)
        // 右家出的是炸弹,不是「不出」;之后才是左家的不出
        assertEquals(
            listOf(
                listOf("Q"), listOf("6"), listOf("A"), emptyList(),
                listOf("4", "4", "4", "4"), emptyList(),
            ),
            tracker.history,
        )
    }

    @Test fun aSingleFrameClockFlickerDoesNotReplayTheSamePlays() {
        // 2026-11-13 15:30:37 土豪场:左家(地主)出 SJ 压右家的 2,轮到我。牌落下时闹钟闪到左家一帧,
        // 以前(#53 按闹钟认)会当成左家的回合、整整绕一圈,把右家的旧 2 和左家的 SJ 又各记一遍,
        // 历史里两个 SJ,DouZero 报 more_cards_than_exist。单帧闪烁要走 [PhoneDdzTurn.trackedSeat] 才不算。
        tracker.restore(
            DdzSeat.Left, bottom,
            listOf(listOf("K"), emptyList(), listOf("2")),
            DdzSeat.Left,
        )
        val turn = PhoneDdzTurn.Turn()
        fun feed(phase: DdzPhase, clock: DdzSide?, l: DdzSeen, r: DdzSeen) {
            left = l
            right = r
            val seat = PhoneDdzTurn.trackedSeat(turn, phase, clock)
            tracker.observe(
                seat,
                { if (it == DdzSide.Left) left else right },
                { bottom },
                leading = PhoneDdzTurn.trackedLeading(phase, seat),
            )
        }
        // 左家压 SJ(牌还在飞,一时读成 ?10,9,4);我这边的闹钟同时闪到左家一帧
        feed(DdzPhase.Follow, null, DdzSeen.Cards(listOf("?10", "9", "4")), DdzSeen.Cards(listOf("2")))
        feed(DdzPhase.Follow, DdzSide.Left, DdzSeen.Empty, DdzSeen.Cards(listOf("2")))
        // 还是我的回合:SJ 读稳记下
        repeat(3) { feed(DdzPhase.Follow, null, DdzSeen.Cards(listOf("SJ")), DdzSeen.Cards(listOf("2"))) }
        // 我过;右家的旧 2 还摆在桌上,右家也过(那份旧 2 不能再记成一手)
        feed(DdzPhase.Other, DdzSide.Right, DdzSeen.Cards(listOf("SJ")), DdzSeen.Cards(listOf("2")))
        repeat(3) { feed(DdzPhase.Other, DdzSide.Left, DdzSeen.Empty, DdzSeen.Cards(listOf("2"))) }
        assertNull(tracker.broken)
        assertEquals(
            listOf(listOf("K"), emptyList(), listOf("2"), listOf("SJ"), emptyList(), emptyList()),
            tracker.history,
        )
    }
}
