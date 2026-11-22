// Modified for Ember AirReceiver (2026).
// Licensed under the Apache License, Version 2.0 — see LICENSE and NOTICE.
package app.ember.airreceiver.airplay.rtsp

/**
 * OCTET_STREAM — the Content-Type AirPlay uses for its raw binary handshake payloads.
 *
 * WHAT: The shared constant for "application/octet-stream", used by the pair-setup/pair-verify
 * key exchange and by the FairPlay fp-setup handshake.
 *
 * WHY: The pairing handler and the mirroring handler both answer with raw binary bodies. Keeping
 * the Content-Type in one shared constant stops the two classes from drifting apart on the wire
 * format a sender expects.
 *
 * Example:
 *   RtspResponse(200, "OK", bodyBytes = publicKey, contentType = OCTET_STREAM)
 */
internal const val OCTET_STREAM = "application/octet-stream"
