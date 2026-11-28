package app.ember.airreceiver.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Serializes handoffs between supported receivers and waits for the previous owner to release. */
internal class PlaybackOwnership {
    private class Lease(val token: Any, val disconnect: () -> Unit) {
        val released = CountDownLatch(1)
    }

    @Volatile var allowReplacement = false

    private val lock = Any()
    private var current: Lease? = null
    private var pending: Any? = null

    fun acquire(candidate: Any, canReplace: Boolean = allowReplacement, disconnect: () -> Unit): Boolean {
        val previous = synchronized(lock) {
            if (pending != null) return false
            val owner = current
            if (owner == null) {
                current = Lease(candidate, disconnect)
                return true
            }
            if (owner.token === candidate) return true
            if (!canReplace) return false
            pending = candidate
            owner
        }
        return try {
            previous.disconnect()
            val ready = previous.released.await(2, TimeUnit.SECONDS)
            synchronized(lock) {
                if (ready && pending === candidate && current == null) {
                    current = Lease(candidate, disconnect)
                    true
                } else false
            }
        } finally {
            synchronized(lock) { if (pending === candidate) pending = null }
        }
    }

    fun release(candidate: Any) {
        synchronized(lock) {
            if (current?.token === candidate) {
                val previous = current
                current = null
                previous?.released?.countDown()
            }
        }
    }

    fun remove(candidate: Any) {
        synchronized(lock) {
            if (pending === candidate) pending = null
            release(candidate)
        }
    }
    companion object {
        val shared = PlaybackOwnership()
    }
}
