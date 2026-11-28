package app.ember.airreceiver.airplay.mirror

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import app.ember.airreceiver.settings.AppSettings
import app.ember.airreceiver.settings.MirrorFormat
import app.ember.airreceiver.settings.MirrorResolution

internal object MirrorCapabilities {
    private fun supports(mime: String, width: Int, height: Int, fps: Int, hardwareOnly: Boolean): Boolean =
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
                if (codec.isEncoder || (hardwareOnly && Build.VERSION.SDK_INT >= 29 &&
                        (!codec.isHardwareAccelerated || codec.isAlias))) return@any false
                runCatching {
                    val caps = codec.getCapabilitiesForType(mime)
                    val mainProfile = !hardwareOnly || caps.profileLevels.any {
                        it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
                    }
                    mainProfile && caps.videoCapabilities.areSizeAndRateSupported(width, height, fps.toDouble())
                }.getOrDefault(false)
            }
        }.getOrDefault(false)

    fun supportsHevc(width: Int, height: Int, fps: Int): Boolean =
        Build.VERSION.SDK_INT >= 29 && supports(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height, fps, true)

    fun resolve(context: Context, settings: AppSettings): MirrorFormat {
        val display = displayMode(context)
        val resolution = settings.effectiveMirrorResolution
        val (width, height) = when {
            settings.lowBandwidthMirroring -> 1280 to 720
            resolution == MirrorResolution.AUTO -> {
                val physicalWidth = display?.physicalWidth ?: 1920
                val physicalHeight = display?.physicalHeight ?: 1080
                MirrorResolution.entries.last { it.width <= minOf(physicalWidth, 3840) &&
                    it.height <= minOf(physicalHeight, 2160) && it != MirrorResolution.CUSTOM }
                    .takeIf { it != MirrorResolution.AUTO }?.let { it.width to it.height } ?: (1280 to 720)
            }
            resolution == MirrorResolution.CUSTOM -> settings.customMirrorWidth to settings.customMirrorHeight
            else -> resolution.width to resolution.height
        }
        val requestedFps = settings.maxMirrorFps.takeIf { it > 0 } ?: 60
        val ceiling = minOf(
            if (settings.lowBandwidthMirroring) minOf(requestedFps, 30) else requestedFps,
            ((display?.refreshRate ?: 60f) + 0.5f).toInt().coerceIn(1, 120)
        )
        val rates = listOf(ceiling, 60, 50, 30, 25, 24, 15).filter { it <= ceiling }.distinct()
        val sizes = listOf(width to height, 2560 to 1440, 1920 to 1080, 1280 to 720, 640 to 360)
            .filter { (w, h) -> w <= width && h <= height }.distinct()
        for ((w, h) in sizes) {
            val fps = rates.firstOrNull { supports(MediaFormat.MIMETYPE_VIDEO_AVC, w, h, it, false) }
            if (fps != null) return MirrorFormat(w, h, fps, supportsHevc(w, h, fps))
        }
        // Some devices omit reliable MediaCodec capability metadata. Keep a conservative fallback.
        return MirrorFormat(minOf(width, 1280), minOf(height, 720), minOf(ceiling, 30))
    }

    private fun displayMode(context: Context): Display.Mode? = runCatching {
        context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.mode
    }.getOrNull()

    fun frameRate(context: Context, width: Int, height: Int): Int {
        val refreshRate = runCatching {
            context.getSystemService(DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY)?.mode?.refreshRate
        }.getOrNull() ?: 60f
        return if (refreshRate >= 59f && supports(MediaFormat.MIMETYPE_VIDEO_AVC, width, height, 60, false)) 60 else 30
    }
}
