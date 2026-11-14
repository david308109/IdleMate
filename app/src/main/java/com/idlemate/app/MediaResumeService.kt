package com.idlemate.app

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.content.ComponentName
import android.media.AudioPlaybackConfiguration
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent

/**
 * 功能二：待機聽劇（不需要任何特殊授權）。
 * 用系統的音訊播放狀態判斷「息屏前是否正在播放」，息屏後若聲音被暫停，
 * 就送出系統媒體鍵「播放」，由目前的媒體 App 接手。
 */
class MediaResumeService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var am: AudioManager
    private var wakeLock: PowerManager.WakeLock? = null

    private var screenOn = true
    private var wasPlaying = false
    private var stoppedAt = 0L

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            val playing = am.isMusicActive
            if (wasPlaying && !playing) stoppedAt = SystemClock.elapsedRealtime()
            wasPlaying = playing
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> { screenOn = false; resumePlayback() }
                Intent.ACTION_SCREEN_ON -> { screenOn = true; handler.removeCallbacksAndMessages(null) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            2, Notif.build(this, "待機聽劇", "螢幕關閉後自動繼續播放"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        if (wakeLock == null) {
            am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IdleMate:media")
                .apply { setReferenceCounted(false); acquire() }
            wasPlaying = am.isMusicActive
            am.registerAudioPlaybackCallback(playbackCallback, handler)
            registerReceiver(screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            })
        }
        return START_STICKY
    }

    private fun resumePlayback() {
        val recentlyPlaying = wasPlaying || SystemClock.elapsedRealtime() - stoppedAt < 3000
        if (!recentlyPlaying) return
        // 影音 App 暫停的時機不定，分多次補送播放鍵
        for (delay in longArrayOf(300, 800, 1500, 3000, 5000, 8000)) {
            handler.postDelayed({
                if (!screenOn && !am.isMusicActive) resumeNow()
            }, delay)
        }
    }

    /** 有通知存取權就直接對播放器下「播放」；否則退回系統媒體鍵。 */
    private fun resumeNow() {
        val direct = runCatching {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val sessions = msm.getActiveSessions(ComponentName(this, MediaListener::class.java))
            val target = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED }
                ?: sessions.firstOrNull()
            target?.transportControls?.play() ?: error("no session")
        }.isSuccess
        if (!direct) sendPlayKey()
    }

    private fun sendPlayKey() {
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { am.unregisterAudioPlaybackCallback(playbackCallback) }
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
