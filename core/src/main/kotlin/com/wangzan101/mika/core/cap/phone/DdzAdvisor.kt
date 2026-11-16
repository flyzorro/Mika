package com.wangzan101.mika.core.cap.phone

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

/** 本机或局域网里的 DouZero 出牌服务(tools/douzero)。一手要在倒计时里出完,连不上就快点放弃。 */
internal object DdzAdvisor {
    private const val TIMEOUT_MS = 2_000

    /** 叫分、加倍:服务最多模拟 3 秒(tools/douzero ROLLOUT_BUDGET_S),加倍只有约 5 秒,等 4 秒、不重试。 */
    const val ROLLOUT_TIMEOUT_MS = 4_000

    /** 连不上再试一次:手机解析 .local 主机名、Wi-Fi 省电偶尔会让头一次超时,一手有 15 秒。 */
    suspend fun post(url: String, body: String, path: String = "act"): String? = once(url, body, path) ?: once(url, body, path)

    /** 叫分、加倍只问一次,等 [ROLLOUT_TIMEOUT_MS]:连不上就按老规则走。 */
    suspend fun postRollout(url: String, body: String, path: String): String? = once(url, body, path, ROLLOUT_TIMEOUT_MS)

    private suspend fun once(url: String, body: String, path: String, timeoutMs: Int = TIMEOUT_MS): String? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URI("${url.trimEnd('/')}/$path").toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
            conn.inputStream.bufferedReader().use { it.readText() }.also { conn.disconnect() }
        }.getOrNull()
    }
}
