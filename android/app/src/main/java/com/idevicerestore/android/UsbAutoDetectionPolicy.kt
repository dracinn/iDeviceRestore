package com.idevicerestore.android

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Controls the USB-attach activity alias without affecting the normal launcher activity. */
object UsbAutoDetectionPolicy {
    fun apply(context: Context, enabled: Boolean) {
        val component = ComponentName(context.packageName, "${context.packageName}.UsbAttachActivityAlias")
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        context.packageManager.setComponentEnabledSetting(
            component,
            state,
            PackageManager.DONT_KILL_APP
        )
    }
}
