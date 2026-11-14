package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.uistate.RawFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.imageio.ImageIO

class DdzScreenTest {
    private fun frame(name: String): RawFrame {
        val image = ImageIO.read(javaClass.getResourceAsStream("/ddz/$name.jpg")
            ?: javaClass.getResourceAsStream("/ddz/$name.png")!!)
        return RawFrame(image.width, image.height, DdzScreen.ROWS.associateWith { image.getRGB(0, it, image.width, 1, null, 0, image.width) })
    }

    @Test fun recognisesEveryPhase() {
        assertEquals(DdzPhase.Result, DdzScreen.read(frame("result")).phase)
        assertEquals(DdzPhase.Call, DdzScreen.read(frame("call")).phase)
        // 闯关里别人叫过二分:一分、二分变灰,字把按钮颜色切断的那一行认不出
        val stage = DdzScreen.read(frame("vd_stage_call"))
        assertEquals(DdzPhase.Call, stage.phase)
        assertEquals(listOf(DdzColor.Blue, DdzColor.Gray, DdzColor.Gray, DdzColor.Yellow), stage.buttons.map { it.color })
        // 同样的叫分,470 那行只剩三分一段黄色:不是出牌
        assertEquals(DdzPhase.Call, DdzScreen.read(frame("vd_stage_call_ad")).phase)
        val cannotBeat = DdzScreen.read(frame("vd_stage_leftpass_rightQQQ6"))
        assertEquals(DdzPhase.Follow, cannotBeat.phase)
        assertTrue(DdzScreen.onlyPass(cannotBeat))
        val lead = DdzScreen.read(frame("vd_stage_lead"))
        assertEquals(DdzPhase.Lead, lead.phase)
        assertEquals(20, lead.handCount)
        val joker = DdzScreen.read(frame("vd_stage_joker555"))
        // 模型自己点过最后那张 4;闯关的手牌整体高 32 像素
        assertEquals(setOf(19), lead.lifted)
        assertEquals(emptySet<Int>(), DdzScreen.read(frame("vd_stage_call")).lifted)
        assertEquals(DdzPhase.Double, DdzScreen.read(frame("double")).phase)
        assertEquals(DdzPhase.Follow, DdzScreen.read(frame("follow_lifted")).phase)
        assertEquals(DdzPhase.Lead, DdzScreen.read(frame("lead")).phase)
        assertEquals(DdzPhase.Lead, DdzScreen.read(frame("lead_lifted")).phase)
    }

    @Test fun readsTheHandOnMikasFullWidthDisplay() {
        val view = DdzScreen.read(frame("vd_17"))
        assertEquals(101.0, view.pitch, 1.0)
        assertEquals(17, view.handCount)
        assertEquals(emptySet<Int>(), view.lifted)
        assertEquals(20, DdzScreen.read(frame("vd_20")).handCount)
    }

    @Test fun seesALiftedCardOnMikasDisplayEvenWithTheLandlordRibbon() {
        val view = DdzScreen.read(frame("vd_lifted"))
        assertEquals(19, view.handCount)
        assertEquals(setOf(2), view.lifted)
    }

    @Test fun reliefPopupIsNeverTakenForTheResultScreen() {
        assertEquals(DdzPhase.Other, DdzScreen.read(frame("vd_relief")).phase)
    }

    @Test fun playAgainIsTheYellowButtonNotTheGreenAdButton() {
        val view = DdzScreen.read(frame("vd_result_bust"))
        assertEquals(DdzPhase.Result, view.phase)
        assertEquals(true, view.buttons.single().x < 1300)
    }

    @Test fun lobbyIsNotATable() {
        assertEquals(DdzPhase.Other, DdzScreen.read(frame("lobby")).phase)
    }

    @Test fun seesAutoPlayAndItsCancelButton() {
        val view = DdzScreen.read(frame("auto"))
        assertEquals(DdzPhase.AutoPlay, view.phase)
        assertEquals(true, view.buttons.single().x in 1200..1330)
        assertEquals(760, view.buttons.single().y)
    }

    @Test fun aCardHeldDownStillCounts() {
        assertEquals(12, DdzScreen.read(frame("lead_pressed")).handCount)
    }

    @Test fun countsTheHandWithCardsLifted() {
        assertEquals(17, DdzScreen.read(frame("call")).handCount)
        assertEquals(17, DdzScreen.read(frame("follow_lifted")).handCount)
        assertEquals(17, DdzScreen.read(frame("follow_left_lifted")).handCount)
        assertEquals(15, DdzScreen.read(frame("lead")).handCount)
        assertEquals(15, DdzScreen.read(frame("lead_lifted")).handCount)
        assertEquals(9, DdzScreen.read(frame("lead_small")).handCount)
    }

