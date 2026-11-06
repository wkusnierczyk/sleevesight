package app.sleevesight.app.ui.settings

import androidx.annotation.StringRes
import app.sleevesight.app.R
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretState
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.settings.WebEngine

// What saving a key switches on (#763): only what the user chooses after the save, on *Load keys*'
// services to enable or in the key dialog; and what the summary after it lists.

/** The group a key is listed under on *Load keys*, in the order shown. */
internal enum class KeyGroup(
    @StringRes val label: Int,
) {
    AI(R.string.key_group_ai),
    MUSIC(R.string.key_group_music),
    SEARCH(R.string.key_group_search),
}

/** The group of the key [id]'s service. */
internal fun groupOf(id: SecretId): KeyGroup =
    when (id) {
        SecretId.ANTHROPIC, SecretId.OPENAI, SecretId.GOOGLE -> KeyGroup.AI
        SecretId.LASTFM -> KeyGroup.MUSIC
        SecretId.EXA -> KeyGroup.SEARCH
    }

/** The AI service the key [id] is for, or null for a source's key. */
internal fun providerOf(id: SecretId): ProviderId? = ProviderId.entries.firstOrNull { SecretId.forProvider(it) == id }

/**
 * Whether the service of the key [id] is switched on in these settings: the AI service chosen;
 * Last.fm as a source; Exa while web search is on with it, as its row under *Sources* shows.
 */
internal fun Settings.serviceOn(id: SecretId): Boolean =
    when (id) {
        SecretId.ANTHROPIC, SecretId.OPENAI, SecretId.GOOGLE -> aiProvider != null && aiProvider == providerOf(id)
        SecretId.LASTFM -> SourceSwitch.LASTFM in enabledSources
        SecretId.EXA -> SourceSwitch.WEB in enabledSources && WebEngine.EXA in webEngines
    }

/**
 * These settings with the service of the key [id] switched [on]. An AI service switched on becomes
 * the one AI service; switched off, it leaves none only if it was the one. Last.fm on is both a
 * source and a source of genre labels (*Genres*); off, it is off as a source, which its labels
 * need, and its switch under *Genres* is left as it was. Exa on turns web search on with it, as
 * its switch under *Sources* does; off, the engine alone.
 */
internal fun Settings.withService(
    id: SecretId,
    on: Boolean,
): Settings =
    when (id) {
        SecretId.ANTHROPIC, SecretId.OPENAI, SecretId.GOOGLE -> {
            val provider = providerOf(id)
            when {
                on -> copy(aiProvider = provider)
                aiProvider == provider -> copy(aiProvider = null)
                else -> this
            }
        }

        SecretId.LASTFM -> {
            if (on) {
                copy(
                    enabledSources = enabledSources + SourceSwitch.LASTFM,
                    labelSources = labelSources + LabelSource.LASTFM,
                )
            } else {
                copy(enabledSources = enabledSources - SourceSwitch.LASTFM)
            }
        }

        SecretId.EXA -> {
            if (on) {
                copy(enabledSources = enabledSources + SourceSwitch.WEB, webEngines = webEngines + WebEngine.EXA)
            } else {
                copy(webEngines = webEngines - WebEngine.EXA)
            }
        }
    }

/**
 * These settings with the [choices] applied: each service listed switched on or off. The AI
 * services' choices are applied off first, then the one on, so at most one is.
 */
internal fun Settings.applied(choices: Map<SecretId, Boolean>): Settings {
    val (ai, sources) = choices.entries.partition { groupOf(it.key) == KeyGroup.AI }
    var result = this
    for ((id, on) in sources) result = result.withService(id, on)
    for ((id, on) in ai) if (!on) result = result.withService(id, false)
    for ((id, on) in ai) if (on) result = result.withService(id, true)
    return result
}

/**
 * Whether the key [id]'s service was on in [settings], with the keys as [secrets] had them: a
 * service whose key was not saved counts as off, as its switch shows (#667).
 */
internal fun wasOn(
    id: SecretId,
    settings: Settings,
    secrets: Map<SecretId, SecretState>,
): Boolean = keySaved(secrets[id]) && settings.serviceOn(id)

/**
 * *Load keys*' services to enable (#763), once the keys [saved] are: a row for each of them, and,
 * when the load holds an AI key, under *Already configured*, the AI services whose keys were saved
 * before ([configured]) and not in this load. The AI services are shown as radios when more than
 * one of them can be chosen ([radios]), counting an AI service on that needs no key (Ollama),
 * which has no row; else as a checkbox. [initial] is what each row starts as, and [current] the AI
 * service on before, which *Your current AI service is …* names, when it is not in this load.
 */
