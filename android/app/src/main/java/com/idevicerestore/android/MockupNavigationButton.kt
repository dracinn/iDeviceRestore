package com.idevicerestore.android

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.view.View
import android.widget.ScrollView
import com.google.android.material.button.MaterialButton

/**
 * Lightweight navigation used by the mockup-inspired home shell.
 *
 * Keeping this behavior in the View avoids adding more presentation-only routing to MainActivity.
 * The button action is selected by android:tag in XML.
 */
class MockupNavigationButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle
) : MaterialButton(context, attrs, defStyleAttr) {

    init {
        setOnClickListener { route() }
    }

    private fun route() {
        when (tag?.toString()) {
            ACTION_HOME -> scrollToTop()
            ACTION_DEVICES -> scrollTo(R.id.deviceSection)
            ACTION_FIRMWARE, ACTION_UPDATE -> scrollTo(R.id.firmwareSection)
            ACTION_TOOLS -> scrollTo(R.id.toolsSection)
            ACTION_RESTORE -> scrollTo(R.id.operationSection)
            ACTION_DIAGNOSTICS -> AndroidUiBridge.activity(context)?.startActivity(
                Intent(context, BootDiagnosticsActivity::class.java)
            )
        }
    }

    private fun scrollToTop() {
        rootView.findViewById<ScrollView?>(R.id.mainScrollView)?.smoothScrollTo(0, 0)
    }

    private fun scrollTo(targetId: Int) {
        val scroll = rootView.findViewById<ScrollView?>(R.id.mainScrollView) ?: return
        val target = rootView.findViewById<View?>(targetId) ?: return
        scroll.post { scroll.smoothScrollTo(0, target.top.coerceAtLeast(0)) }
    }

    companion object {
        private const val ACTION_HOME = "home"
        private const val ACTION_DEVICES = "devices"
        private const val ACTION_FIRMWARE = "firmware"
        private const val ACTION_UPDATE = "update"
        private const val ACTION_TOOLS = "tools"
        private const val ACTION_RESTORE = "restore"
        private const val ACTION_DIAGNOSTICS = "diagnostics"
    }
}
