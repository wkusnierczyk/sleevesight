package app.sleevesight.app.speech

import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.TrackIdentity
import app.sleevesight.app.ui.home.SpokenWords
import app.sleevesight.app.ui.home.spokenSentences
import app.sleevesight.app.ui.home.spokenTrackLine
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.ReadAloudMusic
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * One reading aloud: which button it is of ([id]: [INSIGHT], or a genre's reason,
 * [genreReason]), the track it is about ([track]), what it reads ([of], the insight or reason
 * shown, so a new one shown in its place stops it), and its [parts], in [locale].
 */
class Reading(
    val id: String,
    val track: NowPlaying,
    val of: InsightState.Written,
    val parts: List<String>,
    val locale: Locale,
) {
    companion object {
        /** The id of the insight card's reading. */
        const val INSIGHT: String = "insight"

        /** The id of the reading of [genre]'s reason, in its panel. */
        fun genreReason(genre: String): String = "genre:$genre"
    }
}

/** Why a reading could not start, or ended before its end, which the user is told. */
enum class ReadAloudProblem {
    /** The phone has no text-to-speech engine, or it failed to start. */
    NO_ENGINE,

    /** The engine has no voice for the insight's language: said, not read with a wrong voice. */
    NO_VOICE,

    /** Android refused the audio focus: a call, or another app's sound that cannot be lowered. */
    BUSY,

    /** The engine failed partway. */
    FAILED,
}

/**
 * *Read aloud* (#325): an insight, or a genre's reason (#523), read by the phone's text-to-speech
 * engine ([speech]), with the music lowered or paused for it ([focus]) as *While reading aloud*
 * says ([settings]). One reading at a time: a new one replaces the last. [reading] is the one under
 * way, from the tap until its last word, which a button shows as *Stop reading aloud*.
 *
 * It stops at once on [stop] (the button again); when the track it is about stops playing (a track
 * change, or the player's session ending: [follow]); when what it reads is no longer shown (a
 * rewrite in its place); when another app takes the sound from it (a call); and when the app leaves
 * the screen ([activityStopped]), which releases the engine too. A rotation, which stops the
 * activity only to start it again, does not stop it.
 *
 * *Read new insights aloud* ([follow]): an insight written while the app is on screen, as the call
 * that wrote it ends (not one from the cache, nor one written while the app was away), is read as
 * a tap would read it, never while a screen reader explores by touch ([screenReader]), which reads
 * the screen itself. The button works either way.
 *
 * What is said is the app's ([words]): the track line first with *Read the track line first*,
 * then the sentences as the card shows them ([spokenSentences]), in the insight's language
 * ([insightLocale]). A problem (no engine, no voice for the language, no audio focus, the engine
 * failing) is told ([tell]), never read past. Called on the main thread.
 */
