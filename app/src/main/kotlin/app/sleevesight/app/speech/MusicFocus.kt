package app.sleevesight.app.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import app.sleevesight.core.settings.ReadAloudMusic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The music playing, while an insight is read aloud (#325): Android's audio focus, which the
 * player follows, taken for the reading and given back after it. A seam, so [ReadAloud] is tested
 * without Android's audio. Called on the main thread.
 */
interface MusicFocus {
    /**
     * Takes the focus for a reading, as *Music while reading aloud* says ([how]): the music lowered or
     * paused. False when Android refuses it (a call is under way), and nothing should be spoken.
     * [lost] is called if another app takes the focus from the reading, other than for a moment's
     * sound that lowers it (a call coming in, another player starting): the reading should stop.
     */
    fun take(
        how: ReadAloudMusic,
        lost: () -> Unit,
    ): Boolean

    /** Gives the focus back, so the music comes back up, or plays on; nothing if none is held. */
    fun release()

    /**
     * Whether the music is paused for the readings though *Reduce volume* is chosen (#803): it plays
     * on another device whose volume the phone cannot set. False once the user plays it again or
     * moves it to the phone, and when nothing is held.
     */
    val pausedInstead: StateFlow<Boolean> get() = NEVER_PAUSED_INSTEAD
}

private val NEVER_PAUSED_INSTEAD: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

/**
 * [MusicFocus] for music played on the phone and on another device (#803): the audio focus
 * ([audio]) for every reading, as before, which the phone's own players follow and which keeps a
 * reading from starting during a call; and, where a followed player plays on another device
 * ([remote], Spotify Connect on a speaker), which the focus does not reach, the music held through
 * its session as well ([RemoteHold]): lowered through it where its volume can be set, else paused
 * and played again after. Taken once for readings that follow one another ([ReadAloud] keeps it
 * when a reading replaces another), so the session and the way it is held are fixed for as long as
 * it is held; released however the last reading ends: its end, *Stop*, a failure, the focus lost,
 * the app leaving the screen.
 */
class ReadAloudMusicFocus(
    private val audio: MusicFocus,
    private val remote: () -> RemoteMusic?,
) : MusicFocus {
    private var hold: RemoteHold? = null
    private val pausedInsteadState = MutableStateFlow(false)

    override val pausedInstead: StateFlow<Boolean> = pausedInsteadState.asStateFlow()

    override fun take(
        how: ReadAloudMusic,
        lost: () -> Unit,
    ): Boolean {
        endHold()
        if (!audio.take(how, lost)) return false
        hold =
            remote()?.let { music ->
                RemoteHold.start(music, lower = how == ReadAloudMusic.LOWER) { pausedInsteadState.value = it }
            }
        return true
    }

    override fun release() {
        endHold()
        audio.release()
    }

    private fun endHold() {
        hold?.end()
        hold = null
        pausedInsteadState.value = false
    }
}

/**
 * [MusicFocus] through `AudioManager`: *Reduce volume* asks for transient focus that lets the
 * player duck (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`), which Android does for it from Android 8;
 * *Pause playback* for transient focus alone (`AUDIOFOCUS_GAIN_TRANSIENT`), which a player meets
 * by pausing, and playing on once the focus comes back. Whether it does is the player's.
 */
class SystemMusicFocus(
    context: Context,
) : MusicFocus {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private var held: AudioFocusRequest? = null

    override fun take(
        how: ReadAloudMusic,
        lost: () -> Unit,
    ): Boolean {
        release()
        val gain =
            when (how) {
                ReadAloudMusic.LOWER -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                ReadAloudMusic.PAUSE -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            }
        // Called on the main thread. A moment's sound (a notification) only lowers the voice.
        val listener =
            AudioManager.OnAudioFocusChangeListener { change ->
                val gone = change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                if (gone && held != null) lost()
            }
        val request =
            AudioFocusRequest
                .Builder(gain)
                .setAudioAttributes(ATTRIBUTES)
                .setOnAudioFocusChangeListener(listener)
                .build()
        val granted = audio?.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) held = request
        return granted
    }

    override fun release() {
        val request = held ?: return
        held = null
        audio?.abandonAudioFocusRequest(request)
    }

    private companion object {
        // As the voice is spoken ([SystemSpeech]).
        val ATTRIBUTES: AudioAttributes =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
    }
}
