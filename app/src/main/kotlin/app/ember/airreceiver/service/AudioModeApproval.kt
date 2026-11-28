package app.ember.airreceiver.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One request at a time. The transport stays silent while waiting for a decision. */
internal class AudioModeApproval {
    class Request(val sender: String) {
        private val decided = CountDownLatch(1)
        @Volatile private var accepted = false

        @Synchronized
        fun decide(allow: Boolean) {
            if (decided.count == 0L) return
            accepted = allow
            decided.countDown()
        }

        fun await(): Boolean = decided.await(20, TimeUnit.SECONDS) && accepted
    }

    private val pending = MutableStateFlow<Request?>(null)
    val request = pending.asStateFlow()

    fun ask(sender: String): Boolean {
        val request = Request(sender)
        if (!pending.compareAndSet(null, request)) return false
        return try {
            request.await()
        } finally {
            pending.compareAndSet(request, null)
        }
    }

    fun cancel() {
        pending.value?.decide(false)
    }
}
