package io.github.trevortherrien.unbarred

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

/**
 * Compose UI test driven by the FakeSoundbarController — runs on an emulator/device
 * with no Bluetooth needed. Validates the connect flow and that a control tap sends
 * the right write.
 */
class ScreenUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun scanConnect_showsConnectedState() {
        val fake = FakeSoundbarController()
        compose.setContent { Screen(fake, onConnect = { fake.scanAndConnect() }) }

        compose.onNodeWithText("Not connected").assertIsDisplayed()
        compose.onNodeWithText("Scan & Connect").performClick()
        compose.onNodeWithText("Connected").assertIsDisplayed()
    }

    @Test
    fun eqMusic_sendsCorrectWrite() {
        val fake = FakeSoundbarController().apply { scanAndConnect() }
        compose.setContent { Screen(fake, onConnect = {}) }

        compose.onNodeWithText("Music").performClick()
        assert(fake.writes.any { it.char == Proto.CHAR_VZ_EQ && it.value == 1 }) {
            "Expected an EQ=Music write; recorded ${fake.writes}"
        }
    }
}
