package com.wangzan101.mika.core.screen.user

/**
 * 用户屏幕上的悬浮胶囊:闪一句短文字(不经过模型,也不落进对话)。
 */
interface HintHud {
    /** 胶囊此刻真的显示在亮着的屏幕上。 */
    val showing: Boolean

    /** 闪一句,最多 [ms] 毫秒;胶囊不在(关着、服务断了、Mika 在前台、锁屏)时返回 false。 */
    suspend fun flash(text: String, ms: Long = 2_500L): Boolean
}
