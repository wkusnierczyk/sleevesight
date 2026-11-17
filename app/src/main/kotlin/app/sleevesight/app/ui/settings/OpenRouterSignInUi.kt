package app.sleevesight.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.pipeline.KeyRefusals
import app.sleevesight.app.secrets.OpenRouterLogin
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretState
import app.sleevesight.app.secrets.SignInFailure
import app.sleevesight.app.secrets.SignInStep
import app.sleevesight.app.ui.ExternalLink
import app.sleevesight.app.ui.WebLink
import app.sleevesight.app.ui.logo.Busy
import app.sleevesight.app.ui.openInOwnTask
import app.sleevesight.app.ui.rememberOpenUrl
import app.sleevesight.app.ui.theme.LinkedText
import app.sleevesight.app.ui.theme.PillButton
import app.sleevesight.app.ui.theme.linkedParts
import app.sleevesight.core.model.ProviderId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

// *AI services* → OpenRouter (#1157): the sign-in in place of the link to its key, the key link
// under it; signed in, the key's state, *Manage the key on OpenRouter ↗* and *Sign out*.

/**
 * What OpenRouter's rows show of a sign-in and do with it: where it is ([step]); whether the key
 * saved was refused by OpenRouter since ([refused]); [start], which begins one and gives the page to
 * open; [notOpened], when no browser opened it; and [dismiss], which drops the line of one that did
 * not finish.
 */
internal class OpenRouterSignInUi(
    val step: SignInStep,
    val refused: Boolean,
    val start: () -> String,
    val notOpened: () -> Unit,
    val dismiss: () -> Unit,
)

/** The sign-in with OpenRouter ([login], #1157), and the keys refused since ([refusals]), for the settings. */
class OpenRouterAccess(
    val login: OpenRouterLogin,
    val refusals: KeyRefusals,
)

/** What OpenRouter's rows show of [access]'s sign-in and refusals; null without it (tests). */
@Composable
internal fun openRouterSignIn(access: OpenRouterAccess?): OpenRouterSignInUi? {
    val step by (access?.login?.step ?: NO_SIGN_IN).collectAsState()
    val refused by (access?.refusals?.refused ?: NO_REFUSALS).collectAsState(initial = emptySet())
    return access?.login?.let {
        OpenRouterSignInUi(
            step = step,
            refused = ProviderId.OPENROUTER in refused,
            start = it::start,
            notOpened = it::notOpened,
            dismiss = it::dismiss,
        )
    }
}

// No sign-in, and no key refused, where the screen is shown without them (tests, previews).
private val NO_SIGN_IN: StateFlow<SignInStep> = MutableStateFlow(SignInStep.Idle)
private val NO_REFUSALS: Flow<Set<ProviderId>> = flowOf(emptySet())

/** The sign-in, for OpenRouter's rows; none where the screen is shown without it (tests, previews). */
internal val LocalOpenRouterSignIn = staticCompositionLocalOf<OpenRouterSignInUi?> { null }

/** Where *Manage the key on OpenRouter ↗* leads: the account's keys. */
internal const val OPENROUTER_KEYS = "https://openrouter.ai/settings/keys"

/**
 * OpenRouter's part under its switch, by [key]'s state and the sign-in's ([LocalOpenRouterSignIn]):
 * - while the browser is open, or the key is fetched, that it is (the second with the turning record);
 * - with a key saved and not refused, that it is signed in, *Manage the key on OpenRouter ↗* and
 *   *Sign out* ([SettingsDialog.SignOut]);
 * - otherwise, why a sign-in did not finish, or that the key saved was refused, in the error colour;
 *   *Sign in* (read "Sign in with OpenRouter"), what it does, its terms with their link, and the link to the key
 *   under *Secrets*; and with a key saved, *Sign out*.
 * Nothing before the keys' states are read.
 */
@Composable
internal fun OpenRouterSignInRows(
    key: SecretState?,
    open: (SettingsDialog) -> Unit,
) {
    if (key == null) return
    val ui = LocalOpenRouterSignIn.current
    val step = ui?.step ?: SignInStep.Idle
    val refused = ui?.refused == true && keySaved(key)
    val style = MaterialTheme.typography.bodySmall
    val color = MaterialTheme.colorScheme.onSurface
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag(OPENROUTER_ROWS_TAG)) {
        when {
            step == SignInStep.InBrowser -> {
                Busy(stringResource(R.string.settings_openrouter_waiting), style, color = color, record = false)
            }

            step == SignInStep.Exchanging -> {
                Busy(stringResource(R.string.settings_openrouter_exchanging), style, color = color)
            }

            key == SecretState.SAVED && !refused -> {
                SignedIn(open)
            }

            else -> {
                val failure = (step as? SignInStep.Failed)?.why
                val problem =
                    when {
                        failure != null -> failureLine(failure)
                        refused -> R.string.settings_openrouter_key_refused
                        else -> null
                    }
                problem?.let {
                    Text(
                        stringResource(it),
                        style = style,
                        color = MaterialTheme.colorScheme.error,
                        modifier =
                            Modifier.testTag(OPENROUTER_PROBLEM_TAG).semantics {
                                liveRegion = LiveRegionMode.Polite
                            },
                    )
                }
                SignInOffer(ui)
                if (keySaved(key)) SignOutButton(open)
            }
        }
    }
}

