package com.idevicerestore.android

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.widget.TextView
import com.google.android.material.button.MaterialButton

/** Opens a snapshot of the current in-memory activity and USB probe logs. */
class LogIconButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle
) : MaterialButton(context, attrs, defStyleAttr) {
    init {
        setOnClickListener {
            val root = rootView
            SessionLogSnapshotStore.update(
                activityLog = root.findViewById<TextView>(R.id.logView)?.text,
                probeLog = root.findViewById<TextView>(R.id.probeLogView)?.text
            )
            context.startActivity(Intent(context, LogsActivity::class.java))
        }
    }
}
