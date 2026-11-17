package com.wangzan101.mika.core.cap.phone

import com.sun.net.httpserver.HttpServer
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.ToolExecution
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

class PhoneMahjongHintTest {
    /** 类第一次加载时(还没被任何测试换过)的默认实现:路径那条测试要走真的 HTTP。 */
    private companion object {
        val REAL_POST = PhoneMahjongHint.post
    }

    private val screen = FakeScreen()

    @org.junit.After fun restoreService() {
        PhoneMahjongHint.post = REAL_POST
    }

    private suspend fun run(json: String): ToolExecution =
        PhoneMahjongHint.execute(Args(PhoneMahjongHint.def, Json.parseToJsonElement(json).jsonObject), toolDispatcher(screen).ctx)

    private fun ToolExecution.text(key: String) = content[key]?.jsonPrimitive?.content

    private fun ToolExecution.list(key: String) = (content[key] as JsonArray).map { it.jsonPrimitive.content }

    @Test fun returnsTheRecommendedDiscard() = runTest {
        PhoneMahjongHint.post = { _, _ ->
            """{"win":false,"discard":"9p","shanten":0,"ukeire":4,"waits":["5p"],"queLeft":0,""" +
                """"options":[{"discard":"9p","shanten":0,"ukeire":4,"waits":["5p"]}]}"""
        }
        val r = run(
            """{"hand":["1m","2m","3m","4m","5m","6m","7m","8m","9m","1p","2p","3p","5p","9p"],""" +
                """"que":"m","advisor":"http://mac:3427"}""",
        )
        assertEquals("9p", r.text("discard"))
        assertEquals("0", r.text("shanten"))
        assertEquals("4", r.text("ukeire"))
        assertEquals(listOf("5p"), r.list("waits"))
        assertEquals(1, (r.content["options"] as JsonArray).size)
    }

    @Test fun passesTheServicesErrorCodeBack() = runTest {
        PhoneMahjongHint.post = { _, _ -> """{"error":"bad_tile","detail":"不认识的牌：'0m'"}""" }
        val r = run("""{"hand":["0m"],"que":"m","advisor":"http://mac:3427"}""")
        assertEquals("bad_tile", r.text("error"))
        assertEquals("不认识的牌：'0m'", r.text("detail"))
        val message = r.text("message")!!
        assertTrue(message, message.contains("screenshot"))
        assertTrue(message, message.contains("again"))
    }

    @Test fun reportsTheServiceAsUnavailable() = runTest {
        PhoneMahjongHint.post = { _, _ -> null }
        val r = run("""{"hand":["1m"],"que":"m","advisor":"http://mac:3427"}""")
        assertEquals("false", r.text("ok"))
        assertEquals("service_unavailable", r.text("error"))
        val message = r.text("message")!!
        assertTrue(message, message.contains("Mac"))
        assertTrue(message, message.contains("not running"))
    }

    @Test fun neverTouchesTheScreen() = runTest {
        PhoneMahjongHint.post = { _, _ -> """{"win":true}""" }
        val r = run("""{"hand":["1m","2m"],"melds":[{"type":"pon","tile":"5p"},{"type":"pon","tile":"6p"},{"type":"pon","tile":"7p"},{"type":"kan","tile":"9s"}],"que":"s","advisor":"http://mac:3427"}""")
        assertEquals("true", r.text("win"))
        assertTrue("no taps or gestures: ${screen.calls}", screen.calls.isEmpty())
        assertEquals("no screen reads", 0, screen.snapshots)
    }

    @Test fun sendsTheHandMeldsAndQueToTheDiscardPath() = runTest {
        val seen = CopyOnWriteArrayList<Pair<String, String>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            seen += exchange.requestURI.path to exchange.requestBody.readBytes().decodeToString()
            val reply = """{"win":false,"discard":"9p","shanten":0,"ukeire":4,"waits":["5p"],"queLeft":0,"options":[]}"""
                .toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        server.start()
        try {
            // 真的走 DdzAdvisor.post:路径和请求体只有这条能验
            PhoneMahjongHint.post = REAL_POST
            val r = run(
                """{"hand":["1m","2m","3m","4m","5m","6m","7m","8m","9m","1p","2p"],""" +
                    """"melds":[{"type":"pon","tile":"5p"}],"que":"m","advisor":"http://127.0.0.1:${server.address.port}"}""",
            )
            assertEquals("9p", r.text("discard"))
        } finally {
            server.stop(0)
        }
        assertEquals(1, seen.size)
        assertEquals("/discard", seen[0].first)
        val sent = Json.parseToJsonElement(seen[0].second).jsonObject
        assertEquals(
            listOf("1m", "2m", "3m", "4m", "5m", "6m", "7m", "8m", "9m", "1p", "2p"),
            (sent["hand"] as JsonArray).map { it.jsonPrimitive.content },
        )
        val melds = sent["melds"] as JsonArray
        assertEquals(1, melds.size)
        assertEquals("pon", melds[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("5p", melds[0].jsonObject["tile"]!!.jsonPrimitive.content)
        assertEquals("m", sent["que"]!!.jsonPrimitive.content)
    }
}
