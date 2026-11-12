package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.uistate.RawFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

class DdzCardsTest {
    /** A frame with the rows the phone reads; lossless .png when the fixture needs exact whites (borderline cases). */
    private fun fullFrame(name: String): RawFrame {
        val image = ImageIO.read((javaClass.getResourceAsStream("/ddz/$name.png") ?: javaClass.getResourceAsStream("/ddz/$name.jpg"))!!)
        return RawFrame(image.width, image.height, (DdzScreen.ROWS + DdzCards.ROWS + DdzCards.LOBBY_ROWS).distinct().associateWith { image.getRGB(0, it, image.width, 1, null, 0, image.width) })
    }

    @Test fun theLandlordsJokerIsACard() {
        // Live 2026-11-10 run 16: the landlord's big joker (landlord art across its lower half) read
        // as Empty, so the play was missed (missed_play).
        assertEquals(DdzSeen.Cards(listOf("BJ")), DdzCards.play(fullFrame("human_right_landlord_joker"), DdzSide.Right))
    }

    @Test fun theHumanResultScreenIsRecognised() {
        // Live 2026-11-09/10: the 普通场 result (离开 / 换桌 / 继续) was taken for an unknown popup.
        assertTrue(DdzCards.humanResult(fullFrame("human_result_leave_change_continue")))
        for (name in listOf("call", "lead", "hand_t1", "hand_k2", "result", "human_autoplay_left11_right5", "vd_lobby", "vd_relief")) {
            assertFalse(name, DdzCards.humanResult(fullFrame(name)))
        }
    }

    /** 2026-11-11 实测 Mika 屏:金豆输光点普通场,先弹领金豆、再出恭喜获得。两个画面都认得出,点也都落在免费按钮上。 */
    @Test fun theHumanBeanReliefScreensAreRecognisedWithSafeTaps() {
        val claim = DdzCards.humanReliefPopup(fullFrame("human_relief_popup"))
        val normal = DdzCards.humanReliefClaimed(fullFrame("human_relief_claimed"))
        assertNotNull("领金豆弹窗", claim)
        assertNotNull("恭喜获得页", normal)
        // 「领取」黄按钮、右边「普通领取」文字链、绿色「双倍领取」(带视频图标)按实测位置量
        assertTrue("「领取」落在黄按钮上: $claim", claim!!.first in 980..1380 && claim.second in 675..790)
        assertTrue("「普通领取」落在文字链上: $normal", normal!!.first in 1520..1720 && normal.second in 840..900)
        assertTrue("绝不点带视频图标的「双倍领取」: $normal", normal.first !in 1000..1440 || normal.second !in 815..925)
        // 两个画面互相不认错
        assertNull(DdzCards.humanReliefPopup(fullFrame("human_relief_claimed")))
        assertNull(DdzCards.humanReliefClaimed(fullFrame("human_relief_popup")))
    }

    @Test fun theHumanBeanReliefScreensAreNotConfusedWithOtherDdzScreens() {
        // 牌桌、结算页、单机大厅、闯关页、单机救济金、真人场次页都不该被认成这两个画面
        val others = listOf(
            "call", "lead", "hand_t1", "result", "human_result_leave_change_continue", "human_autoplay_left11_right5",
            "vd_lobby", "vd_relief", "vd_result_bust", "vd_stage_call", "vd_stage_win_leave_continue",
            "vd_stage_loss", "vd_stage_continue", "vd_stage_ad_dialog", "human_classic_rooms",
        )
        for (name in others) {
            val f = fullFrame(name)
            assertNull("$name 当成领金豆弹窗", DdzCards.humanReliefPopup(f))
            assertNull("$name 当成恭喜获得页", DdzCards.humanReliefClaimed(f))
        }
    }

    @Test fun theBeansTooManyPopupIsClosedWithTheXAndNothingElseIsConfusedWithIt() {
        val x = DdzCards.humanBeansTooMany(fullFrame("human_room_beans_too_many"))
        assertNotNull("豆豆太多弹窗", x)
        assertTrue("点右上角的叉,不点「立即前往」: $x", x!!.first in 1640..1710 && x.second in 255..315)
        assertNull(DdzCards.humanReliefPopup(fullFrame("human_room_beans_too_many")))
        for (name in listOf("human_relief_popup", "human_relief_claimed", "human_classic_rooms", "human_main_lobby", "human_matching",
            "call", "lead", "result", "vd_lobby", "vd_relief")) {
            assertNull("$name 当成豆豆太多弹窗", DdzCards.humanBeansTooMany(fullFrame(name)))
        }
    }

