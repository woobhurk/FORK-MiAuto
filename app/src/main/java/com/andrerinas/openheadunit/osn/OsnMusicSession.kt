package com.andrerinas.openheadunit.osn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import androidx.core.content.IntentCompat
import androidx.media.session.MediaButtonReceiver
import com.andrerinas.openheadunit.utils.AppLog
import com.ucar.vehiclesdk.UCarAdapter
import com.ucar.vehiclesdk.UCarCommon
import java.util.Arrays

/**
 * Makes the phone's now-playing the head unit's active media source, and turns steering
 * keys into ICCOA key events. Geely's cluster, desktop widget and HUD read that session;
 * installed Xiaoba packages also receive the same snapshot their CarLink build broadcasts.
 */
internal object OsnMusicSession {
    private const val MUSIC_ACTION = "com.xiaoba.carlink.MUSIC_INFO"
    private const val UNKNOWN_DURATION_MS = 600_000L
    private const val DEBOUNCE_MS = 400L

    private val actions =
        PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS

    private var session: MediaSessionCompat? = null
    private var lastCover: ByteArray? = null
    private var lastArt: Bitmap? = null
    private var lastKey = 0
    private var lastKeyAt = 0L

    fun start(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            if (session != null) return
            val created = MediaSessionCompat(
                app,
                "MiAutoCarLink",
                ComponentName(app, MediaButtonReceiver::class.java),
                null,
            )
            created.setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
            created.setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = onMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                override fun onPause() = onMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
                override fun onSkipToNext() = onMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                override fun onSkipToPrevious() = onMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                    val event = mediaButtonEvent?.let {
                        IntentCompat.getParcelableExtra(it, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    }
                    if (event != null && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        onMediaKey(event.keyCode)
                        return true
                    }
                    return event != null || super.onMediaButtonEvent(mediaButtonEvent)
                }
            })
            created.setPlaybackToLocal(android.media.AudioManager.STREAM_MUSIC)
            session = created
            AppLog.i("OSN: media session ready")
        }
    }

    fun update(context: Context, info: UCarCommon.MusicInfo) {
        start(context)
        val playing = info.isPlaying && !info.isDestroyed
        val duration = if (info.totalTimesMs > 0) info.totalTimesMs else UNKNOWN_DURATION_MS
        val position = info.playingCurrentTimeMs.coerceAtLeast(0).toLong()
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, info.title.orEmpty())
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, info.artistName.orEmpty())
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, info.albumName.orEmpty())
            .putString(MediaMetadataCompat.METADATA_KEY_AUTHOR, info.authorName.orEmpty())
            .putString(MediaMetadataCompat.METADATA_KEY_WRITER, info.writerName.orEmpty())
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
        coverBitmap(info.cover)?.let { metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
        val state = if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        synchronized(this) {
            val active = session ?: return
            active.setMetadata(metadata.build())
            active.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(actions)
                    .setState(state, position, if (playing) 1f else 0f, SystemClock.elapsedRealtime())
                    .build(),
            )
            active.isActive = !info.isDestroyed
        }
        broadcast(context.applicationContext, info)
    }

    fun clear() {
        synchronized(this) {
            lastCover = null
            lastArt = null
            session?.let { active ->
                active.setPlaybackState(
                    PlaybackStateCompat.Builder()
                        .setActions(actions)
                        .setState(PlaybackStateCompat.STATE_STOPPED, 0, 0f, SystemClock.elapsedRealtime())
                        .build(),
                )
                active.isActive = false
            }
        }
    }

    fun onMediaKey(keyCode: Int) {
        val ucar = ucarKey(keyCode) ?: return
        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (keyCode == lastKey && now - lastKeyAt < DEBOUNCE_MS) return
            lastKey = keyCode
            lastKeyAt = now
        }
        try {
            val adapter = UCarAdapter.getInstance()
            adapter.sendKeyEvent(UCarCommon.KeyEventActionType.KEY_EVENT_ACTION_DOWN, ucar, 0)
            adapter.sendKeyEvent(UCarCommon.KeyEventActionType.KEY_EVENT_ACTION_UP, ucar, 0)
            AppLog.i("OSN: steering key $keyCode -> $ucar")
        } catch (error: RuntimeException) {
            AppLog.w("OSN: steering key failed: ${error.message}")
        }
    }

    private fun ucarKey(keyCode: Int): UCarCommon.KeyCodeType? = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_NEXT -> UCarCommon.KeyCodeType.KEY_CODE_MEDIA_NEXT
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> UCarCommon.KeyCodeType.KEY_CODE_MEDIA_PREVIOUS
        KeyEvent.KEYCODE_MEDIA_PLAY -> UCarCommon.KeyCodeType.KEY_CODE_MEDIA_PLAY
        KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> UCarCommon.KeyCodeType.KEY_CODE_MEDIA_PAUSE
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> UCarCommon.KeyCodeType.KEY_CODE_MEDIA
        else -> null
    }

    private fun coverBitmap(cover: ByteArray?): Bitmap? {
        if (cover == null || cover.isEmpty()) return lastArt
        synchronized(this) {
            if (Arrays.equals(cover, lastCover)) return lastArt
            lastCover = cover.copyOf()
        }
        val decoded = BitmapFactory.decodeByteArray(cover, 0, cover.size) ?: return lastArt
        val scaled = Bitmap.createScaledBitmap(decoded, 256, 256, true)
        lastArt = scaled
        return scaled
    }

    private fun broadcast(context: Context, info: UCarCommon.MusicInfo) {
        val intent = Intent(MUSIC_ACTION)
        intent.putExtra("title", info.title.orEmpty())
        intent.putExtra("artist", info.artistName.orEmpty())
        intent.putExtra("album", info.albumName.orEmpty())
        intent.putExtra("lyrics", info.lyrics.orEmpty())
        intent.putExtra("positionMs", info.playingCurrentTimeMs)
        intent.putExtra("durationMs", info.totalTimesMs)
        intent.putExtra("playing", info.isPlaying)
        intent.putExtra("coverUrl", info.coverArt.orEmpty())
        val cover = info.cover
        if (cover != null && cover.isNotEmpty()) intent.putExtra("cover", cover)
        val targets = xiaobaPackages(context)
        if (targets.isEmpty()) return
        for (pkg in targets) {
            val copy = Intent(intent).setPackage(pkg)
            runCatching { context.sendBroadcast(copy) }
        }
    }

    private fun xiaobaPackages(context: Context): List<String> {
        val self = context.packageName
        val installed = runCatching {
            context.packageManager.getInstalledPackages(0)
                .mapNotNull { it: PackageInfo -> it.packageName }
                .filter { it.startsWith("com.xiaoba") && it != self }
        }.getOrDefault(emptyList())
        if (installed.isNotEmpty()) return installed
        return listOf("com.xiaoba.launcher", "com.xiaoba.smartcontroller").filter { it != self }
    }
}
