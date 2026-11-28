package app.ember.airreceiver.airplay.audio

/** Shared gain mapping for AirPlay playback, including its special mute value. */
internal fun airPlayGain(volume: Float): Float =
    if (!volume.isFinite() || volume <= -144f) 0f else ((volume + 30f) / 30f).coerceIn(0f, 1f)
