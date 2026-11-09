package com.wangzan101.mika.core.cap.phone

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

/** 本机或局域网里的 DouZero 出牌服务(tools/douzero)。一手要在倒计时里出完,连不上就快点放弃。 */
internal object DdzAdvisor {
    private const val TIMEOUT_MS = 2_000

    /** 连不上再试一次:手机解析 .local 主机名、Wi-Fi 省电偶尔会让头一次超时,一手有 15 秒。 */
    suspend fun post(url: String, body: String, path: String = "act"): String? = once(url, body, path) ?: once(url, body, path)

    private suspend fun once(url: String, body: String, path: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URI("${url.trimEnd('/')}/$path").toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
            conn.inputStream.bufferedReader().use { it.readText() }.also { conn.disconnect() }
        }.getOrNull()
    }
}
