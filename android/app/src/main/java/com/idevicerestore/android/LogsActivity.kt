package com.idevicerestore.android

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.idevicerestore.android.databinding.ActivityLogsBinding

class LogsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLogsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val snapshot = SessionLogSnapshotStore.snapshot()
        binding.activityLogView.text = snapshot.activityLog
            .ifEmpty { "No activity log entries yet." }
        binding.probeLogView.text = snapshot.probeLog
            .ifEmpty { "No USB probe log entries yet." }
    }
}