/** The line of a sign-in that did not finish, for [why]. */
@StringRes
internal fun failureLine(why: SignInFailure): Int =
    when (why) {
        SignInFailure.CLOSED -> R.string.settings_openrouter_closed
        SignInFailure.DECLINED -> R.string.settings_openrouter_declined
        SignInFailure.OFFLINE -> R.string.settings_openrouter_offline
        SignInFailure.EXPIRED -> R.string.settings_openrouter_expired
        SignInFailure.REFUSED -> R.string.settings_openrouter_refused
    }

// *Sign in*, a pill as the settings' other actions' (#1387), read "Sign in with OpenRouter"; what it
// does, its terms, and the link to the key under *Secrets*.
@Composable
private fun SignInOffer(ui: OpenRouterSignInUi?) {
    val context = LocalContext.current
    val style = MaterialTheme.typography.bodySmall
    val color = MaterialTheme.colorScheme.onSurface
    PillButton(
        label = stringResource(R.string.settings_openrouter_sign_in),
        named = stringResource(R.string.settings_openrouter_sign_in_named),
        onClick =
            ui?.let {
                {
                    it.dismiss()
                    if (!openInOwnTask(context, it.start())) it.notOpened()
                }
            },
        modifier = Modifier.testTag(OPENROUTER_SIGN_IN_TAG),
    )
    Text(stringResource(R.string.settings_openrouter_sign_in_line), style = style, color = color)
    Text(boldStringResource(R.string.settings_openrouter_terms), style = style, color = color)
    WebLink(OPENROUTER_TERMS)
    val here = stringResource(R.string.settings_openrouter_key_here)
    LinkedText(
        linkedParts(
            stringResource(R.string.settings_openrouter_or_key),
            settingsLink(here, SettingsTarget.key(SecretId.OPENROUTER)),
        ),
        style = style,
        color = color,
    )
}

// Signed in: the key's state, its page on OpenRouter, and *Sign out*.
@Composable
private fun SignedIn(open: (SettingsDialog) -> Unit) {
    val style = MaterialTheme.typography.bodySmall
    Text(
        stringResource(R.string.settings_openrouter_signed_in),
        style = style,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.testTag(OPENROUTER_SIGNED_IN_TAG),
    )
    val manage = stringResource(R.string.settings_openrouter_manage)
    ExternalLink(text = manage, description = manage, onClick = rememberOpenUrl(OPENROUTER_KEYS))
    SignOutButton(open)
}

@Composable
private fun SignOutButton(open: (SettingsDialog) -> Unit) {
    PillButton(
        label = stringResource(R.string.settings_openrouter_sign_out),
        named = stringResource(R.string.settings_openrouter_sign_out_named),
        onClick = { open(SettingsDialog.SignOut) },
        modifier = Modifier.testTag(OPENROUTER_SIGN_OUT_TAG),
    )
}

/** *Sign out of OpenRouter?*: its key deleted from the phone once confirmed. */
@Composable
internal fun signOutConfirmation(): Confirmation =
    Confirmation(
        title = stringResource(R.string.settings_openrouter_sign_out_title),
        explained = stringResource(R.string.settings_openrouter_sign_out_text),
        confirm = stringResource(R.string.settings_openrouter_sign_out),
        named = stringResource(R.string.settings_openrouter_sign_out_named),
        failed = stringResource(R.string.settings_key_remove_failed),
    )

/** The test tags of OpenRouter's part under its switch. */
const val OPENROUTER_ROWS_TAG: String = "openrouter_rows"
const val OPENROUTER_SIGN_IN_TAG: String = "openrouter_sign_in"
const val OPENROUTER_SIGN_OUT_TAG: String = "openrouter_sign_out"
const val OPENROUTER_SIGNED_IN_TAG: String = "openrouter_signed_in"
const val OPENROUTER_PROBLEM_TAG: String = "openrouter_problem"
