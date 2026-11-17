package com.wangzan101.mika.core.cap.phone

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetSocketAddress

class HintAdvisorTest {
    @Test fun postsTheFrameToHint() = runTest {
        val seen = mutableListOf<Pair<String, String>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/hint") { exchange ->
            seen += exchange.requestURI.path to exchange.requestBody.readBytes().decodeToString()
            val reply = """{"state":"wait"}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}"
            assertEquals("""{"state":"wait"}""", HintAdvisor.post(url, """{"image":"AQID"}"""))
            assertEquals("/hint", seen.single().first)
            assertEquals("""{"image":"AQID"}""", seen.single().second)
        } finally {
            server.stop(0)
        }
    }

    @Test fun failingToConnectIsNull() = runTest {
        assertNull(HintAdvisor.post("http://127.0.0.1:1", "{}"))
    }

    @Test fun nonTwoHundredIsNull() = runTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/hint") { exchange ->
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }
        server.start()
        try {
            assertNull(HintAdvisor.post("http://127.0.0.1:${server.address.port}", "{}"))
        } finally {
            server.stop(0)
        }
    }
}
