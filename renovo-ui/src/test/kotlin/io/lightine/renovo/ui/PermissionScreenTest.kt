package io.lightine.renovo.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class PermissionScreenTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun android7HasTheDeviceWideUnknownSourcesSetting() {
        assertEquals(PermissionScreen.UnknownSources, permissionScreenFor(24))
        assertEquals(PermissionScreen.UnknownSources, permissionScreenFor(25))
    }

    @Test
    fun android8To16HaveTheSwitch() {
        assertEquals(PermissionScreen.Switch, permissionScreenFor(26))
        assertEquals(PermissionScreen.Switch, permissionScreenFor(36))
    }

    @Test
    fun android17AndLaterHaveTheAlwaysAllowChoice() {
        assertEquals(PermissionScreen.AlwaysAllow, permissionScreenFor(37))
        assertEquals(PermissionScreen.AlwaysAllow, permissionScreenFor(38))
    }

    @Test
    fun theDefaultReadsTheDeviceApiLevel() {
        assertEquals(PermissionScreen.AlwaysAllow, permissionScreenFor())
    }

    @Test
    fun theAndroid7AndAndroid17StepsCannotBothBeOn() {
        assertThrows(IllegalArgumentException::class.java) {
            rule.setContent { PermissionSteps(legacyStepText = true, android17Step = true) }
        }
    }
}
