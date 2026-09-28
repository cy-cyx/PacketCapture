package com.packetcapture.data

import android.content.Context
import com.packetcapture.core.*
import java.io.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** 单个写入队列拥有所有正文文件；转发线程只复制有限大小的块，不等待磁盘。 */
class FileBodyStore(context: Context) : BodyStore {
    private val root = File(context.noBackupFilesDir, "bodies").apply { mkdirs() }
    private val databaseDir = context.getDatabasePath("capture.db").parentFile!!
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<suspend () -> Unit>(64)
    private val reserved = AtomicLong(root.walkTopDown().filter { it.isFile }.sumOf { it.length() })
    private val dbBytes = AtomicLong(0)
    private val freeBytes = AtomicLong(Long.MAX_VALUE)
    init {
        scope.launch { for (task in queue) runCatching { task() } }
        scope.launch {
            while (isActive) {
                dbBytes.set(databaseBytes()); freeBytes.set(root.usableSpace)
                delay(1000)
            }
        }
    }
    private fun safeFile(key: String): File {
        val file = File(root, key).canonicalFile
        require(file.path.startsWith(root.canonicalPath + File.separator)) { "非法正文路径" }
        return file
    }
    override fun open(sessionId: String, exchangeId: String, part: BodyPart, limit: Long, totalLimit: Long): BodySink {
        require(sessionId.matches(Regex("[a-zA-Z0-9-]+")) && exchangeId.matches(Regex("[a-zA-Z0-9-]+")))
        return Sink("$sessionId/$exchangeId-${part.name.lowercase()}.body", limit, totalLimit)
    }
    private inner class Sink(val key: String, val limit: Long, val totalLimit: Long) : BodySink {
        var observed = 0L
        var accepted = 0L
        var saved = 0L
        @Volatile var reason: String? = null
        var writeFailed = false
        var output: OutputStream? = null
        var closed = false
        val result = CompletableDeferred<BodyRef>()
        @Synchronized override fun append(bytes: ByteArray) {
            if (closed) return
            observed += bytes.size
            if (reason != null) return
            val count = minOf(bytes.size.toLong(), (limit - accepted).coerceAtLeast(0)).toInt()
            if (count > 0) {
                // 预留配额包含排队尚未落盘的数据；并发响应不能各自看到同一份剩余额度。
                val next = reserved.addAndGet(count.toLong())
                if (next + dbBytes.get() > totalLimit || freeBytes.get() < 16L * 1024 * 1024) {
                    reserved.addAndGet(-count.toLong()); reason = "存储额度或可用空间不足"; return
                }
                val owned = if (count == bytes.size) bytes else bytes.copyOf(count)
                if (!queue.trySend {
                    // 文件只由队列消费者持有；不能持有 append 的锁执行 IO，否则会反向阻塞网络。
                    if (!writeFailed) {
                        try {
                            if (output == null) {
                                val file = safeFile("$key.part")
                                file.parentFile!!.mkdirs()
                                output = BufferedOutputStream(FileOutputStream(file))
                            }
                            output!!.write(owned)
                            saved += owned.size
                        } catch (e: IOException) { writeFailed = true; reason = "正文写入失败: ${e.message}" }
                    }
                }.isSuccess) {
                    reserved.addAndGet(-count.toLong())
                    // 首次丢块即停止接收本正文，不能把缺口后的字节拼到前缀上。
                    reason = "记录队列已满"; return
                }
                accepted += count
            }
            if (count < bytes.size) reason = "达到正文保存上限"
        }
        override suspend fun finish(): BodyRef {
            val shouldClose = synchronized(this) { if (closed) false else { closed = true; true } }
            if (shouldClose) queue.send {
                run {
                    try {
                        output?.close()
                        val partial = safeFile("$key.part")
                        if (partial.exists() && !partial.renameTo(safeFile(key))) reason = "正文文件提交失败"
                    } catch (e: IOException) { reason = "正文关闭失败: ${e.message}" }
                    // BufferedOutputStream 的 write 成功不等于 close/flush 已落盘；引用只指向提交后的真实文件。
                    val committed = safeFile(key)
                    val partial = safeFile("$key.part")
                    val committedBytes = if (committed.exists()) minOf(saved, committed.length()) else 0L
                    val diskBytes = committedBytes + if (partial.exists()) partial.length() else 0L
                    reserved.addAndGet(-(accepted - diskBytes))
                    result.complete(BodyRef(key, observed, committedBytes, reason != null || observed != committedBytes, reason))
                }
            }
            return result.await()
        }
    }
    private fun databaseBytes(): Long = databaseDir.listFiles()?.filter { it.name.startsWith("capture.db") }?.sumOf { it.length() } ?: 0
    override suspend fun read(ref: BodyRef, maxBytes: Int): ByteArray = withContext(Dispatchers.IO) {
        val file = safeFile(ref.key)
        if (!file.exists() && ref.savedBytes > 0) throw IOException("正文文件丢失，不能导出完整请求")
        if (!file.exists()) byteArrayOf() else file.inputStream().use { readLimited(it, maxBytes).first }
    }
    override suspend fun preview(ref: BodyRef?, headers: List<Header>): BodyPreview = withContext(Dispatchers.IO) {
        if (ref == null) return@withContext BodyPreview("正文尚未完成或为空", false, false)
        if (ref.savedBytes == 0L) return@withContext BodyPreview("", false, ref.truncated, ref.reason ?: if (ref.truncated) "采集时正文未完整保存" else null)
        val file = safeFile(ref.key)
        if (!file.exists()) return@withContext BodyPreview("正文文件不可用", false, true, ref.reason)
        file.inputStream().use { BodyDecoder.decode(it, headers, ref.truncated || file.length() < ref.savedBytes,
            ref.reason ?: if (file.length() < ref.savedBytes) "已保存的正文文件不完整" else null) }
    }
    override suspend fun deleteSession(sessionId: String) = withContext(Dispatchers.IO) {
        val folder = safeFile(sessionId)
        val bytes = folder.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        check(!folder.exists() || folder.deleteRecursively()) { "正文清理失败" }
        reserved.addAndGet(-bytes)
        Unit
    }
    override suspend fun usedBytes(): Long = withContext(Dispatchers.IO) { reserved.get().coerceAtLeast(0) + databaseBytes() }
    override suspend fun clearAll() = withContext(Dispatchers.IO) {
        try {
            val files = root.listFiles() ?: throw IOException("无法读取正文存储目录")
            files.forEach { check(it.deleteRecursively()) { "正文清理失败，请重试" } }
        } finally {
            // 部分文件删除失败时也按实际占用更新，避免显示已释放的额度。
            reserved.set(root.walkTopDown().filter { it.isFile }.sumOf { it.length() })
            dbBytes.set(databaseBytes())
            freeBytes.set(root.usableSpace)
        }
    }
    override suspend fun recoverOrphans(liveSessionIds: Set<String>) = withContext(Dispatchers.IO) {
        root.listFiles()?.filter { it.isDirectory && it.name !in liveSessionIds }?.forEach { it.deleteRecursively() }
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".part") }.forEach { file ->
            if (file.delete()) reserved.addAndGet(-file.length())
        }
        reserved.set(root.walkTopDown().filter { it.isFile }.sumOf { it.length() })
    }
    companion object {
        internal fun readLimited(input: InputStream, limit: Int): Pair<ByteArray, Boolean> {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var remaining = limit
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (count < 0) return output.toByteArray() to false
                output.write(buffer, 0, count); remaining -= count
            }
            return output.toByteArray() to (input.read() != -1)
        }
    }
}
