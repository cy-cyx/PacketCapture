package com.packetcapture

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.packetcapture.core.AppSettings
import com.packetcapture.ui.settings.SettingsScreen
import com.packetcapture.ui.theme.PacketCaptureTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsStorageTest {
    @get:Rule val compose = createComposeRule()

    @Test fun clearStorageRequiresConfirmationAndCancelKeepsDataOnSmallScreen() {
        var clears = 0
        compose.setContent { PacketCaptureTheme {
            Box(Modifier.requiredSize(320.dp, 480.dp)) {
                SettingsScreen(AppSettings(), null, 1024, {}, {}, {}, {}, {},
                    clearStorage = { clears++ }, canClearStorage = true, clearingStorage = false)
            }
        } }
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("clear-storage"))
        compose.onNodeWithTag("clear-storage").assertIsDisplayed().performClick()
        compose.onNodeWithText("清空所有存储？").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, clears) }
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("确认清空").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, clears) }
        compose.onNodeWithTag("clear-storage").performClick()
        compose.onNodeWithText("确认清空").performClick()
        compose.runOnIdle { assertEquals(1, clears) }
        compose.onNodeWithText("确认清空").assertDoesNotExist()
    }

    @Test fun clearStorageIsDisabledWhileCapturingOrClearingIncludingOpenDialog() {
        val allowed = mutableStateOf(false)
        val clearing = mutableStateOf(false)
        var clears = 0
        compose.setContent { PacketCaptureTheme {
            SettingsScreen(AppSettings(), null, 1024, {}, {}, {}, {}, {},
                clearStorage = { clears++ }, canClearStorage = allowed.value, clearingStorage = clearing.value)
        } }
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("clear-storage"))
        compose.onNodeWithTag("clear-storage").assertIsNotEnabled()
        compose.runOnIdle { allowed.value = true }
        compose.onNodeWithTag("clear-storage").performClick()
        compose.runOnIdle { allowed.value = false }
        compose.onNodeWithText("确认清空").assertIsNotEnabled()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { clearing.value = true }
        compose.onNodeWithTag("clear-storage").assertIsNotEnabled()
        compose.onNodeWithText("正在清空…").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, clears) }
    }
}
