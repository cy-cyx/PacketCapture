package com.packetcapture.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.packetcapture.core.DEFAULT_BODY_LIMIT
import java.util.Base64

internal sealed interface BodyImageResult {
    data class Success(val bitmap: Bitmap, val width: Int, val height: Int, val mimeType: String?) : BodyImageResult
    data class Failure(val message: String) : BodyImageResult
}

/** 输入是正文解析器生成的 Base64，gzip/deflate 已在正文读取时解压；仅在用户选择图片时调用。 */
internal object BodyImageDecoder {
    private const val MAX_PREVIEW_EDGE = 2048
    private const val MAX_BASE64_CHARS = ((DEFAULT_BODY_LIMIT + 2) / 3) * 4
    private const val MAX_INPUT_CHARS = MAX_BASE64_CHARS + ((MAX_BASE64_CHARS - 1) / 76) * 2
    private const val UNRECOGNIZED = "无法识别为图片：内容可能不是图片、格式不受支持，或数据不完整。可切回 Base64 查看。"

    fun decode(base64: String): BodyImageResult {
        if (base64.length > MAX_INPUT_CHARS) return BodyImageResult.Failure("内容超过 5 MiB 图片预览上限。")
        return try {
            // BodyDecoder 每 76 字符换行；同时接受不换行和 CRLF 换行的 Base64。
            val bytes = Base64.getMimeDecoder().decode(base64)
            if (bytes.size > DEFAULT_BODY_LIMIT) return BodyImageResult.Failure("内容超过 5 MiB 图片预览上限。")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return BodyImageResult.Failure(UNRECOGNIZED)
            // 先读尺寸，再按长边采样，避免体积很小但像素很多的图片耗尽内存。
            var sample = 1
            while ((maxOf(bounds.outWidth, bounds.outHeight).toLong() + sample - 1) / sample > MAX_PREVIEW_EDGE) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                ?: return BodyImageResult.Failure(UNRECOGNIZED)
            BodyImageResult.Success(bitmap, bounds.outWidth, bounds.outHeight, bounds.outMimeType)
        } catch (_: OutOfMemoryError) {
            BodyImageResult.Failure("图片过大或可用内存不足，无法预览。可切回 Base64 查看。")
        } catch (_: Exception) {
            BodyImageResult.Failure(UNRECOGNIZED)
        }
    }
}