    /** 真人场次页的场次卡会被读成 Lead、手牌 1 张,但它不是经典牌桌。 */
    @Test fun theHumanRoomsPageIsNotTakenForTheClassicTable() {
        val rooms = fullFrame("human_classic_rooms")
        assertFalse(DdzCards.classicHumanTable(rooms))
        assertEquals(DdzPhase.Lead, DdzScreen.read(rooms, classic = true).phase)
    }

    /** 2026-11-11 21:16 单机经典场:结算上弹「当前金币可前往更高级场次」,只有一个黄色的确定。 */
    @Test fun theHigherRoomPopupIsClosedWithItsOkButton() {
        val ok = DdzCards.singleMoreCoinsPopup(fullFrame("single_more_coins_popup"))
        assertNotNull("更高场次弹窗", ok)
        assertTrue("只点黄色确定: $ok", ok!!.first in 1041..1384 && ok.second in 722..808)
    }

    @Test fun noOtherDdzScreenIsTakenForTheHigherRoomPopup() {
        // 主资源里也有一个 /ddz(样本字),按其中一张测试图找到测试资源的目录
        val dir = File(javaClass.getResource("/ddz/human_main_lobby.png")!!.toURI()).parentFile
        val names = dir.listFiles()!!.map { it.name.substringBeforeLast('.') }.filter { it != "single_more_coins_popup" }
        assertTrue("fixtures: ${names.size}", names.size >= 80)
        for (name in names) assertNull(name, DdzCards.singleMoreCoinsPopup(fullFrame(name)))
    }

    private fun frame(name: String): RawFrame {
        val image = ImageIO.read((javaClass.getResourceAsStream("/ddz/$name.png") ?: javaClass.getResourceAsStream("/ddz/$name.jpg"))!!)
        return RawFrame(image.width, image.height, (DdzScreen.ROWS + DdzCards.ROWS).distinct().associateWith { image.getRGB(0, it, image.width, 1, null, 0, image.width) })
    }

    private fun hand(name: String) = frame(name).let { DdzCards.hand(it, DdzScreen.read(it)) }

    @Test fun readsHandsLeftToRight() {
        assertEquals("SJ 2 2 2 A A A A Q J 10 9 9 8 7 3 3".split(' '), hand("hand_t1"))
        // 闯关牌桌:手牌更高
        assertEquals("BJ SJ 2 2 A A Q Q Q Q J J 10 9 9 5 4".split(' '), hand("vd_stage_call"))
        assertEquals("SJ 2 2 A A Q Q Q Q 9 9 8 5 5 4 4 3".split(' '), hand("vd_stage_farmer17"))
        // 闯关的地主,最后一张压着绶带
        val view = frame("vd_stage_landlord19").let { DdzScreen.read(it) }
        assertEquals("${view.phase} ${view.handCount} ${view.lifted}", "2 2 A A A K Q Q J J J J 8 6 4 4 4 4 3".split(' '), hand("vd_stage_landlord19"))
        assertEquals("BJ SJ 2 2 A A A K Q J J 10 10 9 9 8 5".split(' '), hand("hand_k2"))
    }

    @Test fun readsTheHandOnMikasWiderDisplay() {
        assertEquals("BJ SJ 2 2 A A A K Q J J 10 10 9 9 8 5".split(' '), hand("vd_hand_follow"))
        // 地主 20 张、最后一张带绶带
        assertEquals("BJ SJ 2 2 A A K Q J 10 9 8 7 6 6 4 4 3 3 3".split(' '), hand("vd_landlord_lead"))
    }

    @Test fun readsHintLiftedHumanHand() {
        assertEquals("BJ SJ K Q Q Q 10 7".split(' '), hand("human_hand_hint_raised_qqq107"))
    }

    @Test fun readsWhatEachOpponentPlayed() {
        assertRibbonPlay("5 5", DdzCards.play(frame("table_left55"), DdzSide.Left))
        assertEquals(DdzSeen.Cards(listOf("K", "K")), DdzCards.play(frame("table_rightKK"), DdzSide.Right))
        assertEquals(DdzSeen.Cards(listOf("3", "3")), DdzCards.play(frame("table_66_33"), DdzSide.Right))
    }

