package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.uistate.RawFrame
import java.io.DataInputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** TEMP diagnostics; remove after. 写出去的帧能原样读回来。 */
class DdzFrameDumpTest {
    private val rows = (DdzScreen.ROWS + DdzCards.ROWS).distinct()

    private fun frame(name: String): RawFrame {
        val image = ImageIO.read(javaClass.getResourceAsStream("/ddz/$name.png")!!)
        return RawFrame(image.width, image.height, rows.associateWith { image.getRGB(0, it, image.width, 1, null, 0, image.width) })
    }

    @Test fun dumpsTheRingAndTheCurrentFrameAndReadsBackTheSamePixels() {
        val dir = Files.createTempDirectory("ddzdump").toFile()
        val saved = DdzFrameDump.dirs
        try {
            DdzFrameDump.dirs = listOf(dir.path)
            DdzFrameDump.reset()
            val a = frame("human_right_landlord_joker")
            val b = frame("human_right_landlord_10cards")
            DdzFrameDump.seen(a)
            DdzFrameDump.dump("hand_null", b, rows)
            DdzFrameDump.dump("hand_null", b, rows) // 3 秒内同一个 tag 不再写
            DdzFrameDump.writer.submit {}.get()
            val files = dir.listFiles()!!.filter { it.name.endsWith(".ddzf.gz") }.sortedBy { it.name }
            assertEquals(files.toString(), 2, files.size)
            assertTrue(files.all { "_hand_null_" in it.name })
            DataInputStream(GZIPInputStream(files.last().inputStream())).use { input ->
                val magic = ByteArray(5).also { input.readFully(it) }
                assertEquals("DDZF1", String(magic))
                assertEquals(b.width, input.readInt())
                assertEquals(b.height, input.readInt())
                val n = input.readInt()
                assertEquals(rows.size, n)
                repeat(n) {
                    val y = input.readInt()
                    for (x in 0 until b.width) assertEquals("pixel $x,$y", b.pixel(x, y), input.readInt())
                }
            }
        } finally {
            DdzFrameDump.dirs = saved
            DdzFrameDump.reset()
            dir.deleteRecursively()
        }
    }
}
