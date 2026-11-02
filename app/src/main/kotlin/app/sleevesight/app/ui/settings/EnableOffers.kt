package app.sleevesight.app.ui.settings

import androidx.annotation.StringRes
import app.sleevesight.app.R
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretState
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.settings.Settings
import java.io.IOException

// What the key dialog offers to switch on for the key it saves (#763), the services' names, and
// how the choices are written.

/**
 * The name of the key [id]'s service, as the services' tables and the key dialog name it: the AI
 * service ("Google"), "Last.fm", "Exa web search".
 */
@StringRes
internal fun serviceName(id: SecretId): Int =
    when (id) {
        SecretId.ANTHROPIC, SecretId.OPENAI, SecretId.GOOGLE -> providerName(requireNotNull(providerOf(id)))
        SecretId.LASTFM -> R.string.settings_source_lastfm
        SecretId.EXA -> R.string.settings_engine_exa
    }

/**
 * The name of the AI service [provider], as *AI services* names it, and Ollama, which needs no key
 * and is not offered there yet, by its own name.
 */
@StringRes
internal fun aiServiceName(provider: ProviderId): Int =
    if (provider == ProviderId.OLLAMA) R.string.settings_ai_provider_ollama else providerName(provider)

/** What the key dialog offers for the key it saves (#763), as [enableOffer] finds it. */
internal sealed interface EnableOffer {
    /** *Enable {service}*, ticked: a source's key, or an AI key while no AI service is on. */
    data object Enable : EnableOffer

    /** *Your current AI service is {current}.* and *Switch the AI service to {service}*, unticked. */
    data class Switch(
        val current: ProviderId,
    ) : EnableOffer

    /** *{service} is already enabled.*: the key of the AI service on, replaced; nothing to choose. */
    data object AlreadyOn : EnableOffer
}

/**
 * What the key dialog for the key [id] offers, with the settings [settings] and the keys
 * [secrets]: an AI service counts as on only while its key is saved, as its switch shows.
 */
internal fun enableOffer(
    id: SecretId,
    settings: Settings,
    secrets: Map<SecretId, SecretState>,
): EnableOffer {
    val provider = providerOf(id) ?: return EnableOffer.Enable
    val current = settings.aiProvider?.takeIf { providerSwitchUsable(it, secrets) }
    return when (current) {
        null -> EnableOffer.Enable
        provider -> EnableOffer.AlreadyOn
        else -> EnableOffer.Switch(current)
    }
}

/** The choices the key dialog's offer makes for the key [id], its checkbox [ticked]. */
internal fun EnableOffer.choices(
    id: SecretId,
    ticked: Boolean,
): Map<SecretId, Boolean> =
    when (this) {
        EnableOffer.Enable -> mapOf(id to ticked)
        is EnableOffer.Switch -> if (ticked) mapOf(id to true) else emptyMap()
        EnableOffer.AlreadyOn -> emptyMap()
    }

/** The settings [before] a change was written to the store, and [after] it, as written. */
internal class Applied(
    val before: Settings,
    val after: Settings,
)

/**
 * Writes the [choices] to the stored settings ([applied]), and returns them as they were and as
 * written; null when the phone's storage failed, and nothing was written.
 */
internal suspend fun SettingsStore.applyChoices(choices: Map<SecretId, Boolean>): Applied? {
    var result: Applied? = null
    return try {
        update { before -> before.applied(choices).also { result = Applied(before, it) } }
        result
    } catch (_: IOException) {
        null
    }
}
