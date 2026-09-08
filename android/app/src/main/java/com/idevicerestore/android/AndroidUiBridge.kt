package com.idevicerestore.android

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Shared UI plumbing for custom views that need to reach their hosting activity.
 *
 * Several restore/test controls are intentionally custom Views so they can keep their own bounded
 * state machines. This helper keeps Context unwrapping, main-thread logging, and operation-status
 * updates in one place instead of duplicating reflection and view lookup code in each control.
 */
object AndroidUiBridge {
    tailrec fun activity(context: Context?): AppCompatActivity? = when (context) {
        is AppCompatActivity -> context
        is ContextWrapper -> activity(context.baseContext)
        else -> null
    }

    fun log(
        activity: AppCompatActivity,
        message: String,
        fallbackViewId: Int = R.id.logView,
        appendFallback: Boolean = true
    ) = activity.runOnUiThread {
        val delivered = runCatching {
            val method = activity.javaClass.getDeclaredMethod("log", String::class.java)
            method.isAccessible = true
            method.invoke(activity, message)
            true
        }.getOrDefault(false)

        if (!delivered) {
            activity.findViewById<TextView?>(fallbackViewId)?.let { view ->
                if (appendFallback) {
                    view.append(message.trimEnd() + "\n")
                } else {
                    view.text = message
                }
            }
        }
    }

    fun setOperation(activity: AppCompatActivity, message: String, busy: Boolean) =
        activity.runOnUiThread {
            activity.findViewById<TextView?>(R.id.operationStatus)?.text = message
            activity.findViewById<ProgressBar?>(R.id.operationProgress)?.apply {
                visibility = if (busy) View.VISIBLE else View.GONE
                if (busy) isIndeterminate = true
            }
        }
}
