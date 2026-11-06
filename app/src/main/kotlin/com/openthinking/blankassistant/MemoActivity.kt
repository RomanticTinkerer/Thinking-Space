@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.openthinking.blankassistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.openthinking.blankassistant.audio.RecordingService
import com.openthinking.blankassistant.audio.VoiceNoteRecorder
import com.openthinking.blankassistant.storage.CaptureStore
import com.openthinking.blankassistant.ui.OtsTheme
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Records a voice memo with no screenshot: opened from the launcher shortcut or the Quick Settings tile.
 * Starts at once, shows the time and a live level meter, and saves a memo card with the recording on Save.
 */
class MemoActivity : ComponentActivity(), VoiceNoteRecorder.Callbacks {

    private var recorder: VoiceNoteRecorder? = null
    private var duration by mutableLongStateOf(0L)
    private val levels = mutableStateListOf<Float>()
    private var saving by mutableStateOf(false)
    private var cancelled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OtsTheme(forceDark = true) {
                MemoScreen(
                    duration = duration,
                    levels = levels,
                    saving = saving,
                    onSave = { recorder?.stop() },
                    onCancel = ::cancel,
                )
            }
        }
        val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) begin() else {
                Toast.makeText(this, R.string.msg_need_mic, Toast.LENGTH_LONG).show()
                finish()
            }
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) begin()
        else askMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun begin() {
        val r = VoiceNoteRecorder(this, this)
        recorder = r
        // The mic needs the foreground service up first; it calls back when ready.
        RecordingService.start(this) { if (recorder === r) r.start() else RecordingService.stop(this) }
    }

    private fun cancel() {
        cancelled = true
        recorder?.discard()
        finish()
    }

    override fun onProgress(durationMs: Long, amplitude: Int) {
        duration = durationMs
        levels.add(sqrt((amplitude / MAX_AMPLITUDE).coerceIn(0f, 1f))) // sqrt: speech sits low on a linear scale
        while (levels.size > BARS) levels.removeAt(0)
    }

    override fun onFinished(file: File, durationMs: Long) {
        RecordingService.stop(this)
        if (cancelled) {
            file.delete()
            return
        }
        saving = true
        thread {
            val saved = CaptureStore.saveMemo(this, file, durationMs)
            file.delete()
            runOnUiThread {
                Toast.makeText(this, if (saved != null) R.string.msg_memo_saved else R.string.msg_save_failed, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onFailed() {
        Toast.makeText(this, R.string.msg_record_failed, Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onDestroy() {
        if (!saving) recorder?.discard()
        RecordingService.stop(this)
        super.onDestroy()
    }

    private companion object {
        const val BARS = 36
        const val MAX_AMPLITUDE = 32767f
    }
}

@Composable
private fun MemoScreen(
    duration: Long,
    levels: List<Float>,
    saving: Boolean,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Voice memo", style = MaterialTheme.typography.labelLarge, color = scheme.primary)
        Spacer(Modifier.height(12.dp))
        Text(
            String.format(Locale.US, "%d:%02d", duration / 60_000, duration / 1000 % 60),
            style = MaterialTheme.typography.displayLargeEmphasized,
            color = scheme.onSurface,
        )
        Spacer(Modifier.height(32.dp))
        Canvas(Modifier.fillMaxWidth().height(96.dp)) {
            val slot = size.width / 36f
            val bar = slot * 0.5f
            levels.forEachIndexed { i, level ->
                val h = max(level * size.height, bar)
                drawRoundRect(
                    color = scheme.primary,
                    topLeft = Offset(size.width - slot * (levels.size - i) + (slot - bar) / 2f, (size.height - h) / 2f),
                    size = Size(bar, h),
                    cornerRadius = CornerRadius(bar / 2f),
                )
            }
        }
        Spacer(Modifier.height(48.dp))
        if (saving) {
            LoadingIndicator(Modifier.size(48.dp))
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                Button(onClick = onSave) {
                    Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Save memo")
                }
            }
        }
    }
}
