package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.uistate.RawFrame
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 牌桌/匹配画面底部「经典××场」的读法:认得至尊场、土豪场、普通场,认不出时绝不猜。 */
class DdzRoomLabelTest {
    private fun image(name: String) = ImageIO.read(javaClass.getResourceAsStream("/ddz/$name.png")!!)

    private fun labelFrame(name: String): RawFrame {
        val image = image(name)
        return RawFrame(image.width, image.height, DdzRoomLabel.ROWS.associateWith { y ->
            image.getRGB(0, y, image.width, 1, null, 0, image.width)
        })
    }

    @Test fun readsTheRoomFromTheTableAndMatchingFrames() {
        assertEquals("至尊场", DdzRoomLabel.read(labelFrame("master_zhizun_table")))
        assertEquals("至尊场", DdzRoomLabel.read(labelFrame("master_zhizun_matching")))
        assertEquals("土豪场", DdzRoomLabel.read(labelFrame("tuhao_table")))
        assertEquals("普通场", DdzRoomLabel.read(labelFrame("human_right_landlord_10cards")))
    }

    @Test fun anUnknownOrCoveredLabelReadsAsNull() {
        // 场次页没有这个标签
        assertNull(DdzRoomLabel.read(labelFrame("human_classic_rooms")))
        // 标签被弹窗/遮罩挡住:整块涂暗,认不出但不能当成场次不符
        val image = image("master_zhizun_table")
        val blocked = RawFrame(image.width, image.height, DdzRoomLabel.ROWS.associateWith { y ->
            IntArray(image.width) { x -> if (x in 1050..1370) 0xFF202020.toInt() else image.getRGB(x, y) }
        })
        assertNull(DdzRoomLabel.read(blocked))
    }

    @Test fun aFrameThatDidNotReadTheLabelRowsIsNotAnError() {
        // 老测试帧只读了牌桌的行:读不到标签,返回 null,不能抛异常
        val image = image("master_zhizun_table")
        val tableRows = (DdzScreen.ROWS + DdzCards.ROWS).distinct()
        val frame = RawFrame(image.width, image.height, tableRows.associateWith { y ->
            image.getRGB(0, y, image.width, 1, null, 0, image.width)
        })
        assertNull(DdzRoomLabel.read(frame))
    }
}
