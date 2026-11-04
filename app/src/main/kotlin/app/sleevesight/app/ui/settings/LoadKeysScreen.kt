package app.sleevesight.app.ui.settings

import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretState
import app.sleevesight.app.ui.scan.QrScanner
import app.sleevesight.core.Secret
import app.sleevesight.core.keys.KeyCrypto
import app.sleevesight.core.keys.KeySetup
import app.sleevesight.core.keys.KeyWords
import app.sleevesight.core.keys.Opened
import app.sleevesight.core.keys.SealedSetup
import app.sleevesight.core.keys.SetupRead
import app.sleevesight.core.platform.JvmKeyCrypto
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

// *Load keys* (#268, #446, #590, #765): an intro, then four numbered steps: ① five words to type
// on the computer, which seal the setup code to this phone; ② the camera to read the code; ③ the
// keys it holds, to save; ④ the services to switch on (#763); then a summary of what was saved and
// switched. Nothing is saved before ④'s *Complete*.

/** The page that makes a setup code (`api-keys.html`, #436), which *Load keys* points to. */
internal const val API_KEYS_URL = "https://sleevesight.app/api-keys"

/** How long the words *Load keys* shows can open a code, from when they are drawn. */
internal val WORDS_LIFETIME: Duration = 5.minutes

/** How often, at most, the words' age is checked while they matter, so the countdown moves each second. */
private const val WORDS_CHECK_MS = 1_000L

/** What a screen test finds each word *Load keys* shows by. */
internal const val LOAD_KEYS_WORD_TAG = "load-keys-word"

/** Why a code read on ② gives no keys (#765): each says so in a panel under the camera, which keeps reading. */
internal enum class Refusal {
    /** A sealed code the words shown do not open: sealed with other words, or changed since. */
    WRONG_WORDS,

    /** A sealed code scanned after the words last shown expired, before new ones were shown. */
    EXPIRED,

    /** A sealed code scanned after the words last shown opened a code, before new ones were shown. */
    USED,

    /** It cannot be read as a sealed code. */
    DAMAGED,

    /** It is not a setup code. */
    NOT_A_SETUP,

    /** It is a setup code of a later version. */
    NEWER,
}

/** *Load keys*' screens, in order (#765), each by its [title] and the [number] before it, if any. */
@Suppress("MagicNumber") // The steps' numbers, as their titles show them.
private enum class LoadStep(
    @StringRes val title: Int?,
    val number: Int?,
) {
    INTRO(R.string.settings_scan_keys, null),
    WORDS(R.string.load_keys_words_title, 1),

    // The guided scan's title, inside what scrolls (#541), [ScanStep]'s.
    SCAN(null, 2),
    KEYS(R.string.load_keys_keys_title, 3),
    ENABLE(R.string.load_keys_enable_title, 4),
    SUMMARY(R.string.load_keys_summary_title, null),
}

/** What ② has made of the codes it read: none yet or one refused, a sealed one being opened, or one read. */
internal sealed interface ScanState {
    /** The camera reads, after the code [refused], if any. */
    class Reading(
        val refused: Refusal? = null,
    ) : ScanState

    /** A sealed [code] being opened with the [words] shown. */
    class Opening(
        val code: String,
        val words: List<String>,
    ) : ScanState

    /** The code read, [load]: the camera has stopped. */
    class Read(
        val load: KeyLoad,
    ) : ScanState
}

/**
 * What was saved ([keys], with the keys stored [before]) and switched on or off: the [choices] as
 * written ([applied]), or null when they could not be.
 */
private class Done(
    val keys: List<SavedKey>,
    val before: Map<SecretId, SecretState>,
    val choices: Map<SecretId, Boolean>,
    val applied: Applied?,
)

