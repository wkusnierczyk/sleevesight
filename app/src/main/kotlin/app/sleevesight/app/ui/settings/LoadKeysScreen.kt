package app.sleevesight.app.ui.settings

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretState
import app.sleevesight.app.ui.ExternalLink
import app.sleevesight.app.ui.rememberOpenUrl
import app.sleevesight.app.ui.scan.QrScanner
import app.sleevesight.app.ui.shownAddress
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

// *Load keys* (#268, #446, #590): five words to type on the computer, which seal the setup code to
// this phone; how to make the code, the camera to read it, what it holds before anything is saved,
// which services to switch on, and then what was saved and switched (#763).

/** The page that makes a setup code (`api-keys.html`, #436), which *Load keys* points to. */
internal const val API_KEYS_URL = "https://sleevesight.app/api-keys"

/** How long the words *Load keys* shows can open a code, from when they are drawn. */
internal val WORDS_LIFETIME: Duration = 5.minutes

/** How often, at most, the words' age is checked while they show, so they go soon after waking. */
private const val WORDS_CHECK_MS = 1_000L

/** What a screen test finds each word *Load keys* shows by. */
internal const val LOAD_KEYS_WORD_TAG = "load-keys-word"

/** Why a sealed code is not opened. */
internal enum class Refusal {
    /** It was sealed with other words, or changed since. */
    WRONG_WORDS,

    /** The words expired before it was scanned. */
    EXPIRED,

    /** The words have already opened a code. */
    USED,

    /** It cannot be read as a sealed code. */
    DAMAGED,
}

/**
 * Where *Load keys* is: the words, the camera, a sealed code being opened or refused, what the code
 * read holds, the services to enable, or what was saved.
 */
private sealed interface LoadStep {
    data object Words : LoadStep

    data object Scanning : LoadStep

    class Opening(
        val code: String,
        val words: List<String>,
    ) : LoadStep

    class Refused(
        val reason: Refusal,
    ) : LoadStep

    /** What the code [read] holds; [sealed] when it came sealed with the words. */
    class Found(
        val read: SetupRead,
        val sealed: Boolean,
    ) : LoadStep

    /** The services to enable once the [keys] are saved, as [plan] has them (#763). */
    class Enable(
        val keys: List<SavedKey>,
        val before: Map<SecretId, SecretState>,
        val plan: EnablePlan,
    ) : LoadStep

    /** What was saved and switched on or off (#763). */
    class Saved(
        val summary: KeySummary,
    ) : LoadStep
}

