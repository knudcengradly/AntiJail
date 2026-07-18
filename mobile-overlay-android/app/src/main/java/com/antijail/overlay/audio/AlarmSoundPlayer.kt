package com.antijail.overlay.audio

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import com.antijail.overlay.R

class AlarmSoundPlayer(private val context: Context) {
    private companion object {
        private const val TAG = "AlarmSoundPlayer"
    }

    private var player: MediaPlayer? = null

    fun playDrillAlarm() {
        runCatching {
            player?.release()
            player = MediaPlayer.create(context, R.raw.soundalarm)?.apply {
                setOnCompletionListener {
                    release()
                    player = null
                }
                start()
            }
        }.onFailure { e ->
            Log.e(TAG, "playDrillAlarm failed", e)
        }
    }

    fun release() {
        runCatching {
            player?.release()
        }
        player = null
    }
}
