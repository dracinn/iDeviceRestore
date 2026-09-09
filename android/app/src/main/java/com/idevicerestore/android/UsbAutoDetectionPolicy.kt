package com.idevicerestore.android

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/** Controls the USB-attach activity alias without affecting the normal launcher activity. */
object UsbAutoDetectionPolicy {
    private const val TAG = "UsbAutoDetectionPolicy"

    fun apply(context: Context, enabled: Boolean): Boolean {
        val component = ComponentName(context.packageName, "${context.packageName}.UsbAttachActivityAlias")
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        return runCatching {
            context.packageManager.setComponentEnabledSetting(
                component,
                state,
                PackageManager.DONT_KILL_APP
            )
            true
        }.onFailure { error ->
            Log.e(TAG, "Unable to update USB attach alias ${component.flattenToShortString()}", error)
        }.getOrDefault(false)
    }
}
