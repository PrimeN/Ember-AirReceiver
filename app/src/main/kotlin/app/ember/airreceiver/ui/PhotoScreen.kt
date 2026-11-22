// Modified for Ember AirReceiver (2026).
// Licensed under the Apache License, Version 2.0 — see LICENSE and NOTICE.
package app.ember.airreceiver.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import app.ember.airreceiver.util.Logger
import app.ember.airreceiver.util.SafeBitmapDecoder

/**
 * PhotoScreen — Full-screen ImageView for AirPlay `/photo` sharing.
 */
class PhotoScreen @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val imageView = ImageView(context).apply {
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    init {
        setBackgroundColor(android.graphics.Color.BLACK)
        addView(
            imageView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.CENTER
            }
        )
    }

    fun showPhoto(bytes: ByteArray): Boolean {
        // The payload comes from the network and may announce a picture that is huge
        // when decoded (audit finding SEC-02). SafeBitmapDecoder reads the header
        // first and refuses anything outside the decode budget, so a decompression
        // bomb cannot take the receiver down.
        val bitmap = SafeBitmapDecoder.decode(bytes)
        if (bitmap == null) {
            Logger.w("PhotoScreen: photo payload rejected — not a picture, or beyond the decode limits")
            return false
        }

        imageView.setImageBitmap(bitmap)
        return true
    }

    fun clearPhoto() {
        imageView.setImageDrawable(null)
    }
}