/**
 * *Load keys*, a screen of its own ([KeyScreen], secure), in steps (#765). The intro says where the
 * code is made, [API_KEYS_URL], as plain text, with *Start*. ① five words drawn from [list] at
 * random ([crypto]) when *Start* is tapped, each large ([WordsStep]), to give the page, which seals
 * the setup code with them (#590), with the time they have left; they open one code, and when
 * their [WORDS_LIFETIME] is up, by [clock], which counts deep sleep, new ones are drawn by
 * themselves. They are never saved, so a recreated screen draws new ones. ② the camera ([scanner],
 * [ScanStep]): a sealed code is opened with the words, or refused, in a panel under the camera,
 * which keeps reading; a code read stops it, and enables *Next*. ③ the keys the code holds, each
 * ticked ([KeysStep]), with *Next* while any is. ④ the services to switch on ([EnableStep],
 * [enablePlan]), planned from the keys ticked, as if saved, and the [settings] as they are:
 * *Complete* saves the keys ticked with [onSave], which returns those it could not save (④ names
 * them, and saves only those on the next try), and then writes the choices with [onApply], which
 * returns the settings as written, or null when they could not be. Then the summary
 * ([SummaryStep]), of what was written, or that it could not be, with *Try again*; *Done*.
 * *Previous* goes back a step, and *Cancel*, on any step, leaves without saving anything more. A
 * link to a section of the settings calls [onOpen] and leaves the screen.
 *
 * The words that open a code are the ones ① shows. A sealed code scanned after the words last shown
 * there expired or opened a code, before ① has shown the new ones, is refused as expired or used,
 * without trying the new ones, which it cannot have been made with; one the words shown do not
 * open is refused as made with other words. So a code is never opened with words that have expired
 * or been spent.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod", "LongParameterList") // The steps, and what tests may give.
@Composable
internal fun LoadKeysScreen(
    secrets: Map<SecretId, SecretState>,
    settings: Settings,
    scanner: QrScanner,
    onSave: suspend (Map<SecretId, Secret>) -> List<SecretId>,
    onApply: suspend (Map<SecretId, Boolean>) -> Applied?,
    onOpen: (SettingsTarget) -> Unit,
    onDismiss: () -> Unit,
    list: KeyWords = remember { KeyWords.load(JvmResourceReader) },
    crypto: KeyCrypto = JvmKeyCrypto,
    clock: () -> Long = SystemClock::elapsedRealtime,
) {
    var step by remember { mutableStateOf(LoadStep.INTRO) }
    var busy by remember { mutableStateOf(false) }
    // The words, drawn at *Start*. Their age is told by [clock], which counts deep sleep, as a
    // coroutine's delay does not: a phone asleep on this screen for an hour finds them renewed.
    var words by remember { mutableStateOf<List<String>?>(null) }
    var drawnAt by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(0L) }
    // Whether ① has shown the words since they were drawn, and why the ones before them went.
    var seen by remember { mutableStateOf(false) }
    var retired by remember { mutableStateOf<Refusal?>(null) }
    var scan by remember { mutableStateOf<ScanState>(ScanState.Reading()) }
    var plan by remember { mutableStateOf<EnablePlan?>(null) }
    var done by remember { mutableStateOf<Done?>(null) }
    val lifetime = WORDS_LIFETIME.inWholeMilliseconds
    val due = { clock() - drawnAt >= lifetime }
    val draw = {
        words = list.draw(crypto::pick)
        drawnAt = clock()
        now = drawnAt
        seen = false
    }
    // The words shown go, [why], and new ones take their place, for ① to show.
    val retire = { why: Refusal ->
        retired = why
        draw()
    }
    // While the words matter, on ① and ②: renewed once their time is up, checked each second, which
    // moves ①'s countdown, and at once on coming back to either.
    val live = step == LoadStep.WORDS || step == LoadStep.SCAN
    LaunchedEffect(words, live) {
        if (words == null || !live) return@LaunchedEffect
        while (!due()) {
            now = clock()
            delay(minOf(lifetime - (now - drawnAt), WORDS_CHECK_MS))
        }
        retire(Refusal.EXPIRED)
    }
    val read = (scan as? ScanState.Read)?.load
    // The keys ticked on ③, as left there; those a try of *Complete* saved, and those it could not.
    var ticked by remember(read) { mutableStateOf(read?.let { load -> load.keys.map { it.id }.toSet() }.orEmpty()) }
    var saved by remember(read) { mutableStateOf(emptySet<SecretId>()) }
    var unsaved by remember(read) { mutableStateOf(emptyList<SecretId>()) }
    KeyScreen(step.title, busy, onDismiss, step.number) {
        when (step) {
            LoadStep.INTRO -> {
                IntroStep(
                    onStart = {
                        draw()
                        step = LoadStep.WORDS
                    },
                    onCancel = onDismiss,
                )
            }

            LoadStep.WORDS -> {
                val shown = words ?: return@KeyScreen
                LaunchedEffect(shown) { seen = true }
                WordsStep(
                    words = shown,
                    left = lifetime - (now - drawnAt),
                    onNewWords = draw,
                    onNext = {
                        scan = ScanState.Reading()
                        step = LoadStep.SCAN
                    },
                    onCancel = onDismiss,
                )
            }

            LoadStep.SCAN -> {
                (scan as? ScanState.Opening)?.let { opening ->
                    LaunchedEffect(opening) {
                        val opened =
                            withContext(Dispatchers.Default) { SealedSetup.open(opening.code, opening.words, crypto) }
                        val setup = (opened as? Opened.Setup)?.let { KeySetup.read(it.text) }
                        // Opened: the words are spent, whatever the code holds.
                        if (setup != null && words == opening.words) retire(Refusal.USED)
                        scan =
                            when {
                                opened == Opened.WrongWords -> ScanState.Reading(Refusal.WRONG_WORDS)
                                setup == null -> ScanState.Reading(Refusal.DAMAGED)
                                else -> scanned(setup, sealed = true, secrets)
                            }
                    }
                }
                ScanStep(
                    scan = scan,
                    scanner = scanner,
                    onScanned = { code ->
                        // Only while the camera reads: not while a code opens.
                        if (scan !is ScanState.Reading) return@ScanStep
                        val setup = KeySetup.read(code)
                        if (setup != SetupRead.Sealed) {
                            scan = scanned(setup, sealed = false, secrets)
                            return@ScanStep
                        }
                        if (due()) retire(Refusal.EXPIRED)
                        val shown = words
                        // Words ① has not shown cannot have made the code: why the ones it showed went.
                        scan =
                            if (shown == null || !seen) {
                                ScanState.Reading(retired ?: Refusal.EXPIRED)
                            } else {
                                ScanState.Opening(code, shown)
                            }
                    },
                    onPrevious = { step = LoadStep.WORDS },
                    onNext = { step = LoadStep.KEYS },
                    onCancel = onDismiss,
                )
            }

            LoadStep.KEYS -> {
                val load = read ?: return@KeyScreen
                KeysStep(
                    load = load,
                    ticked = ticked,
                    saved = saved,
                    unsaved = unsaved,
                    onTick = { id, on -> ticked = if (on) ticked + id else ticked - id },
                    onPrevious = { step = LoadStep.SCAN },
                    onScanAgain = {
                        scan = ScanState.Reading()
                        step = LoadStep.SCAN
                    },
                    onNext = {
                        plan = enablePlan(load.keys.map { it.id }.filter { it in ticked }, load.before, settings)
                        step = LoadStep.ENABLE
                    },
                    onCancel = onDismiss,
                )
            }

            LoadStep.ENABLE -> {
                val load = read ?: return@KeyScreen
                val shown = plan ?: return@KeyScreen
                EnableStep(
                    plan = shown,
                    unsaved = unsaved,
                    onOpen = { target ->
                        onDismiss()
                        onOpen(target)
                    },
                    onPrevious = { step = LoadStep.KEYS },
                    onComplete = { choices ->
                        busy = true
                        try {
                            // After a failure, the keys saved on the first try are not saved again.
                            val left = load.keys.filter { it.id in ticked && it.id !in saved }
                            unsaved = if (left.isEmpty()) emptyList() else onSave(left.associate { it.id to it.key })
                            saved = saved + left.map { it.id }.filterNot { it in unsaved }
                            if (unsaved.isEmpty()) {
                                val keys =
                                    load.keys
                                        .filter { it.id in ticked }
                                        .map { SavedKey(it.id, endingOf(it.key.reveal()), it.replaces) }
                                done = Done(keys, load.before, choices, onApply(choices))
                                step = LoadStep.SUMMARY
                            }
                        } finally {
                            busy = false
                        }
                    },
                    onCancel = onDismiss,
                )
            }

            LoadStep.SUMMARY -> {
                val shown = done ?: return@KeyScreen
                SummaryStep(
                    keys = shown.keys,
                    secrets = shown.before,
                    first = shown.applied,
                    retry = { onApply(shown.choices) },
                    onBusy = { busy = it },
                    onDone = onDismiss,
                    framed = true,
                )
            }
        }
    }
}

/**
 * What ② makes of a code read as [setup], [sealed] with the words or not: the keys it holds, with
 * the keys stored as [secrets] has them then; or why it holds none to offer.
 */
