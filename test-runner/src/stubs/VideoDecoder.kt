// Modified for Ember AirReceiver (2026).
// Licensed under the Apache License, Version 2.0 — see LICENSE and NOTICE.
package app.ember.airreceiver.airplay.mirror

/**
 * No-op codec API for protocol tests; hardware submission tests run through the app test task.
 * The SPS parser is shared production code so its JVM tests cannot drift from Android behavior.
 * MirrorStreamServer can call this stub without loading MediaCodec.
 */
@Suppress("UNUSED_PARAMETER")
class VideoDecoder(outputSurface: Any?) {

    // Matches the real VideoDecoder API used by MirrorStreamServer (self-heal flag).
    var isHealthy = true
    internal var useMonotonicPresentationTime = false
    internal var codecToAvoid: String? = null

    fun initialize(sps: ByteArray, pps: ByteArray, width: Int, height: Int) {}
    fun decodeNalUnit(nalUnit: ByteArray, presentationTimeUs: Long = 0L): Boolean = true
    fun drainPendingOutput() {}
    fun release() {}

}
