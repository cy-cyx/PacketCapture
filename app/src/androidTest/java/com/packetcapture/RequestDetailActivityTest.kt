package com.packetcapture

import com.packetcapture.ui.home.MainActivity
import com.packetcapture.ui.detail.RequestDetailActivity
import android.app.Activity
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.packetcapture.core.*
import com.packetcapture.ui.home.CaptureViewModel
import com.packetcapture.export.ExportRequest
import com.packetcapture.ui.detail.RequestDetailViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class RequestDetailActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (context.applicationContext as PacketCaptureApp).container
    private lateinit var session: CaptureSession
    private lateinit var exchange: HttpExchange
    private val rawRequest = """{"message":"\u4f60\u597d"}"""

    @Before fun saveFixture() = runBlocking {
        container.ready.await()
        session = CaptureSession(title = "Activity navigation fixture", packages = emptyList(), completion = Completion.COMPLETE)
        container.repository.saveSession(session)
        val connection = ConnectionRecord(newId(), session.id, "127.0.0.1", 12345, "192.0.2.1", 443, "TCP", completion = Completion.COMPLETE)
        container.repository.saveConnection(connection)
        val id = newId()
        val request = container.bodies.open(session.id, id, BodyPart.REQUEST, DEFAULT_BODY_LIMIT, DEFAULT_STORAGE_LIMIT)
        request.append(rawRequest.toByteArray())
        val response = container.bodies.open(session.id, id, BodyPart.RESPONSE, DEFAULT_BODY_LIMIT, DEFAULT_STORAGE_LIMIT)
        response.append("detail response".toByteArray())
        exchange = HttpExchange(id, session.id, connection.id, method = "POST", url = "https://example.test/activity-fixture/selected",
            protocol = "HTTP/2", status = 200, completion = Completion.COMPLETE,
            requestHeaders = listOf(Header("Content-Type", "application/json")), requestBody = request.finish(),
            responseHeaders = listOf(Header("Content-Type", "text/plain")), responseBody = response.finish())
        container.repository.saveExchange(exchange)
    }

    @After fun deleteFixture() = runBlocking { container.repository.deleteSession(session.id) }

    @Test fun requestClickOpensSeparateActivityAndBothBackActionsPreserveHome() {
        runBlocking {
            repeat(24) { index ->
                container.repository.saveExchange(exchange.copy(id = newId(), url = "https://example.test/activity-fixture/$index",
                    startedAt = exchange.startedAt + index + 1L, requestBody = null, responseBody = null))
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var main: MainActivity
            lateinit var model: CaptureViewModel
            val filter = ExchangeFilter(query = "activity-fixture", method = "POST")
            scenario.onActivity {
                main = it
                model = ViewModelProvider(it)[CaptureViewModel::class.java]
                model.selectSession(session.id)
                model.setFilter(filter)
            }
            compose.waitUntil(10000) { model.state.value.ready && model.state.value.requests.size == 25 }
            val rowTag = "request-${exchange.id}"
            compose.onNodeWithTag("capture-list").performScrollToNode(hasTestTag(rowTag))
            val scrollBefore = captureScroll()
            assertTrue(scrollBefore > 0f)

            repeat(2) { attempt ->
                compose.onNodeWithTag(rowTag).performClick()
                compose.waitUntil(10000) { resumedActivity() is RequestDetailActivity }
                waitForDetail()
                compose.onNodeWithText("请求详情").assertIsDisplayed()
                compose.onNodeWithTag("capture-list").assertDoesNotExist()
                if (attempt == 0) compose.onNodeWithContentDescription("返回").performClick() else pressBack()
                compose.waitUntil(10000) { resumedActivity() === main }
                compose.onNodeWithTag(rowTag).assertIsDisplayed()
                assertEquals(scrollBefore, captureScroll(), 0.1f)
                assertEquals(session.id, model.state.value.selectedSession)
                assertEquals(filter, model.state.value.filter)
                scenario.onActivity { assertSame(model, ViewModelProvider(it)[CaptureViewModel::class.java]) }
            }
        }
    }

    @Test fun detailLoadsWithoutHomeAndRecreationKeepsRequestAndSelectedTab() {
        ActivityScenario.launch<RequestDetailActivity>(RequestDetailActivity.createIntent(context, exchange.id)).use { scenario ->
            waitForDetail()
            compose.onNodeWithText("请求", useUnmergedTree = true).performClick()
            compose.onNodeWithTag("detail-content").performScrollToNode(hasText("原文"))
            compose.onNodeWithText("原文").performClick()
            scenario.recreate()
            waitForDetail()
            compose.onNodeWithText("请求").assertIsSelected()
            compose.onNodeWithTag("detail-content").performScrollToNode(hasText("原文"))
            compose.onNodeWithText("原文").assertIsSelected()
            compose.onNodeWithTag("detail-content").performScrollToNode(hasText(rawRequest))
            compose.onNodeWithText(rawRequest).assertIsDisplayed()
            scenario.onActivity {
                assertEquals(exchange.id, ViewModelProvider(it)[RequestDetailViewModel::class.java].state.value.detail.exchange?.id)
            }
        }
    }

    @Test fun detailObservesUpdatesAndHandlesDeletedRequest() {
        ActivityScenario.launch<RequestDetailActivity>(RequestDetailActivity.createIntent(context, exchange.id)).use { scenario ->
            waitForDetail()
            runBlocking { container.repository.saveExchange(exchange.copy(status = 201)) }
            compose.waitUntil(10000) { compose.onAllNodesWithText("201").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("201").assertIsDisplayed()
            runBlocking { container.repository.deleteSession(session.id) }
            compose.waitUntil(10000) { compose.onAllNodesWithText("请求已删除").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("导出请求/响应").assertIsNotEnabled()
            compose.onNodeWithContentDescription("返回").performClick()
            compose.waitUntil(10000) { scenario.state == Lifecycle.State.DESTROYED }
        }
    }

    @Test fun missingRequestIdFinishesSafely() {
        ActivityScenario.launch<RequestDetailActivity>(Intent(context, RequestDetailActivity::class.java)).use { scenario ->
            assertEquals(Lifecycle.State.DESTROYED, scenario.state)
        }
    }

    @Test fun sharedExporterUsesExplicitIdsForBodyCurlAndHar() = runBlocking {
        val body = ByteArrayOutputStream()
        container.documents.write(ExportRequest("request-body", exchange.id, rawBody = true), body)
        assertEquals(rawRequest, body.toString("UTF-8"))
        val curl = ByteArrayOutputStream()
        container.documents.write(ExportRequest("curl", exchange.id), curl)
        ZipInputStream(curl.toByteArray().inputStream()).use { zip ->
            val entries = mutableMapOf<String, ByteArray>()
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
            assertEquals(setOf("request.txt", "response.txt", "curl.txt"), entries.keys)
            val script = entries.getValue("curl.txt").toString(Charsets.UTF_8)
            assertTrue(script.contains(exchange.url))
            assertTrue(script.contains("--compressed"))
            assertTrue(script.contains("--data-raw '$rawRequest'"))
            val requestText = entries.getValue("request.txt").toString(Charsets.UTF_8)
            assertTrue(requestText.contains("POST ${exchange.url} HTTP/2"))
            assertTrue(requestText.contains("你好"))
            val responseText = entries.getValue("response.txt").toString(Charsets.UTF_8)
            assertTrue(responseText.contains("HTTP/2 200"))
            assertTrue(responseText.contains("detail response"))
        }
        val har = ByteArrayOutputStream()
        container.documents.write(ExportRequest("har", session.id), har)
        assertTrue(har.toString("UTF-8").contains(exchange.url))
    }

    private fun waitForDetail() {
        compose.waitUntil(10000) { compose.onAllNodesWithText(exchange.url).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun captureScroll(): Float = compose.onNodeWithTag("capture-list").fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun resumedActivity(): Activity? {
        var activity: Activity? = null
        instrumentation.runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
        }
        return activity
    }
}
