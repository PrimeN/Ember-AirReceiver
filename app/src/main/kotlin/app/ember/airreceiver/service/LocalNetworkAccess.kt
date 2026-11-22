package app.ember.airreceiver.service

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Keep receiver startup compatible with the local-network runtime grant on newer targets. */
internal object LocalNetworkAccess {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    private const val ENFORCED_TARGET = 37

    fun hasAccess(context: Context): Boolean =
        Build.VERSION.SDK_INT < ENFORCED_TARGET ||
            context.applicationInfo.targetSdkVersion < ENFORCED_TARGET ||
            context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
}
