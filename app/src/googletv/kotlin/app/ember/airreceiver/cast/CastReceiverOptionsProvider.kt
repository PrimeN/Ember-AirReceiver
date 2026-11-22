// Modified for Ember AirReceiver (2026).
// Licensed under the Apache License, Version 2.0 — see LICENSE and NOTICE.
package app.ember.airreceiver.cast

import android.content.Context
import com.google.android.gms.cast.tv.CastReceiverOptions
import com.google.android.gms.cast.tv.ReceiverOptionsProvider
import app.ember.airreceiver.R

/**
 * CastReceiverOptionsProvider — hands the Google Cast TV framework the receiver options
 * of the Ember AirReceiver cast app.
 *
 * WHY: the Cast SDK looks the provider up through the manifest entry
 * `com.google.android.gms.cast.tv.RECEIVER_OPTIONS_PROVIDER_CLASS_NAME`. Without a
 * provider the framework has no options for the receiver and the cast app cannot be
 * started from a sender.
 *
 * Example:
 *   CastReceiverOptionsProvider().getOptions(context)
 */
class CastReceiverOptionsProvider : ReceiverOptionsProvider {
    override fun getOptions(context: Context): CastReceiverOptions {
        return CastReceiverOptions.Builder(context)
            .setStatusText(context.getString(R.string.app_name))
            .build()
    }
}
