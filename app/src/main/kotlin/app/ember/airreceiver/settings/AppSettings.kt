// Modified for Ember AirReceiver (2026).
// Licensed under the Apache License, Version 2.0 — see LICENSE and NOTICE.
package app.ember.airreceiver.settings

import app.ember.airreceiver.service.Protocol

enum class AirPlayPinPolicy { OFF, REMEMBER, EACH_SESSION }

enum class MirrorResolution(val width: Int, val height: Int) {
    AUTO(0, 0), HD(1280, 720), FULL_HD(1920, 1080), QHD(2560, 1440), UHD(3840, 2160), CUSTOM(0, 0)
}

data class MirrorFormat(val width: Int, val height: Int, val fps: Int, val hevc: Boolean = false)

/**
 * AppSettings — Immutable data model for all user-configurable Ember AirReceiver settings.
 *
 * WHY: Centralizing all settings in one data class gives a single source of truth.
 * Any component that needs a setting reads from here. Any component that changes a
 * setting creates a new copy via [copy]. This makes settings changes explicit and
 * easy to test.
 *
 * HOW: Settings are persisted via [SettingsRepository]. Get the current settings
 * from [SettingsRepository.settingsFlow] and update them via [SettingsRepository.update].
 *
 * Example:
 *   // Read settings
 *   val settings = settingsRepository.settingsFlow.first()
 *   if (settings.airPlayEnabled) { ... }
 *
 *   // Change a setting
 *   settingsRepository.update { it.copy(displayName = "My TV") }
 */
data class AppSettings(

    // ─── Display ───────────────────────────────────────────────────────────
    /**
     * The name shown in sender pickers (AirPlay menu on Mac, Cast picker in a Cast-capable browser, etc.).
     * If empty, the Android device name is used as a fallback.
     * Validated: max 63 characters, must not be blank after trimming.
     */
    val displayName: String = "",

    // ─── Protocols ─────────────────────────────────────────────────────────
    /**
     * Whether the AirPlay 2 receiver is enabled.
     * When false: mDNS advertisement is stopped, RTSP port 7000 is not opened.
     */
    val airPlayEnabled: Boolean = true,

    /**
     * Whether the Miracast (Wi-Fi Display) receiver is enabled.
     * When false: Wi-Fi P2P service advertisement is stopped.
     */
    val miracastEnabled: Boolean = true,

    /**
     * Whether the Google Cast receiver is enabled.
     * On Fire TV (no Google Play Services), this is ignored.
     * When false: Cast SDK is not initialized.
     */
    val castEnabled: Boolean = true,

    /**
     * Whether the UPnP/DLNA MediaRenderer is enabled.
     * When false: SSDP advertisement and description HTTP server are stopped.
     */
    val dlnaEnabled: Boolean = true,

    // ─── AirPlay specific ──────────────────────────────────────────────────
    /**
     * Whether AirPlay connections require PIN authentication.
     * When true: the user must confirm a 4-digit PIN shown on screen.
     * When false (default): any nearby Mac can connect without confirmation.
     */
    val airPlayPinAuthEnabled: Boolean = false,

    // ─── Service behavior ──────────────────────────────────────────────────
    /**
     * Whether AirReceiverService starts automatically on device boot.
     * Requires the RECEIVE_BOOT_COMPLETED permission to be effective.
     */
    val startOnBoot: Boolean = false,

    // ─── Developer / Debug ─────────────────────────────────────────────────
    /**
     * Overlays a debug HUD on the streaming screen showing:
     * - Current frames per second
     * - Estimated A/V latency (ms)
     * - Active protocol name
     * Only useful for development and testing.
     */
    val showDebugOverlay: Boolean = false,

    // ─── Video ─────────────────────────────────────────────────────────────
    /**
     * When true, advertise a higher mirroring resolution (1440p) in the AirPlay `/info`
     * `displays` record so macOS renders/encodes the mirror at 2560×1440 instead of 1920×1080.
     * The TV surface is 1080p, so frames are downscaled (sharper text via supersampling) at
     * the cost of more decode work — heavier on low-end SoCs.
     */
    val forceHighResolution: Boolean = false,

    val lowBandwidthMirroring: Boolean = false,
    val airPlayAudioOnly: Boolean = false,
    val safeAreaPercent: Int = 0,

    /** Whether to accept companion audio for mirroring. */
    val mirrorAudioEnabled: Boolean = true,
    val startOnLaunch: Boolean = true,
    val airPlayPinPolicy: AirPlayPinPolicy? = null,
    val mirrorResolution: MirrorResolution? = null,
    val customMirrorWidth: Int = 1920,
    val customMirrorHeight: Int = 1080,
    val maxMirrorFps: Int = 0,
    val replaceCurrentSender: Boolean = false,
    val idleVideoPreview: Boolean = false,
    val autoFullscreen: Boolean = true,
    val autoAudioMode: Boolean = true
) {

    val effectivePinPolicy: AirPlayPinPolicy
        get() = airPlayPinPolicy ?: if (airPlayPinAuthEnabled) AirPlayPinPolicy.REMEMBER else AirPlayPinPolicy.OFF

    val effectiveMirrorResolution: MirrorResolution
        get() = mirrorResolution ?: if (forceHighResolution) MirrorResolution.QHD else MirrorResolution.FULL_HD

    val mirrorWidth: Int get() = when {
        lowBandwidthMirroring -> 1280
        forceHighResolution -> 2560
        else -> 1920
    }
    val mirrorHeight: Int get() = when {
        lowBandwidthMirroring -> 720
        forceHighResolution -> 1440
        else -> 1080
    }

    /**
     * Returns the validated, trimmed display name.
     * If the stored name is blank, returns an empty string so callers
     * can fall back to the system device name.
     */
    val effectiveDisplayName: String
        get() = displayName.trim()

    /**
     * Returns true if at least one protocol is enabled.
     * If all three are disabled, the service has nothing to do.
     */
    val anyProtocolEnabled: Boolean
        get() = airPlayEnabled || miracastEnabled || castEnabled || dlnaEnabled

    internal fun isEnabled(protocol: Protocol): Boolean = when (protocol) {
        Protocol.AIRPLAY -> airPlayEnabled
        Protocol.MIRACAST -> miracastEnabled
        Protocol.CAST -> castEnabled
        Protocol.DLNA -> dlnaEnabled
    }

    internal fun withReceiverEnabled(protocol: Protocol, enabled: Boolean): AppSettings = when (protocol) {
        Protocol.AIRPLAY -> copy(airPlayEnabled = enabled)
        Protocol.MIRACAST -> copy(miracastEnabled = enabled)
        Protocol.CAST -> copy(castEnabled = enabled)
        Protocol.DLNA -> copy(dlnaEnabled = enabled)
    }

    companion object {
        /** The default settings instance used on first launch. */
        val DEFAULT = AppSettings()

        /** Maximum allowed length for the display name (mDNS limit). */
        const val DISPLAY_NAME_MAX_LENGTH = 63
    }
}
