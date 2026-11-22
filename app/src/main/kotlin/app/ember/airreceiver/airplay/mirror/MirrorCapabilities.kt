package app.ember.airreceiver.airplay.mirror

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

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

    fun supportsAvc(width: Int, height: Int, fps: Int): Boolean =
        supports(MediaFormat.MIMETYPE_VIDEO_AVC, width, height, fps, false)
}
