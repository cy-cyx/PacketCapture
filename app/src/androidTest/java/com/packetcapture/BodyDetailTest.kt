package com.packetcapture

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp
import com.packetcapture.core.*
import com.packetcapture.ui.DetailScreen
import com.packetcapture.ui.DetailState
import com.packetcapture.ui.ExportRequest
import com.packetcapture.ui.theme.PacketCaptureTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Base64

class BodyDetailTest {
    @get:Rule val compose = createComposeRule()

    private fun binaryDetail(bytes: ByteArray, id: String = "binary", limited: Boolean = false): DetailState = DetailState(
        exchange = HttpExchange(id = id, sessionId = "s", connectionId = "c", method = "GET", url = "https://example.test/image",
            protocol = "HTTP/2", completion = Completion.COMPLETE,
            responseHeaders = listOf(Header("Content-Type", "application/octet-stream")),
            responseBody = BodyRef("body", bytes.size.toLong(), bytes.size.toLong(), limited)),
        response = BodyPreview(Base64.getMimeEncoder(76, byteArrayOf(10)).encodeToString(bytes), true, limited, if (limited) "达到正文保存上限" else null))

    private fun tryImage() {
        compose.onNodeWithTag("detail-content").performScrollToNode(hasText("尝试转为图片"))
        compose.onNodeWithText("尝试转为图片").performClick()
    }

    private fun decodeBase64() {
        compose.onNodeWithTag("detail-content").performScrollToNode(hasText("解码 Base64"))
        compose.onNodeWithText("解码 Base64").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("正在解码 Base64…").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun binaryBase64CanBeDecodedCopiedExportedAndRestoredOnSmallScreen() {
        val text = "你好，Base64 😀\n{\"message\":\"decoded\"}"
        val detail = binaryDetail(text.toByteArray())
        var exported: ExportRequest? = null
        var clipboard: androidx.compose.ui.platform.ClipboardManager? = null
        compose.setContent { PacketCaptureTheme {
            clipboard = LocalClipboardManager.current
            Box(Modifier.requiredSize(320.dp, 480.dp)) {
                DetailScreen(detail, export = { exported = it })
            }
        } }
        decodeBase64()
        compose.onNodeWithTag("detail-content").performScrollToNode(hasText(text))
        compose.onNodeWithText(text).assertIsDisplayed()
        compose.onNodeWithTag("detail-content").performScrollToNode(hasContentDescription("复制正文"))
        compose.onNodeWithContentDescription("复制正文").performClick()
        compose.runOnIdle { assertEquals(text, clipboard!!.getText()!!.text) }
        compose.onNodeWithText("导出正文").performClick()
        compose.runOnIdle { assertEquals(ExportRequest("response-body", "binary", decodeBase64 = true), exported) }
        compose.onNodeWithTag("detail-content").performScrollToNode(hasText("Base64"))
        compose.onNodeWithText("Base64").performClick()
        compose.onNodeWithTag("detail-content").performScrollToNode(hasText(detail.response!!.text))
        compose.onNodeWithText(detail.response!!.text).assertIsDisplayed()
        compose.onNodeWithTag("detail-content").performScrollToNode(hasText("导出正文"))
        compose.onNodeWithText("导出正文").performClick()
        compose.runOnIdle { assertEquals(ExportRequest("response-body", "binary"), exported) }
    }

    @Test fun textBase64RequestIsDetectedAndDecodeModeResetsAcrossTabsAndExchanges() {
        val text = "😀???你好"
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())
        val binary = binaryDetail("response".toByteArray())
        val detail = mutableStateOf(binary.copy(
            exchange = binary.exchange!!.copy(requestBody = BodyRef("request", encoded.length.toLong(), encoded.length.toLong())),
            request = BodyPreview(encoded, false, false)))
        var exported: ExportRequest? = null
        compose.setContent { PacketCaptureTheme { DetailScreen(detail.value, export = { exported = it }) } }
        compose.onNodeWithText("请求", useUnmergedTree = true).performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("解码 Base64").fetchSemanticsNodes().isNotEmpty() }
        decodeBase64()
        compose.onNodeWithTag("detail-content").performScrollToNode(hasText(text))
        compose.onNodeWithText(text).assertIsDisplayed()
        compose.onNodeWithTag("detail-content").performScrollToNode(hasText("导出正文"))
        compose.onNodeWithText("导出正文").performClick()
        compose.runOnIdle { assertEquals(ExportRequest("request-body", "binary", decodeBase64 = true), exported) }
        compose.onNodeWithText("响应", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("detail-content").performScrollToNode(hasText("Base64"))
        compose.onNodeWithText("Base64").assertIsSelected()
        decodeBase64()
        compose.runOnIdle { detail.value = binaryDetail("next".toByteArray(), id = "next") }
        compose.onNodeWithTag("detail-content").performScrollToNode(hasText("Base64"))
        compose.onNodeWithText("Base64").assertIsSelected()
        compose.onNodeWithText("已解码 Base64 · UTF-8 文本").assertDoesNotExist()
    }