@Suppress("LongParameterList", "TooManyFunctions") // Its seams each a parameter; one set of rules.
class ReadAloud(
    private val speech: Speech,
    private val focus: MusicFocus,
    private val settings: StateFlow<Settings?>,
    private val words: SpokenWords,
    private val screenReader: () -> Boolean,
    private val tell: (ReadAloudProblem, Locale) -> Unit,
    private val locale: () -> Locale = ::insightLocale,
) {
    private val readingState = MutableStateFlow<Reading?>(null)

    /** The reading under way, from the tap until its last word, or null. */
    val reading: StateFlow<Reading?> = readingState.asStateFlow()

    // Activities started, and whether one stopped for a configuration change and is starting again.
    private var started = 0
    private var restarting = false

    /** Whether the app is on screen: an activity started, or one restarting after a rotation. */
    val visible: Boolean get() = started > 0 || restarting

    /** Reads [written], the insight shown on the card for [track]. */
    fun readInsight(
        track: TrackState,
        written: InsightState.Written,
    ) = read(Reading(Reading.INSIGHT, track.nowPlaying, written, script(track, null, written), locale()))

    /** Reads [written], the reason [track] has [genre], after its [question] (*Why is this …?*). */
    fun readGenreReason(
        track: TrackState,
        genre: String,
        question: String,
        written: InsightState.Written,
    ) = read(Reading(Reading.genreReason(genre), track.nowPlaying, written, script(track, question, written), locale()))

    // The track line with *Read the track line first*, then [heading], then the sentences.
    private fun script(
        track: TrackState,
        heading: String?,
        written: InsightState.Written,
    ): List<String> {
        val line = spokenTrackLine(track, words).takeIf { settings.value?.readTrackLineFirst == true }
        return listOfNotNull(line, heading) + spokenSentences(written.grounded, words)
    }

    /** Starts [reading], in place of the one under way. */
    fun read(reading: Reading) {
        stop()
        if (reading.parts.isEmpty()) return
        readingState.value = reading
        speech.open { opened ->
            // Stopped, or replaced, while the engine opened.
            if (readingState.value !== reading) return@open
            val music = settings.value?.readAloudMusic ?: ReadAloudMusic.LOWER
            when {
                !opened -> fail(reading, ReadAloudProblem.NO_ENGINE)
                !speech.speaks(reading.locale) -> fail(reading, ReadAloudProblem.NO_VOICE)
                !focus.take(music) { if (readingState.value === reading) stop() } ->
                    fail(reading, ReadAloudProblem.BUSY)
                else ->
                    speech.speak(
                        reading.parts,
                        reading.locale,
                        done = { if (readingState.value === reading) end() },
                        failed = { if (readingState.value === reading) fail(reading, ReadAloudProblem.FAILED) },
                    )
            }
        }
    }

    /** Stops the reading under way at once, and gives the music back; nothing if none is. */
    fun stop() {
        if (readingState.value == null) return
        speech.stop()
        end()
    }

    private fun end() {
        readingState.value = null
        focus.release()
    }

    private fun fail(
        reading: Reading,
        problem: ReadAloudProblem,
    ) {
        speech.stop()
        end()
        tell(problem, reading.locale)
    }

    /** An activity started: the app is on screen. */
    fun activityStarted() {
        started++
        restarting = false
    }

    /**
     * An activity stopped: unless it is only restarting ([changingConfigurations], a rotation), and
     * no other is started, the app has left the screen, and the reading stops, the engine released.
     */
    fun activityStopped(changingConfigurations: Boolean) {
        started = (started - 1).coerceAtLeast(0)
        restarting = changingConfigurations
        if (!visible) {
            stop()
            speech.close()
        }
    }

    /**
     * Follows what plays ([playing]) and what the pipeline shows ([view]), as long as the caller's
     * scope: a reading stops when its track no longer plays on ([TrackIdentity.playsOn]: another
     * track, or none) or when the card's insight it reads is replaced; and with *Read new insights
     * aloud*, each insight written while the app is on screen is read as it lands.
     */
    suspend fun follow(
        playing: StateFlow<NowPlaying?>,
        view: Flow<PipelineView>,
    ) = coroutineScope {
        launch {
            playing.collect { now ->
                val reading = readingState.value ?: return@collect
                if (now == null || !TrackIdentity.playsOn(reading.track, now)) stop()
            }
        }
        launch { followInsights(playing, view) }
    }

    // The card's insight as it changes: a reading of one replaced stops; one newly written reads.
    private suspend fun followInsights(
        playing: StateFlow<NowPlaying?>,
        view: Flow<PipelineView>,
    ) {
        // The insight shown when this began is not new: only one that lands after it is.
        var first = true
        var last: InsightState.Written? = null
        view.collect { shown ->
            val track = shown.track
            val written = track?.insight as? InsightState.Written
            val reading = readingState.value
            if (reading?.id == Reading.INSIGHT && written != reading.of) stop()
            val fresh = written != null && written != last && !written.cached && !first
            first = false
            if (written != null) last = written
            if (fresh && track != null && readsNew(track, playing.value)) readInsight(track, written)
        }
    }

    // Whether a new insight for [track] is read by itself: *Read new insights aloud* on, the app on
    // screen, no screen reader exploring, and [track] the one playing.
    private fun readsNew(
        track: TrackState,
        now: NowPlaying?,
    ): Boolean =
        settings.value?.readNewInsightsAloud == true &&
            visible &&
            !screenReader() &&
            now != null &&
            TrackIdentity.playsOn(track.nowPlaying, now)
}

/**
 * The insight's language, which the voice speaks: English, which the prompts ask the model to write
 * in (`InsightPrompt`, `GenreReasonPrompt`), in the phone's own variety of it where the phone is set
 * to one (a British voice on a British phone), else English as the engine chooses.
 */
fun insightLocale(phone: Locale = Locale.getDefault()): Locale =
    if (phone.language == Locale.ENGLISH.language) phone else Locale.ENGLISH
