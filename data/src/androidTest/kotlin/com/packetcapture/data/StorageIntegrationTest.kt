package com.packetcapture.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.packetcapture.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class StorageIntegrationTest {
    private fun isolatedContext(): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val root = File(base.noBackupFilesDir, "test-${newId()}").apply { mkdirs() }
        return object : ContextWrapper(base) {
            override fun getApplicationContext() = this
            override fun getNoBackupFilesDir() = root
            override fun getDatabasePath(name: String) = File(root, name)
        }
    }
    @Test fun truncatedBodyRetainsOnlyContiguousPrefix() = runBlocking {
        val store = FileBodyStore(isolatedContext())
        val sink = store.open("session", "exchange", BodyPart.REQUEST, 5, 1000000)
        sink.append("abcdefghij".toByteArray()); sink.append("klmnop".toByteArray())
        val ref = sink.finish()
        assertTrue(ref.truncated); assertEquals(16, ref.observedBytes); assertEquals(5, ref.savedBytes)
        assertArrayEquals("abcde".toByteArray(), store.read(ref))
        assertEquals(ref, sink.finish())
    }
    @Test fun quotaRejectsBodyWithoutThrowingIntoNetworkCaller() = runBlocking {
        val store = FileBodyStore(isolatedContext())
        val sink = store.open("session", "exchange", BodyPart.RESPONSE, 1000, 0)
        sink.append(ByteArray(100)); val ref = sink.finish()
        assertTrue(ref.truncated); assertEquals(0, ref.savedBytes); assertEquals(100, ref.observedBytes)
    }
    @Test fun filesystemWriteFailureIsReportedWithoutHanging() = runBlocking {
        val context = isolatedContext()
        File(context.noBackupFilesDir, "bodies").writeText("not-a-directory")
        val store = FileBodyStore(context)
        val sink = store.open("session", "exchange", BodyPart.RESPONSE, 1000, 1000000)
        sink.append(ByteArray(100))
        val ref = kotlinx.coroutines.withTimeout(10000) { sink.finish() }
        assertTrue(ref.truncated); assertEquals(0, ref.savedBytes); assertTrue(ref.reason!!.contains("失败"))
    }
    @Test fun restartRecoversInterruptedAndDeletionRemovesFiles() = runBlocking {
        val context = isolatedContext(); val store = FileBodyStore(context)
        val repository = RoomCaptureRepository(context, store)
        val session = CaptureSession(title = "恢复测试", packages = listOf("test"))
        val exchange = HttpExchange(sessionId = session.id, connectionId = "c", method = "POST", url = "http://test/", protocol = "HTTP/1.1")
        repository.saveSession(session); repository.saveExchange(exchange)
        val sink = store.open(session.id, exchange.id, BodyPart.REQUEST, 100, 1000000)
        sink.append("body".toByteArray()); val ref = sink.finish()
        val reopened = RoomCaptureRepository(context, store)
        reopened.recoverInterrupted()
        assertEquals(Completion.INTERRUPTED, reopened.session(session.id)!!.completion)
        val recovered = reopened.exchange(exchange.id).first()!!
        assertTrue(recovered.requestBody!!.truncated)
        try { TrafficExporter(store).curl(recovered); fail("中断请求不能生成 cURL") } catch (_: IllegalArgumentException) { }
        reopened.deleteSession(session.id)
        assertNull(reopened.session(session.id)); assertFalse(File(context.noBackupFilesDir, "bodies/${ref.key}").exists())
    }
    @Test fun clearStorageRemovesAllHistoryOrphansAndBodyFilesAndAllowsReuse() = runBlocking {
        val context = isolatedContext()
        val store = FileBodyStore(context)
        val repository = RoomCaptureRepository(context, store)
        val retained = File(context.noBackupFilesDir, "retain-settings-and-certificate").apply { writeText("keep") }
        val sessions = List(105) { index ->
            CaptureSession(id = "session-$index", title = "会话 $index", packages = emptyList(), completion = Completion.COMPLETE)
        }
        sessions.forEach { session ->
            repository.saveSession(session)
            repository.saveConnection(ConnectionRecord("c-${session.id}", session.id, "127.0.0.1", 1000, "127.0.0.1", 80, "TCP", completion = Completion.COMPLETE))
            val sink = store.open(session.id, "exchange", BodyPart.RESPONSE, 2048, DEFAULT_STORAGE_LIMIT)
            sink.append(ByteArray(2048) { 42 })
            repository.saveExchange(HttpExchange(id = "e-${session.id}", sessionId = session.id, connectionId = "c-${session.id}",
                method = "GET", url = "https://example.test/${"a".repeat(4096)}", protocol = "HTTP/1.1",
                responseBody = sink.finish(), completion = Completion.COMPLETE))
        }
        // 未提交正文、没有会话记录的正文和元数据也必须清理。
        val orphan = store.open("orphan", "exchange", BodyPart.REQUEST, 100, DEFAULT_STORAGE_LIMIT)
        orphan.append("orphan body".toByteArray()); orphan.finish()
        File(context.noBackupFilesDir, "bodies/orphan/incomplete.body.part").writeText("partial body")
        repository.saveExchange(HttpExchange(id = "orphan", sessionId = "missing", connectionId = "missing",
            method = "GET", url = "https://example.test/orphan", protocol = "HTTP/1.1"))
        assertEquals(100, repository.sessions().first().size)
        val before = store.usedBytes()

        repository.clearStorage()

        assertTrue(repository.sessions().first().isEmpty())
        assertTrue(repository.exchanges(null).first().isEmpty())
        assertTrue(repository.connections(null).first().isEmpty())
        sessions.forEach { assertNull(repository.session(it.id)) }
        assertTrue(File(context.noBackupFilesDir, "bodies").listFiles()!!.isEmpty())
        assertEquals("keep", retained.readText())
        assertTrue("清理后应释放正文和数据库空间", store.usedBytes() < before)
        assertTrue("空数据库应只保留基础占用", store.usedBytes() < 256 * 1024)

        repository.clearStorage()
        val next = CaptureSession(title = "清理后", packages = emptyList(), completion = Completion.COMPLETE)
        repository.saveSession(next)
        val sink = store.open(next.id, "new-exchange", BodyPart.RESPONSE, 100, store.usedBytes() + 4096)
        sink.append("new body".toByteArray())
        val ref = sink.finish()
        assertFalse("清理后配额应可重新使用", ref.truncated)
        assertArrayEquals("new body".toByteArray(), store.read(ref))
        assertEquals(next, repository.session(next.id))
    }
    @Test fun clearStorageRejectsActiveSessionOutsideVisibleHistory() = runBlocking {
        val context = isolatedContext()
        val store = FileBodyStore(context)
        val repository = RoomCaptureRepository(context, store)
        val active = CaptureSession(id = "active", title = "活动会话", packages = emptyList(), startedAt = 0)
        repository.saveSession(active)
        repeat(101) { repository.saveSession(CaptureSession(title = "已完成", packages = emptyList(), completion = Completion.COMPLETE)) }
        val sink = store.open(active.id, "exchange", BodyPart.REQUEST, 100, DEFAULT_STORAGE_LIMIT)
        sink.append("retain body".toByteArray())
        val ref = sink.finish()
        assertFalse(repository.sessions().first().any { it.id == active.id })

        try {
            repository.clearStorage()
            fail("存在活动会话时不能清空")
        } catch (_: IllegalArgumentException) { }

        assertEquals(active, repository.session(active.id))
        assertEquals(100, repository.sessions().first().size)
        assertArrayEquals("retain body".toByteArray(), store.read(ref))
        repository.saveSession(active.copy(completion = Completion.COMPLETE))
        repository.clearStorage()
        assertTrue(repository.sessions().first().isEmpty())
        assertTrue(File(context.noBackupFilesDir, "bodies").listFiles()!!.isEmpty())
    }
}