    @Test fun readsTenCardHumanRightPlayWithDroppedRibbonCard() {
        assertRibbonPlay("10 10 10 9 9 9 7 7 5 5", DdzCards.play(frame("human_right_landlord_10cards"), DdzSide.Right))
    }

    /** 绶带那张要么认准,要么留成 ? 交给 DouZero 服务按牌规补;别的牌必须认准。 */
    private fun assertRibbonPlay(expected: String, seen: DdzSeen) {
        val ranks = (seen as DdzSeen.Cards).ranks
        val want = expected.split(' ')
        assertEquals(want.dropLast(1), ranks.dropLast(1))
        // 认不准时带的猜测不一定对(99988 的 8 猜成了 7、J、5):多张的牌型由服务按牌规补
        assertTrue("$ranks", ranks.last() == want.last() || ranks.last().startsWith(DdzCards.UNKNOWN))
    }

    @Test fun theLandlordsRibbonCardIsReadOrLeftForTheRules() {
        assertRibbonPlay("6 6", DdzCards.play(frame("table_66_33"), DdzSide.Left))
        assertRibbonPlay("9 9", DdzCards.play(frame("table_left99"), DdzSide.Left))
        assertRibbonPlay("10 10 9 9 8 8", DdzCards.play(frame("hand_t1"), DdzSide.Left))
        assertRibbonPlay("9 9 9 8 8", DdzCards.play(frame("vd_right_99988"), DdzSide.Right))
    }

    @Test fun readsLongPlaysOnMikasScreen() {
        // Live 2026-11-11: played cards sit 51.5 px apart on Mika's screen (53 on the phone's own); 53 × 1.05 drifted off by card 5
        assertEquals(DdzSeen.Cards("A K Q J 10 9 8".split(' ')), DdzCards.play(frame("human_right_straight_A_to_8"), DdzSide.Right))
        assertEquals(DdzSeen.Cards("8 7 6 5 4 3".split(' ')), DdzCards.play(frame("human_right_straight_8_to_3"), DdzSide.Right))
        assertEquals(DdzSeen.Cards("5 5 4 4 3 3".split(' ')), DdzCards.play(frame("human_left_pairs_554433"), DdzSide.Left))
        // At that spacing the rank window must still hold the whole 「10」, or its 「0」 is cut and 10 reads like K
        assertEquals(DdzSeen.Cards("10 10 10 3".split(' ')), DdzCards.play(frame("human_left_10_10_10_3"), DdzSide.Left))
        // Live 2026-11-11 single: a 46 px window cut the right edge of a black 「10」's 「0」 (it reaches column 47),
        // so the settled straight read as unsure for seconds and DouZero handed the hand back (unsure_left)
        assertEquals(DdzSeen.Cards("A K Q J 10 9 8".split(' ')), DdzCards.play(frame("single_left_straight_A_to_8_black_10"), DdzSide.Left))
    }

    @Test fun readsTheLastBlackTenOnMikasScreen() {
        // Live 2026-11-12 10:48 single classic 中级场: the left farmer's four-with-two ended in a black 10.
        // The last card's wide window was trimmed to 47 (wideLast 50 minus a 3px margin meant for the
        // next card's shadow), but this 10's 「0」 reaches column 49; 47 kept only the 「1」, which read
        // K,7,J — the tracker failed unread_left and DouZero sat out. Cap the last card's wide window
        // at pitch (50, no next card) and the full 10 matches the existing sample at 0.17.
        assertEquals(DdzSeen.Cards("3 3 3 3 2 10".split(' ')), DdzCards.play(frame("human_left_3333_2_black10"), DdzSide.Left))
        assertEquals(DdzSeen.Cards("3 3 3 3 2 10".split(' ')), DdzCards.play(frame("human_left_3333_2_black10_f1"), DdzSide.Left))
        assertEquals(DdzSeen.Cards("3 3 3 3 2 10".split(' ')), DdzCards.play(frame("human_left_3333_2_black10_f2"), DdzSide.Left))
    }

    @Test fun readsTheClassicStraightThatWrapsOntoASecondRow() {
        // Live 2026-11-12 11:32 single classic: the landlord led 5 6 7 8 9 10 J Q K A; nine cards sat in
        // one row and the tenth (5♠ with the 地主 ribbon) wrapped onto a second row, so the play read Unsure
        // and the hand failed missed_play. Order is the first row left to right, then the second row.
        assertEquals(DdzSeen.Cards("A K Q J 10 9 8 7 6 5".split(' ')),
            DdzCards.play(frame("single_left_straight_A_to_5_wrapped"), DdzSide.Left))
    }