private fun scanned(
    setup: SetupRead,
    sealed: Boolean,
    secrets: Map<SecretId, SecretState>,
): ScanState =
    when (setup) {
        is SetupRead.Setup -> ScanState.Read(KeyLoad(setup, sealed, secrets))
        is SetupRead.Unsupported -> ScanState.Reading(Refusal.NEWER)
        SetupRead.NotASetup -> ScanState.Reading(Refusal.NOT_A_SETUP)
        // Sealed inside a sealed code: none the page makes.
        SetupRead.Sealed -> ScanState.Reading(Refusal.DAMAGED)
    }

/**
 * The intro (#765): in a panel centred on the screen, to open the API keys page on a computer, its
 * address in full as plain text ([ApiKeysAddress]), and to tap *Start* ([onStart]), which draws the
 * words; *Cancel* calls [onCancel].
 */
@Composable
private fun ColumnScope.IntroStep(
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    FilledMiddle(
        least = null,
        above = {},
        middle = {
            KeyPanel {
                val style = MaterialTheme.typography.bodyMedium
                Text(stringResource(R.string.load_keys_intro_open), style = style)
                ApiKeysAddress(centred = true)
                Text(stringResource(R.string.load_keys_intro_start), style = style)
            }
        },
        below = {},
    )
    KeyScreenActions(
        cancel = { TextButton(onClick = onCancel) { Text(stringResource(R.string.settings_cancel)) } },
    ) {
        TextButton(onClick = onStart) { Text(stringResource(R.string.load_keys_start)) }
    }
}
