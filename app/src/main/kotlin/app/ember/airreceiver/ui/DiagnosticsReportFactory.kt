// Modified for Ember AirReceiver (2026).
// Licensed under the Apache License, Version 2.0 — see LICENSE and NOTICE.
package app.ember.airreceiver.ui

import app.ember.airreceiver.BuildConfig
import app.ember.airreceiver.diagnostics.DiagnosticProtocol
import app.ember.airreceiver.diagnostics.DiagnosticSession
import app.ember.airreceiver.diagnostics.DiagnosticsLabels
import app.ember.airreceiver.diagnostics.DiagnosticsPlatform
import app.ember.airreceiver.diagnostics.DiagnosticsPort
import app.ember.airreceiver.diagnostics.DiagnosticsPorts
import app.ember.airreceiver.diagnostics.DiagnosticsReport
import app.ember.airreceiver.diagnostics.ResourceSample
import app.ember.airreceiver.diagnostics.SelfTestResult
import app.ember.airreceiver.dlna.ssdp.SsdpMessages

/**
 * DiagnosticsReportFactory — builds the values of the diagnostics report.
 *
 * WHY this class exists: the report carries three kinds of facts, and they come from
 * three different places — the settings of the app (the name), the network stack (the
 * address), the running service (the states of the protocols). Assembling them is a
 * job of its own, and it is a job that does not belong to a screen: the factory can
 * be read, checked and changed without looking at the UI.
 *
 * WHY the version and the platform are read from the build: the version name carries
 * the flavour suffix, so "1.0.0-alpha.7-googletv" alone does not say which build of
 * the release the owner is running — the version code does. And the flavour, not the
 * device, decides the platform: a device that reports what it thinks it is would name
 * a platform the build does not have.
 *
 * Example:
 *   val report = DiagnosticsReportFactory.report(displayName, localAddress, …)
 */
internal object DiagnosticsReportFactory {

    /** The name of the product flavour that builds for Amazon Fire TV. */
    private const val FIRE_TV_FLAVOR = "firetv"

    /**
     * The version line of the report: the version name and the version code.
     */
    fun version(): String = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    /** The platform line of the report. */
    fun platform(): String = when (BuildConfig.FLAVOR) {
        FIRE_TV_FLAVOR -> DiagnosticsPlatform.FIRE_TV
        else -> DiagnosticsPlatform.GOOGLE_TV
    }

    /**
     * The ports the receiver listens on.
     *
     * @param labels the localized labels of the report.
     * @param descriptionPort the port of the DLNA description server, or null when it
     *   is not known yet — an ephemeral port of a server that is not running cannot be
     *   read from anywhere.
     */
    fun ports(labels: DiagnosticsLabels, descriptionPort: Int?): List<DiagnosticsPort> = listOf(
        DiagnosticsPort(labels.portAirPlay, DiagnosticsPorts.AIRPLAY),
        DiagnosticsPort(labels.portDlnaDescription, descriptionPort ?: 0),
        DiagnosticsPort(labels.portSsdp, SsdpMessages.PORT)
    )

    /**
     * Builds the report from the values the screen knows.
     *
     * @param displayName the name the television shows in the sender pickers.
     * @param localAddress the IPv4 address the receiver advertises.
     * @param serviceState the state of the receiver service, as text.
     * @param protocolStates the state of every protocol, as text.
     * @param ports the ports the receiver listens on.
     * @param selfTestResults the results of the last self test run.
     * @param activeSession the session that is streaming right now, or null.
     * @param resourceSample the last measurement of the resources, or null.
     */
    fun report(
        displayName: String,
        localAddress: String,
        serviceState: String,
        protocolStates: Map<DiagnosticProtocol, String>,
        ports: List<DiagnosticsPort>,
        selfTestResults: List<SelfTestResult>,
        activeSession: DiagnosticSession?,
        resourceSample: ResourceSample?,
        lastAirPlaySession: String? = null
    ): DiagnosticsReport = DiagnosticsReport(
        deviceName = displayName,
        localAddress = localAddress,
        appVersion = version(),
        platform = platform(),
        serviceState = serviceState,
        protocolStates = protocolStates,
        ports = ports,
        selfTestResults = selfTestResults,
        activeSession = activeSession,
        resourceSample = resourceSample,
        lastAirPlaySession = lastAirPlaySession
    )
}