    @Test fun readsTheRightHandWrappedPlayToo() {
        // 帧里只有左家出过这一手;把左家的出牌区平移到右家(牌形不变,不镜像),右边的第二排也该读出来
        val image = ImageIO.read(javaClass.getResourceAsStream("/ddz/single_left_straight_A_to_5_wrapped.png")!!)
        val shift = 1137
        val rows = (DdzScreen.ROWS + DdzCards.ROWS).distinct().associateWith { y ->
            IntArray(image.width) { x ->
                val from = x - shift
                if (y in 220..419 && from in 280..926) image.getRGB(from, y) else image.getRGB(x, y)
            }
        }
        assertEquals(DdzSeen.Cards("A K Q J 10 9 8 7 6 5".split(' ')),
            DdzCards.play(RawFrame(image.width, image.height, rows), DdzSide.Right))
    }

    @Test fun aRowThatIsNotFullDoesNotGrowASecondRow() {
        // 八张一排、下面再画一块白的(像第二排):九张摆满之前不会换排,不能凭空多读一张
        val image = ImageIO.read(javaClass.getResourceAsStream("/ddz/single_left_straight_A_to_8_black_10.png")!!)
        val rows = (DdzScreen.ROWS + DdzCards.ROWS).distinct().associateWith { y ->
            IntArray(image.width) { x -> if (y in 405..419 && x in 340..460) -1 else image.getRGB(x, y) }
        }
        assertEquals(DdzSeen.Cards("A K Q J 10 9 8".split(' ')),
            DdzCards.play(RawFrame(image.width, image.height, rows), DdzSide.Left))
    }

    @Test fun readsSettledRedTenPairOnMikasScreen() {
        assertEquals(DdzSeen.Cards(listOf("10", "10")), DdzCards.play(frame("human_left_pair_10_red"), DdzSide.Left))
    }

    @Test fun readsTheRedEightInTheLandlordsStraight() {
        // Live 2026-11-11 21:43 single classic: the red 8 in the landlord's 10-9-8-7-6 sat only 0.069
        // from 3 (margin is 0.08), so every frame read Unsure and the tracker failed (unsure_right);
        // a null in the middle of a play spoils the whole hand, unlike the ribbon's trailing ?
        for (name in listOf("single_right_straight_10_to_6_red8_first", "single_right_straight_10_to_6_red8", "single_right_straight_10_to_6_red8_fail")) {
            assertEquals(name, DdzSeen.Cards("10 9 8 7 6".split(' ')), DdzCards.play(frame(name), DdzSide.Right))
        }
    }

    @Test fun doesNotInventRankWhileRedTenPairIsLanding() {
        val seen = DdzCards.play(frame("human_left_pair_10_red_landing"), DdzSide.Left)
        assertTrue("$seen", seen == DdzSeen.Unsure ||
            (seen is DdzSeen.Cards && seen.ranks.all { it == "10" || it.startsWith(DdzCards.UNKNOWN) }))
    }

    @Test fun aSingleRibbonCardItCannotReadIsLeftForTheRulesWithGuesses() {
        val frame = frame("table_left10_right8")
        // 单张什么都说得通,只能靠猜:最像的就是 10
        assertEquals(DdzSeen.Cards(listOf("?10,K,9")), DdzCards.play(frame, DdzSide.Left))
        assertEquals(DdzSeen.Cards(listOf("8")), DdzCards.play(frame, DdzSide.Right))
    }

    @Test fun aGreyedOutEarlierPlayIsNotCounted() {
        val frame = frame("table_leftdim_rightQ")
        assertEquals(DdzSeen.Empty, DdzCards.play(frame, DdzSide.Left))
        assertEquals(DdzSeen.Cards(listOf("Q")), DdzCards.play(frame, DdzSide.Right))
    }

