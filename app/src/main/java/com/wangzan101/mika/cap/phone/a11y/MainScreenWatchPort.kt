package com.wangzan101.mika.cap.phone.a11y

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityWindowInfo
import com.wangzan101.mika.capsule.CapsuleController
import com.wangzan101.mika.core.access.AppAccessPolicy
import com.wangzan101.mika.core.userscreen.HintHud
import com.wangzan101.mika.core.userscreen.MainScreenPort
import com.wangzan101.mika.vision.BitmapImageOps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * core 两个看屏端口(主屏 + 胶囊提示)的实现:
 * 用无障碍服务截**用户的主屏**(不是 Mika 的虚拟屏),只在 JJ麻将占着最上面一个 App 窗口时给图;
 * 看屏期间把悬浮胶囊停到不挡牌桌的位置,截图前胶囊真压着要认的地方才临时藏一下,结束后还原。
 * 同一时间只允许一个看屏(第二个任务拿到 busy)。
 */
class MainScreenWatchPort(
    private val context: Context,
    private val appAccess: AppAccessPolicy,
    private val capsule: () -> CapsuleController?,
) : MainScreenPort, HintHud {
    /** 上一次截图开始(或拿到图)的时刻;两张之间至少隔 [SHOT_INTERVAL_MS]。 */
    @Volatile private var lastShotAt = 0L

    override suspend fun beginWatch(): Boolean {
        val now = SystemClock.uptimeMillis()
        synchronized(WATCH_LOCK) {
            val held = watchOwner
            // 同一时间只允许一个看屏;占着的那次异常没了(超过一次看屏的上限)才算作废。
            if (held != null && held !== this && now - watchSince < STALE_WATCH_MS) return false
            watchOwner = this
            watchSince = now
        }
        return try {
            // 胶囊此刻在不在屏幕上,只用来留一条日志:看屏照样继续,提示等胶囊显示出来再闪(任务侧按 flash 的返回值重试)。
            val docked = withContext(Dispatchers.Main.immediate) { capsule()?.beginWatchDock() ?: false }
            if (!docked) Log.w(TAG, "watch started while the capsule is not shown; hints retry until it is")
            true
        } catch (e: Throwable) {
            // 还没真正开起来就被取消:别把「正在看屏」占着。
            releaseWatch()
            throw e
        }
    }

    override suspend fun endWatch() {
        // 已经被当成作废、由另一个看屏接手时,别去动人家的胶囊位置。
        val mine = synchronized(WATCH_LOCK) { watchOwner === this }
        if (mine) withContext(Dispatchers.Main.immediate) { capsule()?.endWatchDock() }
        releaseWatch()
    }

    private fun releaseWatch() = synchronized(WATCH_LOCK) { if (watchOwner === this) watchOwner = null }

    override val showing: Boolean get() = capsule()?.showing?.value == true

    override suspend fun flash(text: String, ms: Long): Boolean =
        withContext(Dispatchers.Main.immediate) { capsule()?.flashHint(text, ms) ?: false }

    /** 用户主屏最上面那个 App;锁屏、有系统窗口盖着、读不出、或不止一个 App 窗口时为 null。 */
    override suspend fun foreground(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val service = ScreenAccessibility.instance.value ?: return@runCatching null
            if (locked()) return@runCatching null
            frontApp(service)
        }.getOrNull()
    }

    override suspend fun capture(packageName: String): MainScreenPort.Capture = try {
        captureOnce(packageName)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        MainScreenPort.Capture.Skip
    }

    private suspend fun captureOnce(packageName: String): MainScreenPort.Capture {
        val service = ScreenAccessibility.instance.value ?: return MainScreenPort.Capture.Unavailable
        if (locked()) return MainScreenPort.Capture.Skip
        if (frontApp(service) != packageName) return MainScreenPort.Capture.Skip
        if (!appAccess.current().canShare(packageName)) return MainScreenPort.Capture.Skip
        val cap = capsule()
        if (cap != null && withContext(Dispatchers.Main.immediate) { cap.cardOpen }) return MainScreenPort.Capture.Skip
        // 系统限制两次截图的间隔:在藏起浮层之前等,别让胶囊陪着一块等。
        val wait = SHOT_INTERVAL_MS - (SystemClock.uptimeMillis() - lastShotAt)
        if (wait > 0) delay(wait)
        val bmp = shoot(service, packageName, cap) ?: return MainScreenPort.Capture.Skip
        lastShotAt = SystemClock.uptimeMillis()
        val jpeg = withContext(Dispatchers.Default) {
            try {
                if (bmp.width <= bmp.height) return@withContext null // 竖屏画面:Mac 不认,当这一轮跳过
                val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
                bytes.takeUnless { BitmapImageOps.isMostlyBlack(it) }
            } finally {
                bmp.recycle()
            }
        }
        return jpeg?.let(MainScreenPort.Capture::Ok) ?: MainScreenPort.Capture.Skip
    }

    /** 截一张;胶囊压着要认的地方时先藏起来。返回的 Bitmap 由调用方回收。 */
    private suspend fun shoot(service: ScreenAccessibility, packageName: String, cap: CapsuleController?): Bitmap? {
        if (frontApp(service) != packageName) return null
        val metrics = service.resources.displayMetrics
        val regions = WatchGeometry.recognizeRegions(metrics.widthPixels, metrics.heightPixels)
        val shot = if (cap == null) {
            service.takeMainScreenshot()
        } else {
            withContext(Dispatchers.Main.immediate) {
                cap.hiddenForCapture(regions) { service.takeMainScreenshot() }
            }
        }
        return (shot as? ScreenAccessibility.MainShot.Ok)?.bitmap
    }

    /**
     * 主屏最上面那个 App 的包名。要求:最上面的 App 窗口拿着焦点(Mika 的浮层不抢焦点,不算)、
     * 屏上只有一个 App 窗口(分屏、浮窗就不发)、没有盖住大半屏的系统窗口(通知栏、系统弹窗)。
     */
    private fun frontApp(service: ScreenAccessibility): String? {
        val windows = service.windowsOn(Display.DEFAULT_DISPLAY)
        val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val top = apps.maxByOrNull { it.layer } ?: return null
        if (!top.isFocused) return null
        val pkg = top.root?.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return null
        if (apps.any { it !== top && it.root?.packageName?.toString() != pkg }) return null
        val bounds = Rect().also(top::getBoundsInScreen)
        // 状态栏、导航条(<15% 高)不算;拉下来的通知栏、系统弹窗、音量面板这类要跳过。
        val covered = windows.any { w ->
            if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM || w.layer <= top.layer) return@any false
            val r = Rect().also(w::getBoundsInScreen)
            r.width() >= bounds.width() / 2 && r.height() >= bounds.height() * 15 / 100
        }
        return pkg.takeUnless { covered }
    }

    /** 锁屏或熄屏时不截:截出来是锁屏画面,也没法确认前台。 */
    private fun locked(): Boolean {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (keyguard?.isDeviceLocked == true) return true
        val power = context.getSystemService(PowerManager::class.java)
        return power?.isInteractive != true
    }

    private companion object {
        const val TAG = "MikaWatch"
        /** 整个进程共用一个看屏名额:第二个任务的端口实例看到它占着就报 busy。 */
        val WATCH_LOCK = Any()
        var watchOwner: MainScreenWatchPort? = null
        var watchSince = 0L
        /** 一次看屏最长 60 分钟;占着的没了(收尾没走到)超过这个时间才算作废。 */
        const val STALE_WATCH_MS = 61L * 60 * 1000
        /** 两张截图之间至少这么久(系统限制两次截图的最小间隔)。 */
        const val SHOT_INTERVAL_MS = 1_000L
        /** 认牌只需要看清牌面;85 比 PNG 小一个数量级。 */
        const val JPEG_QUALITY = 85
    }
}