    @Test fun seesWhichCardsAreLifted() {
        assertEquals(emptySet<Int>(), DdzScreen.read(frame("lead")).lifted)
        assertEquals(setOf(5, 6), DdzScreen.read(frame("follow_lifted")).lifted)
        assertEquals(setOf(14), DdzScreen.read(frame("lead_lifted")).lifted)
    }

    @Test fun seesHintLiftedCardsOnMikasDisplay() {
        assertEquals(emptySet<Int>(), DdzScreen.read(frame("human_hand_jokers_flat")).lifted)
        // 轮到我时游戏自己把提示的 Q Q Q 7 抬高 17 像素;10 没抬,它上方露出的是左边抬起那张 Q。大半手牌抬着也不是闯关牌桌
        val hint = DdzScreen.read(frame("human_hand_hint_raised_qqq107"))
        assertEquals(setOf(3, 4, 5, 7), hint.lifted)
        assertEquals(DdzScreen.HAND_ROW, hint.handRow)
    }

    @Test fun wholeClassicHandLiftedByOurTapIsNotTakenForTheStageTable() {
        // 整手牌被我们点高 30 像素(最后一对、最后一手顺子):牌顶 614,620 行大半是牌面,和闯关牌桌的 612 分不开
        val image = ImageIO.read(javaClass.getResourceAsStream("/ddz/human_hand_jokers_flat.png")!!)
        val all = RawFrame(image.width, image.height, DdzScreen.ROWS.associateWith { y ->
            IntArray(image.width) { x -> if (y >= 560 && x in 744..1673) image.getRGB(x, y + 30) else image.getRGB(x, y) }
        })
        // 默认读法把它当成闯关牌桌:手牌行错位、抬起的牌读成空
        assertEquals(emptySet<Int>(), DdzScreen.read(all).lifted)
        // 真人局永远是经典牌桌,不看闯关
        val classic = DdzScreen.read(all, classic = true)
        assertEquals((0 until 8).toSet(), classic.lifted)
        assertEquals(DdzScreen.HAND_ROW, classic.handRow)
    }

    @Test fun playButtonIsLastInFollowAndOnlyOneInLead() {
        val follow = DdzScreen.read(frame("follow_lifted"))
        assertEquals(3, follow.buttons.size)
        assertEquals(DdzColor.Blue, follow.buttons[0].color)
        assertEquals(1, DdzScreen.read(frame("lead")).buttons.size)
        assertEquals(DdzColor.Gray, DdzScreen.read(frame("lead")).buttons[0].color)
        assertEquals(DdzColor.Yellow, DdzScreen.read(frame("lead_lifted")).buttons[0].color)
    }

    @Test fun callButtonsKeepTheirOrder() {
        val call = DdzScreen.read(frame("call"))
        assertEquals(4, call.buttons.size)
        assertEquals(DdzColor.Blue, call.buttons[0].color)
    }

    @Test fun doubleNeverLooksLikeFollow() {
        val double = DdzScreen.read(frame("double"))
        assertEquals(DdzColor.Green, double.buttons[2].color)
    }

    @Test fun cardCentresStayInsideTheVisibleStrip() {
        val view = DdzScreen.read(frame("lead"))
        val first = view.cardX(0)
        val last = view.cardX(14)
        assertEquals(true, first in 491..640)
        assertEquals(true, last in 1800..2049)
    }

    @Test fun aLiftedCardInAThreeCardStageHand() {
        // 大王、K、K:只抬了大王;原来认成两张 K 抬着,选牌点来点去最后放弃
        val joker = DdzScreen.read(frame("vd_stage_3cards_joker_lifted"))
        assertEquals(3, joker.handCount)
        assertEquals(setOf(0), joker.lifted)
        assertEquals(DdzScreen.HAND_ROW - 32, joker.handRow)
        assertEquals(setOf(1, 2), DdzScreen.read(frame("vd_stage_3cards_K_lifted")).lifted)
    }

    @Test fun longStraightOnTheTableIsNotTakenForAGrayButton() {
        // 自己刚出的 3-4-5-6-7-8-9-10 摆在牌桌正中,横跨 470 这一行;牌面的白和牌间的灰影拼出一段"灰按钮",
        // 被当成轮到我出牌。地主在想时我这边一个按钮都没有:既不是 Lead/Follow,也不该有按钮。
        for (name in listOf(
            "longplay_straight8_115140",
            "longplay_straight8_115142",
            "longplay_straight8_115144",
            "longplay_straight8_115148",
        )) {
            val view = DdzScreen.read(frame(name))
            assertEquals(name, DdzPhase.Other, view.phase)
            assertEquals(name, emptyList<DdzButton>(), view.buttons)
        }
    }
}