    @Test fun readsTheBiggerHigherPlaysOnAStageTable() {
        val frame = frame("vd_stage_leftpass_rightQQQ6")
        assertEquals(DdzSeen.Pass, DdzCards.play(frame, DdzSide.Left))
        assertEquals(DdzSeen.Cards("Q Q Q 6".split(' ')), DdzCards.play(frame, DdzSide.Right))
        assertEquals(null, DdzCards.clock(frame))
        // 自己刚出的顺子在中间,不算任何一家的;右家的闹钟在他张数框的里侧
        val clock = frame("vd_stage_right_clock")
        assertEquals(DdzSeen.Empty, DdzCards.play(clock, DdzSide.Left))
        assertEquals(DdzSeen.Empty, DdzCards.play(clock, DdzSide.Right))
        assertEquals(DdzSide.Right, DdzCards.clock(clock))
        // 右家单出一张 10(两位数,比别的点数宽);左家地主的 Q 压着绶带
        val ten = frame("vd_stage_right10_leftQ")
        assertEquals(DdzSeen.Cards(listOf("10")), DdzCards.play(ten, DdzSide.Right))
        assertEquals(DdzSeen.Cards(listOf("10")), DdzCards.play(frame("vd_stage_right10c_leftpass"), DdzSide.Right))
        // 右家出的顺子 10-A,不是「不出」
        val straight = frame("vd_stage_right_straight_leftpass")
        assertEquals(DdzSeen.Cards("A K Q J 10".split(' ')), DdzCards.play(straight, DdzSide.Right))
        assertEquals(DdzSeen.Pass, DdzCards.play(straight, DdzSide.Left))
        // 右家新一轮先出的顺子;左家的「不出」是上一轮留下的
        val lead = frame("vd_stage_right_lead76543")
        assertEquals(DdzSeen.Cards("7 6 5 4 3".split(' ')), DdzCards.play(lead, DdzSide.Right))
        // 七张的顺子,右下角压着「顺子」特效字
        assertEquals(DdzSeen.Cards("Q J 10 9 8 7 6".split(' ')), DdzCards.play(frame("vd_stage_right_straight7_effect"), DdzSide.Right))
        // 叫完地主在正中翻开的三张底牌,不是谁出的牌
        val reveal = frame("vd_stage_bottom_reveal")
        assertEquals(DdzSeen.Empty, DdzCards.play(reveal, DdzSide.Left))
        assertEquals(DdzSeen.Empty, DdzCards.play(reveal, DdzSide.Right))
        // 左家的牌贴着他的张数框
        val joker = frame("vd_stage_leftjoker_rightpass")
        assertEquals(DdzSeen.Pass, DdzCards.play(joker, DdzSide.Right))
        // 红字的王是大王,闯关的大牌上红得发暗
        assertEquals(DdzSeen.Cards(listOf("BJ")), DdzCards.play(joker, DdzSide.Left))
        assertEquals(DdzSeen.Cards(listOf("5")), DdzCards.play(frame("vd_stage_left5_ribbon"), DdzSide.Left))
    }

    @Test fun readsHowManyCardsTheOthersHaveLeft() {
        // 地主的框在帽子下面低一截;一位数、两位数都有
        assertEquals(mapOf(DdzSide.Left to 16, DdzSide.Right to 19), DdzCards.counts(frame("vd_stage_right3_ribbon_left6")))
        assertEquals(mapOf(DdzSide.Left to 9, DdzSide.Right to 17), DdzCards.counts(frame("vd_stage_left2_ribbon")))
        assertEquals(mapOf(DdzSide.Left to 10, DdzSide.Right to 17), DdzCards.counts(frame("vd_stage_left_wrap10")))
        assertEquals(mapOf(DdzSide.Left to 7, DdzSide.Right to 2), DdzCards.counts(frame("vd_stage_3cards_joker_lifted")))
        assertEquals(mapOf(DdzSide.Left to 17, DdzSide.Right to 17), DdzCards.counts(frame("vd_stage_bottom_Q410")))
    }

    @Test fun readsHowManyCardsTheOthersHaveLeftOnTheClassicTable() {
        // 经典牌桌的头像边上也有牌背小框:地主的帽子在左边或右边,框的位置都不变
        assertEquals(mapOf(DdzSide.Left to 1, DdzSide.Right to 4), DdzCards.counts(frame("counts_L1_R4")))
        assertEquals(mapOf(DdzSide.Left to 16, DdzSide.Right to 17), DdzCards.counts(frame("counts_L16_R17")))
        assertEquals(mapOf(DdzSide.Left to 4, DdzSide.Right to 8), DdzCards.counts(frame("counts_L4_R8")))
        assertEquals(mapOf(DdzSide.Left to 4, DdzSide.Right to 17), DdzCards.counts(frame("counts_L4_R17")))
    }

