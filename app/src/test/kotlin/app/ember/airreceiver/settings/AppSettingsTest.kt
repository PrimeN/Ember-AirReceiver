// Modified for Ember AirReceiver (2026).
// Licensed under the Apache License, Version 2.0 — see LICENSE and NOTICE.
package app.ember.airreceiver.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AppSettingsTest — Unit tests for [AppSettings] data class.
 *
 * WHY: [AppSettings] contains computed properties and constants that are
 * used throughout the app. Regression tests ensure that default values,
 * validation logic, and computed properties always behave correctly.
 *
 * WHAT WE TEST:
 * - Default values match the design spec
 * - [AppSettings.effectiveDisplayName] trims whitespace correctly
 * - [AppSettings.anyProtocolEnabled] returns correct results for all combinations
 * - [AppSettings.DISPLAY_NAME_MAX_LENGTH] constant value
 * - copy() semantics (standard data class behaviour)
 */
class AppSettingsTest {

    // ─── Default values ──────────────────────────────────────────────────────

    @Test
    fun `new controls preserve existing remembered pairing and resolution`() {
        val legacy = AppSettings(airPlayPinAuthEnabled = true, forceHighResolution = true)
        assertEquals(AirPlayPinPolicy.REMEMBER, legacy.effectivePinPolicy)
        assertEquals(MirrorResolution.QHD, legacy.effectiveMirrorResolution)
        assertEquals(AirPlayPinPolicy.OFF, legacy.copy(airPlayPinPolicy = AirPlayPinPolicy.OFF).effectivePinPolicy)
        assertEquals(MirrorResolution.AUTO, legacy.copy(mirrorResolution = MirrorResolution.AUTO).effectiveMirrorResolution)
        assertTrue(AppSettings.DEFAULT.startOnLaunch)
        assertFalse(AppSettings.DEFAULT.startOnBoot)
    }

    @Test
    fun `default settings have empty display name`() {
        assertEquals("", AppSettings.DEFAULT.displayName)
    }

    @Test
    fun `default settings have all protocols enabled`() {
        assertTrue(AppSettings.DEFAULT.airPlayEnabled)
        assertTrue(AppSettings.DEFAULT.miracastEnabled)
        assertTrue(AppSettings.DEFAULT.castEnabled)
        assertTrue(AppSettings.DEFAULT.dlnaEnabled)
    }

    @Test
    fun `default settings have pin auth disabled`() {
        assertFalse(AppSettings.DEFAULT.airPlayPinAuthEnabled)
    }

    @Test
    fun `default settings have start on boot disabled`() {
        assertFalse(AppSettings.DEFAULT.startOnBoot)
    }

    @Test
    fun `default settings have debug overlay disabled`() {
        assertFalse(AppSettings.DEFAULT.showDebugOverlay)
    }

    @Test
    fun `DISPLAY_NAME_MAX_LENGTH is 63`() {
        assertEquals(63, AppSettings.DISPLAY_NAME_MAX_LENGTH)
    }

    // ─── effectiveDisplayName ─────────────────────────────────────────────────

    @Test
    fun `effectiveDisplayName returns trimmed name`() {
        val settings = AppSettings(displayName = "  Living Room TV  ")
        assertEquals("Living Room TV", settings.effectiveDisplayName)
    }

    @Test
    fun `effectiveDisplayName returns empty string for blank name`() {
        val settings = AppSettings(displayName = "   ")
        assertEquals("", settings.effectiveDisplayName)
    }

    @Test
    fun `effectiveDisplayName returns name unchanged when no surrounding whitespace`() {
        val settings = AppSettings(displayName = "Ember AirReceiver")
        assertEquals("Ember AirReceiver", settings.effectiveDisplayName)
    }

    @Test
    fun `effectiveDisplayName handles empty string`() {
        val settings = AppSettings(displayName = "")
        assertEquals("", settings.effectiveDisplayName)
    }

    // ─── anyProtocolEnabled ───────────────────────────────────────────────────

    @Test
    fun `anyProtocolEnabled is true when all protocols are enabled`() {
        val settings = AppSettings(
            airPlayEnabled = true,
            miracastEnabled = true,
            castEnabled = true,
            dlnaEnabled = true
        )
        assertTrue(settings.anyProtocolEnabled)
    }

    @Test
    fun `anyProtocolEnabled is true when only AirPlay is enabled`() {
        val settings = AppSettings(
            airPlayEnabled = true,
            miracastEnabled = false,
            castEnabled = false,
            dlnaEnabled = false
        )
        assertTrue(settings.anyProtocolEnabled)
    }

    @Test
    fun `anyProtocolEnabled is true when only Miracast is enabled`() {
        val settings = AppSettings(
            airPlayEnabled = false,
            miracastEnabled = true,
            castEnabled = false,
            dlnaEnabled = false
        )
        assertTrue(settings.anyProtocolEnabled)
    }

    @Test
    fun `anyProtocolEnabled is true when only Cast is enabled`() {
        val settings = AppSettings(
            airPlayEnabled = false,
            miracastEnabled = false,
            castEnabled = true,
            dlnaEnabled = false
        )
        assertTrue(settings.anyProtocolEnabled)
    }

    @Test
    fun `anyProtocolEnabled is true when only DLNA is enabled`() {
        val settings = AppSettings(
            airPlayEnabled = false,
            miracastEnabled = false,
            castEnabled = false,
            dlnaEnabled = true
        )
        assertTrue(settings.anyProtocolEnabled)
    }

    @Test
    fun `anyProtocolEnabled is false when all protocols are disabled`() {
        val settings = AppSettings(
            airPlayEnabled = false,
            miracastEnabled = false,
            castEnabled = false,
            dlnaEnabled = false
        )
        assertFalse(settings.anyProtocolEnabled)
    }

    // ─── copy() and equality ──────────────────────────────────────────────────

    @Test
    fun `copy preserves unchanged fields`() {
        val original = AppSettings(displayName = "TV", airPlayEnabled = true, startOnBoot = false)
        val updated = original.copy(startOnBoot = true)

        assertEquals("TV", updated.displayName)
        assertTrue(updated.airPlayEnabled)
        assertTrue(updated.startOnBoot)
    }

    @Test
    fun `two instances with same values are equal`() {
        val a = AppSettings(displayName = "TV", airPlayEnabled = true)
        val b = AppSettings(displayName = "TV", airPlayEnabled = true)
        assertEquals(a, b)
    }

    @Test
    fun `two instances with different values are not equal`() {
        val a = AppSettings(displayName = "TV1")
        val b = AppSettings(displayName = "TV2")
        assertTrue(a != b)
    }
}
