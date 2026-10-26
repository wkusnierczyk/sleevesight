package app.sleevesight.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretState
import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.settings.Crossfade
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.settings.WebEngine
import kotlin.time.Duration

@Composable
internal fun daysLabel(lifetime: Duration): String {
    val days = lifetime.inWholeDays.toInt()
    return pluralStringResource(R.plurals.settings_days, days, days)
}

@Composable
internal fun crossfadeLabel(crossfade: Crossfade): String =
    when (crossfade) {
        Crossfade.Autodetect -> stringResource(R.string.settings_crossfade_auto)
        is Crossfade.Fixed ->
            stringResource(
                R.string.settings_crossfade_seconds,
                crossfade.value.inWholeSeconds.toInt(),
            )
    }

/** What the screen says of a stored key: whether there is one, never what it is. */
@Composable
internal fun keyState(state: SecretState?): String =
    stringResource(
        when (state ?: SecretState.ABSENT) {
            SecretState.ABSENT -> R.string.settings_key_absent
            SecretState.SAVED -> R.string.settings_key_saved
            SecretState.UNREADABLE -> R.string.settings_key_unreadable
        },
    )

/** The name of a service offered; only those with a client are. */
@StringRes
internal fun providerName(provider: ProviderId): Int =
    when (provider) {
        ProviderId.ANTHROPIC -> R.string.settings_ai_provider_anthropic
        ProviderId.OPENAI -> R.string.settings_ai_provider_openai
        ProviderId.GOOGLE -> R.string.settings_ai_provider_google
        ProviderId.OLLAMA -> error("$provider is not offered yet")
    }

/** The name of a reasoning-effort level, as the settings offer it. */
@Composable
internal fun effortLabel(effort: ReasoningEffort): String =
    stringResource(
        when (effort) {
            ReasoningEffort.MINIMAL -> R.string.settings_effort_minimal
            ReasoningEffort.LOW -> R.string.settings_effort_low
            ReasoningEffort.MEDIUM -> R.string.settings_effort_medium
            ReasoningEffort.HIGH -> R.string.settings_effort_high
        },
    )

@StringRes
internal fun keyName(id: SecretId): Int =
    when (id) {
        SecretId.ANTHROPIC -> R.string.settings_key_name_anthropic
        SecretId.OPENAI -> R.string.settings_key_name_openai
        SecretId.GOOGLE -> R.string.settings_key_name_google
        SecretId.LASTFM -> R.string.settings_key_name_lastfm
        SecretId.EXA -> R.string.settings_key_name_exa
    }

/** A key's service, as the key screens' tables name it ([KeyTable], #542): "Anthropic", "Google Gemini". */
@StringRes
internal fun keyService(id: SecretId): Int =
    when (id) {
        SecretId.ANTHROPIC -> R.string.settings_ai_provider_anthropic
        SecretId.OPENAI -> R.string.settings_ai_provider_openai
        SecretId.GOOGLE -> R.string.key_service_google
        SecretId.LASTFM -> R.string.settings_source_lastfm
        SecretId.EXA -> R.string.settings_key_recipient_exa
    }

/** Who a key is sent to. */
@StringRes
internal fun keyRecipient(id: SecretId): Int =
    when (id) {
        SecretId.ANTHROPIC -> R.string.settings_ai_provider_anthropic
        SecretId.OPENAI -> R.string.settings_ai_provider_openai
        SecretId.GOOGLE -> R.string.settings_ai_provider_google
        SecretId.LASTFM -> R.string.settings_source_lastfm
        SecretId.EXA -> R.string.settings_key_recipient_exa
    }

/** The key a source needs, or null for one that needs none; web search's are its engines'. */
internal fun sourceKey(switch: SourceSwitch): SecretId? =
    when (switch) {
        SourceSwitch.LASTFM -> SecretId.LASTFM
        SourceSwitch.MUSICBRAINZ,
        SourceSwitch.WIKIPEDIA,
        SourceSwitch.DISCOGS,
        SourceSwitch.WEB,
        SourceSwitch.MODEL,
        -> null
    }

/** The key a web search engine needs. */
internal fun engineKey(engine: WebEngine): SecretId =
    when (engine) {
        WebEngine.EXA -> SecretId.EXA
    }
