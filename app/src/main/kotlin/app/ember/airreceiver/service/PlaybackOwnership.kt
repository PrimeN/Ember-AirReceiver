package app.ember.airreceiver.service

/** One active playback owner across the service and the separately launched player. */
internal object PlaybackOwnership {
    @Volatile var enabled = false
    private var owner: Any? = null

    @Synchronized
    fun acquire(candidate: Any): Boolean {
        if (owner == null) owner = candidate
        return owner === candidate || !enabled
    }

    @Synchronized
    fun release(candidate: Any) {
        if (owner === candidate) owner = null
    }
}
