package com.dimowner.audiorecorder.v2.audio

import android.content.Context
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope

// LOCAL MODIFICATION (Thinking Space): upstream is annotated @Singleton / @Inject and takes an
// @ApplicationContext (Hilt). Those annotations were removed so the class can be constructed
// directly; the body is otherwise identical to upstream
// (Dimowner/AudioRecorder @ 28ef142, app/.../v2/audio/AudioRecorderV2.kt).
class AudioRecorderV2(
    applicationContext: Context,
    coroutineScope: CoroutineScope,
) : MediaRecorderBase(applicationContext, coroutineScope) {

    override val recordingLogTag: String = "AAC "

    override fun configureRecorder(
        recorder: MediaRecorder,
        channelCount: Int,
        sampleRate: Int,
        bitrate: Int,
    ) {
        recorder.apply {
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioChannels(channelCount)
            setAudioSamplingRate(sampleRate)
            setAudioEncodingBitRate(bitrate)
        }
    }
}