    @Test fun nonTextBase64KeepsTruncationWarningAndDisablesDecodedExport() {
        val detail = binaryDetail(byteArrayOf(0, 1, 2, -1), limited = true)
        compose.setContent { PacketCaptureTheme { DetailScreen(detail, export = {}) } }
        decodeBase64()
        compose.onNodeWithTag("detail-content").performScrollToNode(hasText("不是可显示的 UTF-8 文本", substring = true))
        compose.onNodeWithText("不是可显示的 UTF-8 文本", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("detail-content").performScrollToNode(hasText("正文不完整：达到正文保存上限"))
        compose.onNodeWithText("正文不完整：达到正文保存上限").assertIsDisplayed()
        compose.onNodeWithTag("detail-content").performScrollToNode(hasText("导出正文"))
        compose.onNodeWithText("导出正文").assertIsNotEnabled()
        compose.onNodeWithContentDescription("复制正文").assertIsNotEnabled()
        compose.onNodeWithText("Base64").performClick()
        compose.onNodeWithTag("detail-content").performScrollToNode(hasText("导出正文"))
        compose.onNodeWithText("导出正文").assertIsEnabled()
    }

    @Test fun binaryResponseCanBePreviewedOnDemandAndSwitchedBack() {
        val detail = binaryDetail(imageBytes())
        var exported: ExportRequest? = null
        compose.setContent { PacketCaptureTheme { DetailScreen(detail, export = { exported = it }) } }
        compose.onNodeWithContentDescription("正文图片预览").assertDoesNotExist()
        tryImage()
        compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("正文图片预览").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("detail-content").performScrollToNode(hasContentDescription("正文图片预览"))
        compose.onNodeWithContentDescription("正文图片预览").assertIsDisplayed()
        compose.onNodeWithTag("detail-content").performScrollToNode(hasText("8 × 6 · image/png"))
        compose.onNodeWithText("8 × 6 · image/png").assertIsDisplayed()
        compose.onNodeWithTag("detail-content").performScrollToNode(hasText("导出 Base64"))
        compose.onNodeWithText("导出 Base64").performClick()
        compose.runOnIdle { assertEquals(ExportRequest("response-body", "binary"), exported) }
        compose.onNodeWithText("Base64").performClick()
        compose.onNodeWithTag("detail-content").performScrollToNode(hasText(detail.response!!.text))
        compose.onNodeWithText(detail.response!!.text).assertIsDisplayed()
        compose.onNodeWithContentDescription("正文图片预览").assertDoesNotExist()
    }

    @Test fun unsupportedBinaryShowsFailureAndKeepsTruncationWarning() {
        val detail = binaryDetail(byteArrayOf(0, 1, 2, -1), limited = true)
        compose.setContent { PacketCaptureTheme { DetailScreen(detail, export = {}) } }
        tryImage()
        compose.waitUntil(10000) { compose.onAllNodesWithText("无法识别为图片", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("detail-content").performScrollToNode(hasText("无法识别为图片", substring = true))
        compose.onNodeWithText("无法识别为图片", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("detail-content").performScrollToNode(hasText("正文不完整：达到正文保存上限"))
        compose.onNodeWithText("正文不完整：达到正文保存上限").assertIsDisplayed()
        compose.onNodeWithTag("detail-content").performScrollToNode(hasText("Base64"))
        compose.onNodeWithText("Base64").performClick()
        compose.onNodeWithTag("detail-content").performScrollToNode(hasText(detail.response!!.text))
        compose.onNodeWithText(detail.response!!.text).assertIsDisplayed()
    }

    @Test fun switchingExchangeDoesNotKeepPreviousImage() {
        val detail = mutableStateOf(binaryDetail(imageBytes()))
        compose.setContent { PacketCaptureTheme { DetailScreen(detail.value, export = {}) } }
        tryImage()
        compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("正文图片预览").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { detail.value = binaryDetail(byteArrayOf(0, 1, 2), id = "next-response") }
        compose.onNodeWithContentDescription("正文图片预览").assertDoesNotExist()
        compose.onNodeWithTag("detail-content").performScrollToNode(hasText("Base64"))
        compose.onNodeWithText("Base64").assertIsSelected()
    }

    @Test fun longUrlAndRequestTailBeyond64KAreScrollableOnSmallScreen() {
        val url = "https://example.test/" + "segment/".repeat(40) + "?query=" + "value".repeat(600) + "&last=LAST_URL_FIELD"
        val text = "request value\n".repeat(6000) + "LAST_REQUEST_FIELD"
        var exported: ExportRequest? = null
        val exchange = HttpExchange(id = "full-body", sessionId = "s", connectionId = "c", method = "POST",
            url = url, protocol = "HTTP/2", completion = Completion.COMPLETE,
            requestBody = BodyRef("body", text.length.toLong(), text.length.toLong()))
        compose.setContent { PacketCaptureTheme {
            Box(Modifier.requiredSize(320.dp, 480.dp)) {
                DetailScreen(DetailState(exchange = exchange, request = BodyPreview(text, false, false)), export = { exported = it })
            }
        } }
        listOf("概览", "请求", "响应", "连接", "导出 cURL", "导出 HAR").forEach {
            compose.onNodeWithText(it, useUnmergedTree = true).assertIsDisplayed()
        }
        compose.onNodeWithText(url).assertExists()
        val content = compose.onNodeWithTag("detail-content").assertIsDisplayed()
        val scrollBefore = content.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        content.performTouchInput { swipeUp() }
        assertTrue(content.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > scrollBefore)
        compose.onNodeWithText("请求", useUnmergedTree = true).performClick()
        compose.onNodeWithText("尝试转为图片").assertDoesNotExist()
        compose.onNodeWithTag("detail-content").performScrollToNode(hasText("导出正文"))
        compose.onNodeWithText("导出正文").performClick()
        compose.runOnIdle { assertEquals(ExportRequest("request-body", "full-body"), exported) }
        compose.onNodeWithContentDescription("复制正文").assertIsNotEnabled()
        compose.onNodeWithTag("detail-content").performScrollToNode(hasText("LAST_REQUEST_FIELD", substring = true))
        compose.onNodeWithText("LAST_REQUEST_FIELD", substring = true).assertExists()
    }

    @Test fun captureTruncationIsShownBeforeBodyContent() {
        val exchange = HttpExchange(sessionId = "s", connectionId = "c", method = "POST", url = "https://example.test/body", protocol = "HTTP/1.1",
            requestBody = BodyRef("body", 20, 6, true, "达到正文保存上限"))
        compose.setContent { PacketCaptureTheme {
            DetailScreen(DetailState(exchange = exchange, request = BodyPreview("prefix", false, true, "达到正文保存上限")), export = {})
        } }
        compose.onNodeWithText("请求", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("detail-content").performScrollToNode(hasText("正文不完整：达到正文保存上限"))
        compose.onNodeWithText("正文不完整：达到正文保存上限").assertIsDisplayed()
    }

    @Test fun decodedViewIsDefaultAndRawSwitchAlsoControlsExport() {
        val raw = """{"message":"\u4f60\u597d\nnext"}"""
        val decoded = "{\n  \"message\": \"你好\nnext\"\n}"
        var exported: ExportRequest? = null
        val exchange = HttpExchange(id = "escapes", sessionId = "s", connectionId = "c", method = "POST",
            url = "https://example.test/body", protocol = "HTTP/2", completion = Completion.COMPLETE,
            requestBody = BodyRef("body", raw.length.toLong(), raw.length.toLong()))
        compose.setContent { PacketCaptureTheme {
            DetailScreen(DetailState(exchange = exchange, request = BodyPreview(decoded, false, false, rawText = raw)), export = { exported = it })
        } }
        compose.onNodeWithText("请求", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("detail-content").performScrollToNode(hasText(decoded))
        compose.onNodeWithText(decoded).assertIsDisplayed()
        compose.onNodeWithTag("detail-content").performScrollToNode(hasText("原文"))
        compose.onNodeWithText("原文").performClick()
        compose.onNodeWithTag("detail-content").performScrollToNode(hasText(raw))
        compose.onNodeWithText(raw).assertIsDisplayed()
        compose.onNodeWithTag("detail-content").performScrollToNode(hasText("导出正文"))
        compose.onNodeWithText("导出正文").performClick()
        compose.runOnIdle { assertEquals(ExportRequest("request-body", "escapes", true), exported) }
    }
}
