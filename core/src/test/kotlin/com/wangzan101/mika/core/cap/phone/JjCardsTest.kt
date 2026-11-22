package com.wangzan101.mika.core.cap.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JjCardsTest {
    /** 点数框按录屏里量到的大小摆:手牌字高约 78,别家出的约 50,底牌约 31。 */
    private fun card(rank: String, x: Double, y: Double, h: Double = 78.0, score: Double = 0.99) =
        JjCard(rank, x, y, x + 40, y + h, score)

    private val width = 2408

    @Test fun splitsHandPlaysAndBottom() {
        val cards = listOf(
            card("A", 857.0, 15.0, 31.0), card("K", 927.0, 15.0, 31.0), card("2", 996.0, 15.0, 31.0),
            card("2", 673.0, 272.0, 53.0), card("2", 729.0, 272.0, 53.0),          // 左家出的对 2
            card("PASS", 1560.0, 200.0, 50.0),                                      // 右家不出
            card("3", 1660.0, 735.0), card("4", 1469.0, 770.0), card("BJ", 478.0, 775.0, 178.0),
        )
        val t = JjCards.table(cards, width)
        assertEquals(listOf("A", "K", "2"), t.bottom)
        assertEquals(DdzSeen.Cards(listOf("2", "2")), t.left)
        assertEquals(DdzSeen.Pass, t.right)
        assertEquals(DdzSeen.Empty, t.mine)
        assertEquals(listOf("BJ", "4", "3"), t.handRanks)
        // 3 的上沿在 735:抬起来了
        assertEquals(setOf(2), t.lifted)
    }

    @Test fun aWideDuplicateTenIsOneCard() {
        // 真机 11:11:52 /cards 原样:右家 5–J 的顺子,10 多认出一个盖到 9 上的宽框
        val cards = listOf(
            JjCard("J", 1296.3, 270.4, 1328.4, 325.3, 0.999),
            JjCard("10", 1335.9, 272.4, 1412.7, 324.5, 0.940),
            JjCard("10", 1352.6, 270.1, 1390.2, 325.1, 1.000),
            JjCard("9", 1411.8, 270.2, 1442.7, 324.9, 1.000),
            JjCard("8", 1467.7, 269.9, 1500.6, 325.5, 0.999),
            JjCard("7", 1529.5, 270.3, 1560.3, 326.0, 1.000),
            JjCard("6", 1583.3, 273.0, 1616.0, 324.9, 1.000),
            JjCard("5", 1641.1, 270.4, 1675.5, 327.0, 1.000),
        )
        assertEquals(DdzSeen.Cards(listOf("J", "10", "9", "8", "7", "6", "5")), JjCards.table(cards, 2412).right)
    }

    @Test fun twoRealTensSideBySideStayTwo() {
        assertEquals(DdzSeen.Cards(listOf("10", "10")), JjCards.table(listOf(card("10", 1651.0, 274.0, 46.0), card("10", 1693.0, 275.0, 46.0)), width).right)
    }

    @Test fun anUnsureCardMakesThePlayUnsure() {
        val t = JjCards.table(listOf(card("9", 1600.0, 280.0, 50.0, score = 0.55), card("9", 1650.0, 280.0, 50.0)), width)
        assertEquals(DdzSeen.Unsure, t.right)
    }

    @Test fun cardsAndPassTogetherAreUnsure() {
        // 上一手的牌还在、不出的气泡又冒出来:动画中间,不算
        val t = JjCards.table(listOf(card("9", 700.0, 280.0, 50.0), card("PASS", 640.0, 200.0, 50.0)), width)
        assertEquals(DdzSeen.Unsure, t.left)
    }

    @Test fun theBottomCardsFlyingThroughTheMiddleAreNobodysPlay() {
        // 2026-11-21 15:41 真机:叫完分 K 6 6 从中间飞到顶栏,按中线分成了「右家出 6」,整局历史错位
        val t = JjCards.table(listOf(card("K", 1046.0, 272.0, 53.0), card("6", 1150.0, 242.0, 53.0), card("6", 1251.0, 268.0, 53.0)), width)
        assertEquals(DdzSeen.Empty, t.left)
        assertEquals(DdzSeen.Empty, t.right)
    }

    @Test fun aLongStraightFromTheLeftStaysLeft() {
        // 左家 3 到 A 的顺子从 x=640 排到 1300 多,伸过了中线
        val ranks = listOf("3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K", "A")
        val t = JjCards.table(ranks.mapIndexed { i, r -> card(r, 640.0 + i * 56, 280.0, 50.0) }, width)
        assertEquals(DdzSeen.Cards(ranks), t.left)
        assertEquals(DdzSeen.Empty, t.right)
    }

    @Test fun theResultOverlayIsNotMyHand() {
        // 每手结算浮层里摆的各家剩牌:字矮(约 50–68)、上沿高(670 起),不是手牌
        val overlay = listOf(card("9", 770.0, 670.0, 68.0), card("8", 440.0, 730.0, 60.0)) +
            (0 until 5).map { card("5", 765.0 + it * 53, 797.0, 58.0) }
        assertTrue(JjCards.table(overlay, width).hand.isEmpty())
    }

    @Test fun bottomNeedsExactlyThreeSureCards() {
        assertNull(JjCards.table(listOf(card("A", 857.0, 15.0, 31.0), card("K", 927.0, 15.0, 31.0)), width).bottom)
    }

    @Test fun myPassBubbleIsMine() {
        val t = JjCards.table(listOf(card("PASS", 680.0, 640.0, 50.0)), width)
        assertEquals(DdzSeen.Pass, t.mine)
        assertTrue(t.hand.isEmpty())
    }

    @Test fun parsesTheServerReply() {
        val body = """{"cards": [{"rank": "10", "box": [855.0, 769.5, 900.2, 848.0], "score": 0.998}], "ms": 59}"""
        val cards = JjCards.parse(body)!!
        assertEquals("10", cards.single().rank)
        assertEquals(855.0, cards.single().x0, 0.0)
        assertNull(JjCards.parse("""{"error": "cards_unavailable"}"""))
    }
}
