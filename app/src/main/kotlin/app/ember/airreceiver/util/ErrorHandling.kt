// Original file for Ember AirReceiver.
// Licensed under the Apache License, Version 2.0 — see LICENSE and NOTICE.
package app.ember.airreceiver.util

import kotlin.coroutines.cancellation.CancellationException

/*
 * ErrorHandling — two small helpers that make the receivers' broad `catch` blocks safe.
 *
 * Added for Ember AirReceiver.
 */

/**
 * Rethrows this throwable when it is a coroutine [CancellationException].
 *
 * WHY: a `catch (e: Exception)` inside a coroutine also catches the
 * [CancellationException] that `scope.cancel()` delivers. Swallowing it keeps the
 * coroutine running after its receiver was stopped, and the usual error path then
 * reports a failure that never happened — a receiver flips to `ERROR` right after the
 * user switched it off. Calling this first in every broad catch inside a coroutine keeps
 * cancellation cooperative.
 *
 * Usage:
 * ```
 * scope.launch {
 *     try { mdns.restart(name) }
 *     catch (e: Exception) { e.rethrowIfCancellation(); Logger.e("re-advertise failed", e) }
 * }
 * ```
 */
fun Throwable.rethrowIfCancellation() {
    if (this is CancellationException) throw this
}

/**
 * Runs one release step of a shutdown sequence and logs its failure instead of
 * propagating it.
 *
 * WHY: a `stop()` that closes several resources inside a single `try` stops at the first
 * failure and leaks every resource after it — typically a listening socket that stays
 * bound, so the next start fails with `EADDRINUSE` while the receiver still advertises.
 * Wrapping each step separately guarantees that every resource gets its own chance to be
 * released, whatever happened to the one before it.
 *
 * Usage:
 * ```
 * releaseQuietly("RTSP client") { activeClient?.close() }
 * releaseQuietly("RTSP server socket") { serverSocket?.close() }
 * ```
 *
 * @param component a short name of what is released, used in the log line.
 * @param step the release action.
 */
inline fun releaseQuietly(component: String, step: () -> Unit) {
    try {
        step()
    } catch (e: Exception) {
        Logger.e("$component release failed (non-fatal)", e)
    }
}
