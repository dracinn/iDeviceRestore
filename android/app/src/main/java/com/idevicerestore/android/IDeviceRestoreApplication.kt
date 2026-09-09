package com.idevicerestore.android

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import java.lang.ref.WeakReference

class IDeviceRestoreApplication : Application(), Application.ActivityLifecycleCallbacks {
    private var resumedActivity = WeakReference<Activity>(null)
    private var pendingUpdate: AppUpdateChecker.Update? = null
    private var updatePromptShown = false

    override fun onCreate() {
        super.onCreate()
        val settings = AppSettings(this)
        val mode = settings.appearanceMode.appCompatNightMode
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }

        UsbAutoDetectionPolicy.apply(this, settings.automaticDeviceDetection)
        registerActivityLifecycleCallbacks(this)
        if (settings.checkForAppUpdatesAtLaunch) {
            AppUpdateChecker().checkAsync(BuildConfig.VERSION_NAME) { update ->
                if (update != null) {
                    pendingUpdate = update
                    resumedActivity.get()?.runOnUiThread { maybeShowPendingUpdate(resumedActivity.get()) }
                }
            }
        }
    }

    private fun maybeShowPendingUpdate(activity: Activity?) {
        if (activity == null || activity.isFinishing || activity.isDestroyed || updatePromptShown) return
        if (activity !is MainActivity) return
        val update = pendingUpdate ?: return
        updatePromptShown = true
        AlertDialog.Builder(activity)
            .setTitle("iDeviceRestore update available")
            .setMessage("${update.tagName} is available. This build is ${update.currentVersion}.")
            .setPositiveButton("View release") { _, _ ->
                runCatching {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.releaseUrl)))
                }
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    override fun onActivityResumed(activity: Activity) {
        resumedActivity = WeakReference(activity)
        maybeShowPendingUpdate(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumedActivity.get() === activity) resumedActivity.clear()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