/**
 * *Load keys*, a screen of its own ([KeyScreen], secure). First, five words drawn from [list] at
 * random ([crypto]), each large, to type on the computer at [API_KEYS_URL], where the page seals the
 * setup code with them (#590), with *New words* and *Next*; they open one code, and are forgotten
 * after [WORDS_LIFETIME] by [clock], which counts deep sleep, or when the screen is left (never
 * saved, so a recreated screen draws new
 * ones). Then how to make the code, the camera's view ([scanner]) to read it, *Show the words* and
 * *Cancel* ([GuidedScan], as the key field's scans, #541). A sealed code is opened with the words
 * (about a second, while the screen says so), or refused: the words do not match it, have expired
 * or were used, or it is damaged. A clear code (version 1) is read as before, and the screen says
 * it was not sealed. Once a code is read (#763), the keys it holds, before anything is saved: each
 * by its group, name and last four characters, whether it is new or replaces a key stored
 * ([secrets]), and a checkbox, ticked, and the keys that did not scan ([KeysStep]); *Next* saves the
 * keys ticked with [onSave], which returns those it could not save: the screen names them, and saves
 * only those on the next try. Then which services to switch on ([EnableStep], [enablePlan]), from
 * the [settings] as they are: *Next* applies the choices with [onApply], *Cancel* leaves every
 * switch as it was. Then the summary ([SummaryStep]), with *Done*. A link to a section of the
 * settings calls [onOpen] and leaves the screen. A code that is not a setup code, or one of a later
 * version, says so, with *Scan again* and *Cancel*.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod", "LongParameterList") // The steps, and what tests may give.
@Composable
internal fun LoadKeysScreen(
    secrets: Map<SecretId, SecretState>,
    settings: Settings,
    scanner: QrScanner,
    onSave: suspend (Map<SecretId, Secret>) -> List<SecretId>,
    onApply: (Map<SecretId, Boolean>) -> Unit,
    onOpen: (SettingsTarget) -> Unit,
    onDismiss: () -> Unit,
    list: KeyWords = remember { KeyWords.load(JvmResourceReader) },
    crypto: KeyCrypto = JvmKeyCrypto,
    clock: () -> Long = SystemClock::elapsedRealtime,
) {
    var step by remember { mutableStateOf<LoadStep>(LoadStep.Words) }
    var busy by remember { mutableStateOf(false) }
    // The words while they can open a code; once they cannot, why. Their age is told by [clock],
    // which counts deep sleep, as a coroutine's delay does not: a phone asleep on this screen for
    // an hour finds them expired. The delay below only hides them on time while the phone is awake.
    var words by remember { mutableStateOf<List<String>?>(list.draw(crypto::pick)) }
    var drawnAt by remember { mutableLongStateOf(clock()) }
    var gone by remember { mutableStateOf<Refusal?>(null) }
    val lifetime = WORDS_LIFETIME.inWholeMilliseconds
    val due = { clock() - drawnAt >= lifetime }
    val expire = {
        words = null
        gone = Refusal.EXPIRED
    }
    LaunchedEffect(words) {
        while (words != null && !due()) delay(minOf(lifetime - (clock() - drawnAt), WORDS_CHECK_MS))
        if (words != null) expire()
    }
    val newWords = {
        words = list.draw(crypto::pick)
        drawnAt = clock()
        gone = null
        step = LoadStep.Words
    }
    // While scanning, the title is the guided scan's, inside what scrolls (#541).
    val title = R.string.settings_scan_keys.takeIf { step != LoadStep.Scanning }
    KeyScreen(title, busy || step is LoadStep.Opening, onDismiss) {
        when (val shown = step) {
            LoadStep.Words -> {
                LaunchedEffect(Unit) { if (words != null && due()) expire() }
                WordsStep(
                    words = words?.takeUnless { due() },
                    onNewWords = newWords,
                    onNext = { step = LoadStep.Scanning },
                    onCancel = onDismiss,
                )
            }

            LoadStep.Scanning -> {
                GuidedScan(
                    title = R.string.settings_scan_keys,
                    make = stringResource(R.string.load_keys_make),
                    url = API_KEYS_URL,
                    linkDescription = R.string.load_keys_instructions_of,
                    read = stringResource(R.string.load_keys_read_code),
                    scanner = scanner,
                    onScanned = { code ->
                        val read = KeySetup.read(code)
                        if (read == SetupRead.Sealed && words != null && due()) expire()
                        val shownWords = words
                        step =
                            when {
                                read != SetupRead.Sealed -> LoadStep.Found(read, sealed = false)
                                shownWords == null -> LoadStep.Refused(gone ?: Refusal.EXPIRED)
                                else -> LoadStep.Opening(code, shownWords)
                            }
                    },
                    onCancel = onDismiss,
                    actions = {
                        TextButton(onClick = { step = LoadStep.Words }) {
                            Text(stringResource(R.string.load_keys_show_words))
                        }
                    },
                )
            }

            is LoadStep.Opening -> {
                OpeningStep()
                LaunchedEffect(shown) {
                    val opened = withContext(Dispatchers.Default) { SealedSetup.open(shown.code, shown.words, crypto) }
                    val read = (opened as? Opened.Setup)?.let { KeySetup.read(it.text) }
                    step =
                        when {
                            opened == Opened.WrongWords -> LoadStep.Refused(Refusal.WRONG_WORDS)
                            read == null || read == SetupRead.Sealed -> LoadStep.Refused(Refusal.DAMAGED)
                            else -> LoadStep.Found(read, sealed = true)
                        }
                    // Opened: the words are spent, whatever the code holds.
                    if (read != null) {
                        words = null
                        gone = Refusal.USED
                    }
                }
            }

            is LoadStep.Refused -> {
                RefusedStep(
                    reason = shown.reason,
                    onShowWords = { step = LoadStep.Words },
                    onNewWords = newWords,
                    onScanAgain = { step = LoadStep.Scanning },
                    onCancel = onDismiss,
                )
            }

            is LoadStep.Found -> {
                val read = shown.read
                if (read is SetupRead.Setup) {
                    KeysStep(
                        setup = read,
                        sealed = shown.sealed,
                        secrets = secrets,
                        onSave = onSave,
                        onBusy = { busy = it },
                        onSaved = { keys, before ->
                            step = LoadStep.Enable(keys, before, enablePlan(keys.map { it.id }, before, settings))
                        },
                        onScanAgain = { step = LoadStep.Scanning },
                        onCancel = onDismiss,
                    )
                } else {
                    UnreadStep(read, onScanAgain = { step = LoadStep.Scanning }, onCancel = onDismiss)
                }
            }

            is LoadStep.Enable -> {
                EnableStep(
                    plan = shown.plan,
                    onOpen = { target ->
                        onDismiss()
                        onOpen(target)
                    },
                    onNext = { choices ->
                        val after = settings.applied(choices)
                        onApply(choices)
                        step = LoadStep.Saved(summaryOf(shown.keys, settings, shown.before, after))
                    },
                    onCancel = onDismiss,
                )
            }

            is LoadStep.Saved -> {
                SummaryStep(shown.summary, onDismiss)
            }
        }
    }
}

/**
 * A code read that holds no keys to offer: one that is not a setup code ([read] is
 * [SetupRead.NotASetup]), or one of a later version; with *Scan again* ([onScanAgain]) and *Cancel*
 * ([onCancel]). A sealed code never shows here: it is opened, or refused, first.
 */
