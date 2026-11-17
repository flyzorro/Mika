package com.wangzan101.mika.core.userscreen

/**
 * 用户主屏(不是 Mika 的虚拟屏)此刻的样子:前台是哪个 App、能不能截一张画面。
 */
interface MainScreenPort {
    /**
     * 开始看屏:同一时间只允许一个看屏,已被占着时返回 false(调用方报 busy)。
     * 成功时由实现把悬浮胶囊挪到不挡牌桌的位置,结束由 [endWatch] 还原。
     */
    suspend fun beginWatch(): Boolean

    /** 结束看屏:恢复用户原来的胶囊位置,放开占用。可以重复调用。 */
    suspend fun endWatch()

    /** 用户主屏最上面的 App 包名;锁屏、通知栏或系统弹窗盖在上面、读不出时为 null。 */
    suspend fun foreground(): String?

    /** 截一张用户主屏。只有 [foreground] 也是 [packageName] 时才给图。 */
    suspend fun capture(packageName: String): Capture

    sealed interface Capture {
        /** 原分辨率的一帧 JPEG。 */
        class Ok(val jpeg: ByteArray) : Capture

        /** 这一轮不发:锁屏、防截屏、归属变了、画面不可用。 */
        data object Skip : Capture

        /** 无障碍服务不在了(截不到图),看屏该停。 */
        data object Unavailable : Capture
    }
}