internal class EnablePlan(
    val saved: List<SecretId>,
    val configured: List<SecretId>,
    val radios: Boolean,
    val initial: Map<SecretId, Boolean>,
    val current: ProviderId?,
)

/**
 * The services to enable once the keys [saved] are, with the keys stored before the load as
 * [before] and the settings as [settings]. A source starts ticked. With no AI key in the load,
 * there are no AI rows. One new AI key, with no AI service set up before (no key saved, and none
 * on that needs no key), starts ticked; otherwise a new AI key starts off, unless it replaces the
 * key of the AI service on, which stays on. Each AI service under *Already configured* starts as it
 * is.
 */
internal fun enablePlan(
    saved: List<SecretId>,
    before: Map<SecretId, SecretState>,
    settings: Settings,
): EnablePlan {
    val ai = SecretId.entries.filter { groupOf(it) == KeyGroup.AI }
    val newAi = saved.filter { it in ai }
    // An AI service on that needs no key (Ollama): set up before, though it has no key or row.
    val keyless = settings.aiProvider?.takeIf { SecretId.forProvider(it) == null }
    val anyBefore = keyless != null || ai.any { keySaved(before[it]) }
    val configured = if (newAi.isEmpty()) emptyList() else ai.filter { it !in saved && keySaved(before[it]) }
    val initial =
        buildMap {
            for (id in saved) {
                put(
                    id,
                    when {
                        id !in ai -> true
                        newAi.size == 1 && !anyBefore -> true
                        else -> wasOn(id, settings, before)
                    },
                )
            }
            for (id in configured) put(id, wasOn(id, settings, before))
        }
    val current = if (newAi.isEmpty()) null else currentAi(settings, configured)
    val choosable = newAi.size + configured.size + (if (keyless != null) 1 else 0)
    return EnablePlan(saved, configured, radios = newAi.isNotEmpty() && choosable > 1, initial, current)
}

/**
 * The AI service on in [settings] that *Your current AI service is …* names: one listed under
 * *Already configured* ([configured]), or one that needs no key; null for none, or for one loaded now.
 */
private fun currentAi(
    settings: Settings,
    configured: List<SecretId>,
): ProviderId? =
    settings.aiProvider?.takeIf { provider ->
        SecretId.forProvider(provider).let { it == null || it in configured }
    }

/**
 * [choices] with the key [id]'s row set [on]: an AI service chosen unchooses any other, as only
 * one can be on.
 */
internal fun Map<SecretId, Boolean>.chosen(
    id: SecretId,
    on: Boolean,
): Map<SecretId, Boolean> =
    mapValues { (other, was) ->
        when {
            other == id -> on
            on && groupOf(id) == KeyGroup.AI && groupOf(other) == KeyGroup.AI -> false
            else -> was
        }
    }

/** A key saved, on the summary: its service's key [id], its [ending], and whether it [replaced] one. */
internal data class SavedKey(
    val id: SecretId,
    val ending: String,
    val replaced: Boolean,
)

/**
 * The summary after a save (#763): the [keys] saved, and the [services] configured, each with
 * whether it is now on: those whose key was saved, and any AI service whose state changed; and
 * the AI service that needs no key (Ollama), on before and now not ([keylessOff]).
 */
internal class KeySummary(
    val keys: List<SavedKey>,
    val services: Map<SecretId, Boolean>,
    val keylessOff: ProviderId? = null,
)

/**
 * The summary of the [keys] saved, the settings [before] the choices (with the keys stored then,
 * [secrets]) and [after] them.
 */
internal fun summaryOf(
    keys: List<SavedKey>,
    before: Settings,
    secrets: Map<SecretId, SecretState>,
    after: Settings,
): KeySummary {
    val saved = keys.map { it.id }
    // A service not saved now is on, before and after, only with its key saved, as its switch shows.
    val changed =
        SecretId.entries
            .filter { id -> id !in saved && groupOf(id) == KeyGroup.AI }
            .filter { id -> wasOn(id, before, secrets) != wasOn(id, after, secrets) }
    val keyless = before.aiProvider?.takeIf { SecretId.forProvider(it) == null && it != after.aiProvider }
    return KeySummary(
        keys,
        saved.associateWith { after.serviceOn(it) } + changed.associateWith { wasOn(it, after, secrets) },
        keyless,
    )
}