@Composable
private fun ColumnScope.UnreadStep(
    read: SetupRead,
    onScanAgain: () -> Unit,
    onCancel: () -> Unit,
) {
    val unsupported = read as? SetupRead.Unsupported
    val title = if (unsupported != null) R.string.setup_unsupported_title else R.string.setup_not_a_setup_title
    StepHeading(stringResource(title), announced = true)
    Column(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
        val text =
            unsupported?.let { stringResource(R.string.setup_unsupported, it.version) }
                ?: stringResource(R.string.setup_not_a_setup)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
    KeyScreenActions(
        cancel = { TextButton(onClick = onCancel) { Text(stringResource(R.string.settings_cancel)) } },
    ) {
        TextButton(onClick = onScanAgain) { Text(stringResource(R.string.setup_scan_again)) }
    }
}

/**
 * The words step: [words], each on a line of its own, large, its first three letters bold, which
 * are enough to type it; or, once they have expired, that they have. *New words* calls
 * [onNewWords], *Next* (while there are words) [onNext], and *Cancel* [onCancel].
 */
@Composable
private fun ColumnScope.WordsStep(
    words: List<String>?,
    onNewWords: () -> Unit,
    onNext: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val style = MaterialTheme.typography.bodyMedium
        Text(stringResource(R.string.load_keys_words_intro), style = style)
        StepHeading(stringResource(R.string.load_keys_open))
        val address = shownAddress(API_KEYS_URL)
        ExternalLink(
            text = address,
            description = stringResource(R.string.load_keys_instructions_of, address),
            onClick = rememberOpenUrl(API_KEYS_URL),
        )
        StepHeading(stringResource(R.string.load_keys_type))
        if (words == null) {
            Text(
                stringResource(R.string.load_keys_words_expired),
                style = style,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            for (word in words) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(word.take(KeyWords.PREFIX)) }
                        append(word.drop(KeyWords.PREFIX))
                    },
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.testTag(LOAD_KEYS_WORD_TAG),
                )
            }
            val minutes = WORDS_LIFETIME.inWholeMinutes.toInt()
            Text(pluralStringResource(R.plurals.load_keys_words_expire, minutes, minutes), style = style)
        }
    }
    KeyScreenActions(
        cancel = { TextButton(onClick = onCancel) { Text(stringResource(R.string.settings_cancel)) } },
    ) {
        TextButton(onClick = onNewWords) { Text(stringResource(R.string.load_keys_new_words)) }
        if (words != null) TextButton(onClick = onNext) { Text(stringResource(R.string.load_keys_next)) }
    }
}

/**
 * While a sealed code is opened: about a second, on a slow phone. Its heading has the turning record
 * before it, in place of a progress bar (#785).
 */
@Composable
private fun OpeningStep() {
    StepHeading(stringResource(R.string.load_keys_opening), announced = true, busy = true)
}

/**
 * Why a sealed code was not opened ([reason]), and what to do: words that do not match it offer
 * *Show the words* ([onShowWords]) and *Scan again* ([onScanAgain]); words expired or used, *New
 * words* ([onNewWords]); a damaged code, *Scan again*; each with *Cancel* ([onCancel]).
 */
@Composable
private fun ColumnScope.RefusedStep(
    reason: Refusal,
    onShowWords: () -> Unit,
    onNewWords: () -> Unit,
    onScanAgain: () -> Unit,
    onCancel: () -> Unit,
) {
    val (title, text) =
        when (reason) {
            Refusal.WRONG_WORDS -> R.string.load_keys_wrong_words_title to R.string.load_keys_wrong_words
            Refusal.EXPIRED -> R.string.load_keys_expired_title to R.string.load_keys_expired
            Refusal.USED -> R.string.load_keys_used_title to R.string.load_keys_used
            Refusal.DAMAGED -> R.string.load_keys_damaged_title to R.string.load_keys_damaged
        }
    StepHeading(stringResource(title), announced = true)
    Column(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
    }
    KeyScreenActions(
        cancel = { TextButton(onClick = onCancel) { Text(stringResource(R.string.settings_cancel)) } },
    ) {
        when (reason) {
            Refusal.WRONG_WORDS -> {
                TextButton(onClick = onShowWords) { Text(stringResource(R.string.load_keys_show_words)) }
                TextButton(onClick = onScanAgain) { Text(stringResource(R.string.setup_scan_again)) }
            }

            Refusal.EXPIRED, Refusal.USED -> {
                TextButton(onClick = onNewWords) { Text(stringResource(R.string.load_keys_new_words)) }
            }

            Refusal.DAMAGED -> {
                TextButton(onClick = onScanAgain) { Text(stringResource(R.string.setup_scan_again)) }
            }
        }
    }
}
