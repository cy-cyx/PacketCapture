package com.packetcapture

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.packetcapture.core.*
import com.packetcapture.data.FileBodyStore
import com.packetcapture.ui.BodyImageDecoder
import com.packetcapture.ui.BodyImageResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream

internal fun imageBytes(width: Int = 8, height: Int = 6, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): ByteArray {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    return try {
        bitmap.eraseColor(Color.rgb(36, 145, 192))
        ByteArrayOutputStream().use { output ->
            check(bitmap.compress(format, 90, output))
            output.toByteArray()
        }
    } finally { bitmap.recycle() }
}

class BodyImageDecoderTest {
    @Suppress("DEPRECATION")
    @Test fun commonImageFormatsAreDetectedFromBytes() {
        for ((format, mime) in listOf(Bitmap.CompressFormat.PNG to "image/png", Bitmap.CompressFormat.JPEG to "image/jpeg",
            Bitmap.CompressFormat.WEBP to "image/webp")) {
            val result = BodyImageDecoder.decode(Base64.getMimeEncoder(76, byteArrayOf(10)).encodeToString(imageBytes(format = format)))
            assertTrue("Expected $mime image", result is BodyImageResult.Success)
            result as BodyImageResult.Success
            assertEquals(8, result.width); assertEquals(6, result.height); assertEquals(mime, result.mimeType)
            result.bitmap.recycle()
        }
        val gif = BodyImageDecoder.decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7") as BodyImageResult.Success
        assertEquals("image/gif", gif.mimeType); assertEquals(1, gif.width); assertEquals(1, gif.height)
        gif.bitmap.recycle()
    }

    @Test fun invalidOrNonImageBytesReturnFailure() {
        for (input in listOf("", "not valid base64!", Base64.getEncoder().encodeToString(byteArrayOf(0, 1, 2, 3, -1)),
            Base64.getEncoder().encodeToString(imageBytes().copyOf(12)))) {
            assertTrue(BodyImageDecoder.decode(input) is BodyImageResult.Failure)
        }
    }

    @Test fun largeImageIsSampledBeforeAllocatingPixels() {
        val result = BodyImageDecoder.decode(Base64.getEncoder().encodeToString(imageBytes(4096, 128))) as BodyImageResult.Success
        assertEquals(4096, result.width); assertEquals(128, result.height)
        assertTrue(result.bitmap.width <= 2048); assertTrue(result.bitmap.height <= 2048)
        assertTrue(result.bitmap.byteCount <= 2048 * 2048 * 4)
        result.bitmap.recycle()
    }

    @Test fun wrappedBase64AtSavedBodyLimitIsAcceptedButLargerBodiesAreRejected() {
        val bytes = imageBytes().copyOf(DEFAULT_BODY_LIMIT.toInt())
        val encoded = Base64.getMimeEncoder().encodeToString(bytes)
        val image = BodyImageDecoder.decode(encoded) as BodyImageResult.Success
        assertEquals(8, image.width); assertEquals(6, image.height)
        image.bitmap.recycle()
        val oversized = Base64.getMimeEncoder().encodeToString(bytes.copyOf(bytes.size + 1))
        assertTrue(BodyImageDecoder.decode(oversized) is BodyImageResult.Failure)
    }

    @Test fun compressedOctetStreamRetainsImageBytesAndTruncationWarning() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "image-preview-${newId()}")
        val context = object : ContextWrapper(base) {
            override fun getNoBackupFilesDir() = root
            override fun getDatabasePath(name: String) = File(root, name)
        }
        val store = FileBodyStore(context)
        val png = imageBytes()
        for (encoding in listOf("gzip", "deflate")) {
            val compressed = ByteArrayOutputStream().apply {
                val stream = if (encoding == "gzip") GZIPOutputStream(this) else DeflaterOutputStream(this)
                stream.use { it.write(png) }
            }.toByteArray()
            val sessionId = newId()
            try {
                val sink = store.open(sessionId, newId(), BodyPart.RESPONSE, DEFAULT_BODY_LIMIT, DEFAULT_STORAGE_LIMIT)
                sink.append(compressed)
                val ref = sink.finish()
                val preview = store.preview(ref.copy(truncated = true, reason = "测试截断提示"),
                    listOf(Header("Content-Type", "application/octet-stream"), Header("Content-Encoding", encoding)))
                assertTrue(preview.binary); assertTrue(preview.limited); assertTrue(preview.note!!.contains("测试截断提示"))
                assertArrayEquals(png, Base64.getMimeDecoder().decode(preview.text))
                val image = BodyImageDecoder.decode(preview.text) as BodyImageResult.Success
                assertEquals(8, image.width); assertEquals(6, image.height)
                image.bitmap.recycle()
            } finally { store.deleteSession(sessionId) }
        }
    }
}
