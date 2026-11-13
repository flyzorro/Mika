package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.uistate.RawFrame
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream

/**
 * 真人局诊断:把识别程序当时看到的那几帧写到 App 的外部文件目录,adb 能直接拉:
 * /sdcard/Android/data/<包名>/files/ddzdump/。内存里只留最近 [RING] 帧当上下文。
 *
 * 目前用来留「点场次之后」的几帧(标签 `room_tap`):2026-11-13 room=master 却进了至尊场,
 * 截图间隔约 2 秒,看不到点下去那一刻;点完紧接着的帧能确认工具到底点在哪张卡、画面怎么变的。
 *
 * 文件是 gzip 的二进制:"DDZF1"、宽、高、行数,然后每行「行号 + 宽个 ARGB int」;没读的行不存。
 * 电脑上用 scratchpad 里的 ddzf2png.py 转成 PNG(没读的行涂黑)。
 */
internal object DdzFrameDump {
    private const val RING = 6
    private const val MAX_FILES = 400
    private const val MIN_GAP_MS = 3_000L
    /** 测试里换成临时目录。 */
    internal var dirs = listOf(
        "/sdcard/Android/data/com.wangzan101.mika.dev/files/ddzdump",
        "/sdcard/Android/data/com.wangzan101.mika/files/ddzdump",
    )

    private class Seen(val at: Long, val frame: RawFrame)

    private val ring = ArrayDeque<Seen>()
    private val lastByTag = HashMap<String, Long>()
    private var written = 0

    /** 测试用:清掉限速和计数。 */
    @Synchronized
    internal fun reset() { ring.clear(); lastByTag.clear(); written = 0 }
    internal val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "ddz-frame-dump").apply { isDaemon = true } }
    private val stamp = SimpleDateFormat("HHmmss.SSS", Locale.US)

    /** 每一帧都经过这里:留最近几帧。 */
    @Synchronized
    fun seen(frame: RawFrame) {
        if (ring.lastOrNull()?.frame === frame) return
        ring.addLast(Seen(System.currentTimeMillis(), frame))
        while (ring.size > RING) ring.removeFirst()
    }

    /** 出事了:把最近几帧连同 [frame] 一起写盘。同一个 [tag] 至少隔 [MIN_GAP_MS] 才再写一次。 */
    @Synchronized
    fun dump(tag: String, frame: RawFrame?, rows: List<Int>) {
        val now = System.currentTimeMillis()
        val last = lastByTag[tag]
        if (last != null && now - last < MIN_GAP_MS) return
        if (written >= MAX_FILES) return
        lastByTag[tag] = now
        frame?.let { seen(it) }
        val frames = ring.toList()
        val base = "${stamp.format(Date(now))}_${tag.replace(Regex("[^A-Za-z0-9_.-]"), "_")}"
        written += frames.size
        println("DDZDUMP $base frames=${frames.size}")
        // 文件名在这里算好:SimpleDateFormat 不能跨线程用
        val named = frames.mapIndexed { i, seen -> "${base}_${i}_${stamp.format(Date(seen.at))}.ddzf.gz" to seen.frame }
        val targets = dirs
        writer.execute {
            val dir = targets.map(::File).firstOrNull { it.isDirectory || it.mkdirs() }
            if (dir == null) {
                println("DDZDUMP failed: no writable dir")
                return@execute
            }
            for ((name, f) in named) {
                runCatching { write(File(dir, name), f, rows) }
                    .onFailure { println("DDZDUMP failed $name: ${it.message}") }
            }
        }
    }

    private fun write(file: File, frame: RawFrame, rows: List<Int>) {
        val present = rows.distinct().sorted().filter { y -> runCatching { frame.pixel(0, y) }.isSuccess }
        // 整行拼好一次写、压缩用最快的一档:逐个 writeInt 在手机上一帧要好几秒,任务一结束 App 进后台被冻结就写不完
        val line = ByteBuffer.allocate(4 * frame.width)
        val gzip = object : GZIPOutputStream(file.outputStream(), 1 shl 16) { init { def.setLevel(Deflater.BEST_SPEED) } }
        DataOutputStream(gzip).use { out ->
            out.writeBytes("DDZF1")
            out.writeInt(frame.width)
            out.writeInt(frame.height)
            out.writeInt(present.size)
            for (y in present) {
                out.writeInt(y)
                line.clear()
                for (x in 0 until frame.width) line.putInt(frame.pixel(x, y))
                out.write(line.array(), 0, line.position())
            }
        }
    }
}
