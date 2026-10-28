package app.sleevesight.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import app.sleevesight.core.keys.KeyProblem
import app.sleevesight.core.keys.KeySetup
import app.sleevesight.core.keys.KeyWords
import app.sleevesight.core.keys.Opened
import app.sleevesight.core.keys.SealedSetup
import app.sleevesight.core.keys.SetupRead
import app.sleevesight.core.platform.JvmKeyCrypto
import app.sleevesight.core.platform.JvmResourceReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

// *Load keys* (#268, #446, #590): five words to type on the computer, which seal the setup code to
// this phone; how to make the code, the camera to read it, what it holds before anything is saved,
// and then what was saved.

/** The page that makes a setup code (`api-keys.html`, #436), which *Load keys* points to. */
internal const val API_KEYS_URL = "https://sleevesight.app/api-keys"

/** What a setup code read offers to save: all it holds, only its good keys, or nothing. */
internal enum class SetupOffer { ALL, SOME, NONE }

/**
 * What [read] offers: [SetupOffer.ALL] when every line is a key that can be saved,
 * [SetupOffer.SOME] when some are and others are refused, unknown or unreadable, and
 * [SetupOffer.NONE] when none is, or the code is not a setup code, or one of a later version.
 */
internal fun offerOf(read: SetupRead): SetupOffer =
    when {
        read !is SetupRead.Setup || read.keys.isEmpty() -> SetupOffer.NONE
        read.refused.isEmpty() && read.unknown.isEmpty() && read.unreadable == 0 -> SetupOffer.ALL
        else -> SetupOffer.SOME
    }

/** How long the words *Load keys* shows can open a code, from when they are drawn. */
internal val WORDS_LIFETIME: Duration = 5.minutes

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
 * read holds, or what was saved.
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

    class Saved(
        val keys: List<ShownKey>,
    ) : LoadStep
}

/**
 * *Load keys*, a screen of its own ([KeyScreen], secure). First, five words drawn from [list] at
 * random ([crypto]), each large, to type on the computer at [API_KEYS_URL], where the page seals the
 * setup code with them (#590), with *New words* and *Next*; they open one code, and are forgotten
 * after [WORDS_LIFETIME] or when the screen is left (never saved, so a recreated screen draws new
 * ones). Then how to make the code, the camera's view ([scanner]) to read it, *Show the words* and
 * *Cancel* ([GuidedScan], as the key field's scans, #541). A sealed code is opened with the words
 * (about a second, while the screen says so), or refused: the words do not match it, have expired
 * or were used, or it is damaged. A clear code (version 1) is read as before, and the screen says
 * it was not sealed. Once a code is read, what it holds shows, before anything is saved: each key in
 * its service's shape, by its last four characters, and whether it is new or replaces a key stored
 * ([secrets]); then every problem: a key refused and why, a name this version does not know, a line
 * that could not be read, a code that is not a setup code or is of a later version. All good offers
 * *Save*; some keys good, *Save these* (those only) and *Scan again*; nothing usable, *Scan again*;
 * each with *Cancel*. [onSave] saves the keys and returns those it could not: the screen then names
 * them and saves only those on the next try, and once none is left, it says which keys were saved,
 * with *Done*.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod", "LongParameterList") // The steps, and what tests may give.
@Composable
internal fun LoadKeysScreen(
    secrets: Map<SecretId, SecretState>,
    scanner: QrScanner,
    onSave: suspend (Map<SecretId, Secret>) -> List<SecretId>,
    onDismiss: () -> Unit,
    list: KeyWords = remember { KeyWords.load(JvmResourceReader) },
    crypto: KeyCrypto = JvmKeyCrypto,
) {
    var step by remember { mutableStateOf<LoadStep>(LoadStep.Words) }
    var busy by remember { mutableStateOf(false) }
    // The words while they can open a code; once they cannot, why.
    var words by remember { mutableStateOf<List<String>?>(list.draw(crypto::pick)) }
    var gone by remember { mutableStateOf<Refusal?>(null) }
    LaunchedEffect(words) {
        if (words != null) {
            delay(WORDS_LIFETIME)
            words = null
            gone = Refusal.EXPIRED
        }
    }
    val newWords = {
        words = list.draw(crypto::pick)
        gone = null
        step = LoadStep.Words
    }
    // While scanning, the title is the guided scan's, inside what scrolls (#541).
    val title = R.string.settings_scan_keys.takeIf { step != LoadStep.Scanning }
    KeyScreen(title, busy || step is LoadStep.Opening, onDismiss) {
        when (val shown = step) {
            LoadStep.Words -> {
                WordsStep(
                    words = words,
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
                FoundStep(
                    read = shown.read,
                    sealed = shown.sealed,
                    secrets = secrets,
                    onSave = onSave,
                    onBusy = { busy = it },
                    onSaved = { step = LoadStep.Saved(it) },
                    onScanAgain = { step = LoadStep.Scanning },
                    onCancel = onDismiss,
                )
            }

            is LoadStep.Saved -> {
                SavedStep(shown.keys, onDismiss)
            }
        }
    }
}

/**
 * What the code [read] holds, and what can be done with it ([offerOf]), and, for a setup code that
 * was not [sealed], that anyone who saw it can read its keys. [onSave] saves the keys given and
 * returns those it could not; [onBusy] hears while it runs, when nothing else can be done, and
 * [onSaved] the keys saved once none is left.
 */
