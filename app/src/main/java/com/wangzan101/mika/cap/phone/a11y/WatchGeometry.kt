package com.wangzan101.mika.cap.phone.a11y

/** 屏幕坐标里的一块矩形(像素)。纯数据,好算、好测;要交给安卓时再换成 Rect。 */
internal data class WatchRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun overlaps(other: WatchRect): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom
}

/**
 * 看屏时要认的区域:底部手牌、左上角的牌墙数字、左下自己的头像/定缺角标(横屏 2412×1080)。
 * 胶囊停在这些区域上时会挡住认牌,截图前要先把它藏起来。
 */
internal object WatchGeometry {
    fun recognizeRegions(width: Int, height: Int): List<WatchRect> = listOf(
        WatchRect(0, height * 3 / 4, width, height),
        WatchRect(0, 0, width / 8, height / 5),
        WatchRect(0, height * 3 / 5, width / 5, height),
    )

    /** 胶囊 [bounds] 压到 [regions] 里的任何一块就要先藏起来;[bounds] 为 null(没显示)时不用。 */
    fun needsHide(bounds: WatchRect?, regions: List<WatchRect>): Boolean =
        bounds != null && regions.any(bounds::overlaps)
}
