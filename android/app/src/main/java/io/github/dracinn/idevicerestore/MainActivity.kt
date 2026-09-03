package io.github.dracinn.idevicerestore

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.dracinn.idevicerestore.download.FirmwareDownloadEvents
import io.github.dracinn.idevicerestore.download.FirmwareDownloadPhase
import io.github.dracinn.idevicerestore.download.FirmwareDownloadProgress

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            var importedIpsw by remember { mutableStateOf<Uri?>(null) }
            var transfer by remember { mutableStateOf<FirmwareDownloadProgress?>(null) }

            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    runCatching {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                    importedIpsw = uri
                }
            }

            DisposableEffect(Unit) {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        if (intent?.action != FirmwareDownloadEvents.ACTION_PROGRESS) return
                        val phaseName = intent.getStringExtra(FirmwareDownloadEvents.EXTRA_PHASE) ?: return
                        val phase = runCatching { FirmwareDownloadPhase.valueOf(phaseName) }.getOrNull() ?: return
                        val total = intent.getLongExtra(
                            FirmwareDownloadEvents.EXTRA_TOTAL_BYTES,
                            FirmwareDownloadEvents.UNKNOWN_TOTAL_BYTES,
                        ).takeIf { it >= 0L }
                        transfer = FirmwareDownloadProgress(
                            requestId = intent.getStringExtra(FirmwareDownloadEvents.EXTRA_REQUEST_ID) ?: return,
                            phase = phase,
                            bytesDownloaded = intent.getLongExtra(
                                FirmwareDownloadEvents.EXTRA_BYTES_DOWNLOADED,
                                0L,
                            ),
                            totalBytes = total,
                            message = intent.getStringExtra(FirmwareDownloadEvents.EXTRA_MESSAGE),
                            completedPath = intent.getStringExtra(FirmwareDownloadEvents.EXTRA_COMPLETED_PATH),
                            computedDigestHex = intent.getStringExtra(FirmwareDownloadEvents.EXTRA_DIGEST),
                        )
                    }
                }
                val filter = IntentFilter(FirmwareDownloadEvents.ACTION_PROGRESS)
                if (Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("DEPRECATION")
                    registerReceiver(receiver, filter)
                }
                onDispose { unregisterReceiver(receiver) }
            }

            MaterialTheme {
                Scaffold { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("iDeviceRestore", style = MaterialTheme.typography.headlineMedium)
                        Text("Android download framework · restore execution disabled")

                        Card {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("Firmware source", style = MaterialTheme.typography.titleMedium)
                                Text(importedIpsw?.toString() ?: "No local IPSW selected")
                                Button(onClick = {
                                    picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                                }) {
                                    Text("Choose local IPSW")
                                }
                            }
                        }

                        Card {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("Download framework", style = MaterialTheme.typography.titleMedium)
                                val current = transfer
                                if (current == null) {
                                    Text("Ready for firmware catalog integration")
                                } else {
                                    Text(current.phase.name.lowercase().replaceFirstChar { it.uppercase() })
                                    current.message?.let { Text(it) }
                                    current.totalBytes?.let { total ->
                                        if (total > 0L) {
                                            val percent = ((current.bytesDownloaded * 100L) / total)
                                                .coerceIn(0L, 100L)
                                            Text("$percent% · ${current.bytesDownloaded} / $total bytes")
                                        }
                                    }
                                    current.completedPath?.let { Text("Ready: $it") }
                                }
                            }
                        }

                        Text(
                            "Downloads are staged and verified before they become eligible for IPSW inspection. " +
                                "Revive, update, erase restore, reboot/reset, and firmware upload are not enabled on this branch."
                        )
                    }
                }
            }
        }
    }
}
