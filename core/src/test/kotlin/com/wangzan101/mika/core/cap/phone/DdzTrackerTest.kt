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
        tracker.mine(listOf("6"))
        see(DdzSeat.Right)
        // 右家出的牌刚飞出来,缩在头像边上,那一帧像「不出」
        right = DdzSeen.Pass
        see(DdzSeat.Left)
        right = DdzSeen.Cards("7 6 5 4 3".split(' '))
        repeat(3) { see(DdzSeat.Left) }
        assertEquals(listOf("7", "6", "5", "4", "3"), tracker.history[1])
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
        // 左家地主先出 3(20 张剩 19)
        counts = mapOf(DdzSide.Left to 19, DdzSide.Right to 17)
        left = DdzSeen.Cards(listOf("3"))
        repeat(4) { seeCounted(DdzSeat.Me) }
        tracker.mine(listOf("4"))
        // 右家不要、地主马上出对 A:闹钟一直没看到,两家的牌都摆着
        right = DdzSeen.Pass
        left = DdzSeen.Cards(listOf("A", "A"))
        counts = mapOf(DdzSide.Left to 17, DdzSide.Right to 17)
        repeat(6) { seeCounted(null) }
        assertEquals(listOf(listOf("3"), listOf("4"), emptyList(), listOf("A", "A")), tracker.history)
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
}
