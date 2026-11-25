package com.wangzan101.mika.core.cap.phone

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI

/** 本机或局域网里的 DouZero 出牌服务(tools/douzero)。一手要在倒计时里出完,连不上就快点放弃。 */
internal object DdzAdvisor {
    private const val CONNECT_TIMEOUT_MS = 2_000
    private const val PROBE_READ_TIMEOUT_MS = 2_000

    @Volatile
    var lastReachableUrl: String? = null

    /** /act 拒绝空请求也算可达。只等响应状态，不读正文。 */
    suspend fun probe(url: String): Boolean =
        once(url, "{}", "act", CONNECT_TIMEOUT_MS, PROBE_READ_TIMEOUT_MS, probeOnly = true) != null

    /** 出牌:服务在顶两手分数接近时会现场模拟 2.5 秒(tools/douzero ACT_ROLLOUT_BUDGET_S),读完给 4 秒。 */
    private const val ACT_READ_TIMEOUT_MS = 4_000

    /** 叫分、加倍:服务最多模拟 3 秒(tools/douzero ROLLOUT_BUDGET_S),加倍只有约 5 秒,等 4 秒、不重试。 */
    const val ROLLOUT_TIMEOUT_MS = 4_000

    /**
     * 出牌连不上再试一次:手机解析 .local 主机名、Wi-Fi 省电偶尔会让头一次超时。
     * 连 2 秒、读 4 秒、重试一次,最坏 2×(2+4)=12 秒,一手有 15 秒。
     */
    suspend fun post(url: String, body: String, path: String = "act"): String? =
        once(url, body, path, CONNECT_TIMEOUT_MS, ACT_READ_TIMEOUT_MS)
            ?: once(url, body, path, CONNECT_TIMEOUT_MS, ACT_READ_TIMEOUT_MS)

    /** 叫分、加倍只问一次,等 [ROLLOUT_TIMEOUT_MS]:连不上就按老规则走。 */
    suspend fun postRollout(url: String, body: String, path: String): String? =
        once(url, body, path, ROLLOUT_TIMEOUT_MS, ROLLOUT_TIMEOUT_MS)

    private suspend fun once(
        url: String,
        body: String,
        path: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        probeOnly: Boolean = false,
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URI("${url.trimEnd('/')}/$path").toURL().openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = connectTimeoutMs
                conn.readTimeout = readTimeoutMs
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                if (probeOnly) conn.instanceFollowRedirects = false
                conn.outputStream.use { it.write(body.toByteArray()) }
                val response = if (probeOnly) {
                    check(conn.responseCode != -1) { "no HTTP response" }
                    ""
                } else {
                    conn.inputStream.bufferedReader().use { it.readText() }
                }
                lastReachableUrl = url
                response
            } finally {
                conn.disconnect()
            }
        }.onFailure { e ->
            if (e is SocketTimeoutException) coroutineContext[JjTimeoutCounter]?.count?.invoke()
            println("DDZTRACE advisor_error url=$url path=$path err=${e::class.simpleName}: ${e.message}")
        }.getOrNull()
    }
}
