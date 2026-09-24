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
}