    @Test fun readsAPlayThatWrapsOntoASecondRow() {
        // 地主出了十张(101010 999 66 33):第一排九张,最后一张带绶带的 3 摆在第二排
        val wrap = DdzCards.play(frame("vd_stage_left_wrap10"), DdzSide.Left)
        assertTrue("$wrap", wrap is DdzSeen.Cards && wrap.ranks.size == 10)
        val ranks = (wrap as DdzSeen.Cards).ranks
        assertEquals("10 10 10 9 9 9 6 6 3".split(' '), ranks.take(9))
        assertTrue("$wrap", ranks.last() == "3" || (ranks.last().startsWith("?") && "3" in ranks.last()))
    }

    @Test fun readsRibbonedSinglesOnTheStage() {
        val right3 = frame("vd_stage_right3_ribbon_left6")
        assertEquals(DdzSeen.Cards(listOf("6")), DdzCards.play(right3, DdzSide.Left))
        val three = DdzCards.play(right3, DdzSide.Right)
        assertTrue("$three", three == DdzSeen.Cards(listOf("3")) || (three is DdzSeen.Cards && three.ranks.single().startsWith("?") && "3" in three.ranks.single()))
        // 左家出王炸:王的竖排红字压在左边认位置的那一列上
        assertEquals(DdzSeen.Cards(listOf("BJ", "SJ")), DdzCards.play(frame("vd_stage_left_rocket"), DdzSide.Left))
        // 地主出 44433,最后一张带绶带的红 3 被认成了 J
        val trio = DdzCards.play(frame("vd_stage_left44433_ribbon"), DdzSide.Left)
        assertTrue("$trio", trio is DdzSeen.Cards && trio.ranks.take(4) == listOf("4", "4", "4", "3") &&
            (trio.ranks.last() == "3" || (trio.ranks.last().startsWith("?") && "3" in trio.ranks.last())))
        val two = DdzCards.play(frame("vd_stage_left2_ribbon"), DdzSide.Left)
        assertTrue("$two", two == DdzSeen.Cards(listOf("2")) || (two is DdzSeen.Cards && two.ranks.single().startsWith("?") && "2" in two.ranks.single()))
    }

    @Test fun tellsAPassFromAnEmptyArea() {
        val frame = frame("table_leftpass")
        assertEquals(DdzSeen.Pass, DdzCards.play(frame, DdzSide.Left))
        assertEquals(DdzSeen.Empty, DdzCards.play(frame, DdzSide.Right))
    }

    @Test fun readsTheBottomCards() {
        assertEquals(listOf("9", "8", "3"), DdzCards.bottom(frame("table_left55")))
        // 6 和 5 在底牌那么小的字上只差几笔
        assertEquals(listOf("6", "9", "J"), DdzCards.bottom(frame("vd_bottom_69J")))
        // 闯关牌桌:三张分开摆在顶上正中
        assertEquals(listOf("3", "8", "7"), DdzCards.bottom(frame("vd_stage_landlord19")))
        assertEquals(listOf("K", "4", "9"), DdzCards.bottom(frame("vd_stage_lead")))
        assertEquals(listOf("Q", "4", "10"), DdzCards.bottom(frame("vd_stage_bottom_Q410")))
        assertEquals(listOf("9", "4", "5"), DdzCards.bottom(frame("vd_stage_leftpass_rightQQQ6")))
    }

    @Test fun seesWhoseTurnItIs() {
        assertEquals(DdzSide.Right, DdzCards.clock(frame("table_left55")))
        assertEquals(DdzSide.Left, DdzCards.clock(frame("table_rightKK")))
        assertNull(DdzCards.clock(frame("hand_t1")))
    }

    /** 2026-11-11 16:13:金豆输光后冷启动,游戏开在商店的金豆页;真人 start 不认得它,一下都没点就放弃了。 */
    @Test fun theBeanStorePageIsRecognised() {
        assertTrue(DdzCards.humanShop(fullFrame("human_shop_beans")))
    }

    @Test fun noOtherDdzScreenIsTakenForTheBeanStorePage() {
        // 主资源里也有一个 /ddz(样本字),按其中一张测试图找到测试资源的目录
        val dir = File(javaClass.getResource("/ddz/human_main_lobby.png")!!.toURI()).parentFile
        val names = dir.listFiles()!!.map { it.name.substringBeforeLast('.') }.filter { it != "human_shop_beans" }
        assertTrue("fixtures: ${names.size}", names.size >= 80)
        for (name in names) assertFalse(name, DdzCards.humanShop(fullFrame(name)))
    }
}
