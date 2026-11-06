package com.openthinking.blankassistant

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.IntentCompat
import com.openthinking.blankassistant.storage.CaptureStore
import kotlin.concurrent.thread

/**
 * "Share to Thinking Space": saves shared text, links, images and audio into the album and closes. It has no
 * screen of its own, only a toast, so sharing feels like dropping something into a box.
 */
class ShareReceiverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val share = intent
        thread {
            val saved = save(share)
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (saved > 0) getString(R.string.msg_shared_saved, saved) else getString(R.string.msg_shared_failed),
                    Toast.LENGTH_SHORT,
                ).show()
                finish()
            }
        }
    }

    private fun save(share: Intent): Int {
        val streams = when (share.action) {
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(share, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> listOfNotNull(IntentCompat.getParcelableExtra(share, Intent.EXTRA_STREAM, Uri::class.java))
        }
        if (streams.isNotEmpty()) {
            return streams.count {
                val isAudio = (contentResolver.getType(it) ?: share.type).orEmpty().startsWith("audio/")
                (if (isAudio) CaptureStore.saveAudio(this, it) else CaptureStore.saveImage(this, it)) != null
            }
        }

        val text = share.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        return if (CaptureStore.saveText(this, text) != null) 1 else 0
    }
}