@Suppress("LongParameterList") // What the code holds, and what each button does.
@Composable
private fun ColumnScope.FoundStep(
    read: SetupRead,
    sealed: Boolean,
    secrets: Map<SecretId, SecretState>,
    onSave: suspend (Map<SecretId, Secret>) -> List<SecretId>,
    onBusy: (Boolean) -> Unit,
    onSaved: (List<ShownKey>) -> Unit,
    onScanAgain: () -> Unit,
    onCancel: () -> Unit,
) {
    val keys = remember(read) { (read as? SetupRead.Setup)?.keys?.mapKeys { SecretId.of(it.key) }.orEmpty() }
    var unsaved by remember(read) { mutableStateOf<List<SecretId>>(emptyList()) }
    // The keys stored when the code was read: a key it holds stays *new* once a first try saved it.
    val before = remember(read) { secrets }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val offer = offerOf(read)
    StepHeading(stringResource(foundTitle(read)), announced = true)
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val style = MaterialTheme.typography.bodyMedium
        when (read) {
            // A sealed code never shows here: it is opened, or refused, first.
            SetupRead.NotASetup, SetupRead.Sealed -> {
                Text(stringResource(R.string.setup_not_a_setup), style = style)
            }

            is SetupRead.Unsupported -> {
                Text(stringResource(R.string.setup_unsupported, read.version), style = style)
            }

            is SetupRead.Setup -> {
                SetupContents(read, keys, before, unsaved, sealed)
            }
        }
    }
    KeyScreenActions(
        cancel = { TextButton(onClick = onCancel, enabled = !busy) { Text(stringResource(R.string.settings_cancel)) } },
    ) {
        if (offer != SetupOffer.ALL) {
            TextButton(onClick = onScanAgain, enabled = !busy) { Text(stringResource(R.string.setup_scan_again)) }
        }
        if (offer != SetupOffer.NONE) {
            val save = if (offer == SetupOffer.ALL) R.string.settings_save else R.string.load_keys_save_these
            TextButton(
                onClick = {
                    // A second tap in the same frame finds the button still enabled.
                    if (busy) return@TextButton
                    busy = true
                    onBusy(true)
                    scope.launch {
                        // After a failure, only the keys not yet saved are saved again.
                        val left = if (unsaved.isEmpty()) keys else keys.filterKeys { it in unsaved }
                        try {
                            unsaved = onSave(left)
                        } finally {
                            busy = false
                            onBusy(false)
                        }
                        if (unsaved.isEmpty()) onSaved(keys.map { (id, key) -> ShownKey(id, endingOf(key.reveal())) })
                    }
                },
                enabled = !busy,
            ) { Text(stringResource(save)) }
        }
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

/** While a sealed code is opened: about a second, on a slow phone. */
@Composable
private fun OpeningStep() {
    StepHeading(stringResource(R.string.load_keys_opening), announced = true)
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
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

/** The heading over what the code [read] holds. */
@StringRes
private fun foundTitle(read: SetupRead): Int =
    when {
        read is SetupRead.NotASetup -> R.string.setup_not_a_setup_title
        read is SetupRead.Unsupported -> R.string.setup_unsupported_title
        offerOf(read) == SetupOffer.NONE -> R.string.load_keys_nothing_title
        else -> R.string.setup_title
    }

/**
 * What the setup code [setup] holds: its [keys] in their services' shape, each by its last four
 * characters and whether it replaces a key stored ([secrets]), with, for an AI service's key, where
 * its terms are told (#409); unless the code was [sealed], that anyone who saw it can read them;
 * then the problems: the keys refused, the names unknown, the lines unreadable; and the keys that
 * could not be saved ([unsaved]).
 */
@Composable
private fun SetupContents(
    setup: SetupRead.Setup,
    keys: Map<SecretId, Secret>,
    secrets: Map<SecretId, SecretState>,
    unsaved: List<SecretId>,
    sealed: Boolean,
) {
    val style = MaterialTheme.typography.bodyMedium
    if (keys.isNotEmpty()) {
        Text(stringResource(R.string.setup_explained), style = style)
        if (keys.keys.any { serviceTerms(it) != null }) Text(stringResource(R.string.setup_terms), style = style)
        StepHeading(stringResource(R.string.load_keys_found))
        KeyTable(
            keys.map { (id, key) ->
                val stored = (secrets[id] ?: SecretState.ABSENT) != SecretState.ABSENT
                KeyRow(ShownKey(id, endingOf(key.reveal())), replaces = stored)
            },
        )
        if (!sealed) Text(stringResource(R.string.load_keys_clear_code), style = style)
    }
    if (setup.refused.isNotEmpty() || setup.unknown.isNotEmpty() || setup.unreadable > 0) {
        StepHeading(stringResource(R.string.load_keys_problems))
    }
    for ((name, problem) in setup.refused) {
        val service = stringResource(keyName(SecretId.of(name)))
        Text(stringResource(R.string.setup_refused, service, stringResource(refusal(problem))), style = style)
    }
    if (setup.unknown.isNotEmpty()) {
        Text(stringResource(R.string.setup_unknown, setup.unknown.joinToString()), style = style)
    }
    if (setup.unreadable > 0) {
        Text(pluralStringResource(R.plurals.setup_unreadable, setup.unreadable, setup.unreadable), style = style)
    }
    if (keys.isEmpty()) Text(stringResource(R.string.setup_nothing), style = style)
    if (unsaved.isNotEmpty()) {
        val names = unsaved.map { stringResource(keyName(it)) }.joinToString()
        Text(stringResource(R.string.setup_failed, names), style = style, color = MaterialTheme.colorScheme.error)
    }
}

/** The confirmation: which [keys] were saved. */
@Composable
private fun ColumnScope.SavedStep(
    keys: List<ShownKey>,
    onDone: () -> Unit,
) {
    StepHeading(stringResource(R.string.load_keys_saved_title), announced = true)
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val style = MaterialTheme.typography.bodyMedium
        KeyTable(keys.map(::KeyRow))
        Text(stringResource(R.string.setup_explained), style = style)
    }
    KeyScreenActions {
        TextButton(onClick = onDone) { Text(stringResource(R.string.key_screen_done)) }
    }
}

/** Why a key in a setup code is not saved. */
@StringRes
private fun refusal(problem: KeyProblem): Int =
    when (problem) {
        KeyProblem.EMPTY -> R.string.setup_refused_empty
        KeyProblem.BAD_CHARACTERS -> R.string.setup_refused_characters
        KeyProblem.WRONG_SHAPE -> R.string.setup_refused_shape
        KeyProblem.DUPLICATE -> R.string.setup_refused_duplicate
    }
