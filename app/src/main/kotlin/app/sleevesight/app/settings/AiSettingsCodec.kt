package app.sleevesight.app.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.settings.Settings

/**
 * The AI service's part of [SettingsCodec] (#227), under its rules: only what moved off its default
 * is stored, a value this version cannot read reads as the default, and a write rewrites only what
 * changed.
 *
 * `ai.provider` is the provider switched on. Each provider's model and effort have keys of their
 * own, `ai.model.<PROVIDER>` and `ai.effort.<PROVIDER>`, so a provider switched off keeps them. A
 * provider added later needs no key here: the keys are made from its name.
 *
 * Before, one model and one effort were stored (`ai.model`, `ai.effort`), for the one service
 * chosen. They read as that provider's, or Anthropic's with none chosen, since it was the only one
 * offered ([legacyOwner]), and the first write of any setting moves them to that provider's keys
 * ([migrateLegacy]). A downgrade after that reads the model and the effort as their defaults.
 */
internal object AiSettingsCodec {
    private val PROVIDER = stringPreferencesKey("ai.provider")

    /** The one model stored before each provider had its own: [legacyOwner]'s. */
    private val LEGACY_MODEL = stringPreferencesKey("ai.model")

    /** The one effort stored before each provider had its own: [legacyOwner]'s. */
    private val LEGACY_EFFORT = stringPreferencesKey("ai.effort")

    /** An effort of null is the provider's default, which needs a name of its own to be stored. */
    private const val PROVIDER_DEFAULT_EFFORT = "PROVIDER_DEFAULT"

    private fun modelKey(provider: ProviderId) = stringPreferencesKey("ai.model.${provider.name}")

    private fun effortKey(provider: ProviderId) = stringPreferencesKey("ai.effort.${provider.name}")

    fun readProvider(prefs: Preferences): ProviderId? = enumOf<ProviderId>(prefs[PROVIDER])

    /** Each provider's model, where one is stored; the legacy `ai.model` is [legacyOwner]'s. */
    fun readModels(prefs: Preferences): Map<ProviderId, String> {
        val owner = legacyOwner(prefs)
        return ProviderId.entries
            .mapNotNull { provider ->
                val stored = prefs[modelKey(provider)] ?: prefs[LEGACY_MODEL].takeIf { provider == owner }
                stored?.takeIf { it.isNotBlank() }?.let { provider to it }
            }.toMap()
    }

    /**
     * Each provider's effort, where one is stored that its scale offers, or the provider's default;
     * a level its scale does not offer, or a name this version does not know, reads as its scale's
     * default. The legacy `ai.effort` is [legacyOwner]'s.
     */
    fun readEfforts(prefs: Preferences): Map<ProviderId, ReasoningEffort?> {
        val owner = legacyOwner(prefs)
        return Settings.EFFORT_SCALES.entries
            .fold(Settings()) { read, (provider, scale) ->
                val stored = prefs[effortKey(provider)] ?: prefs[LEGACY_EFFORT].takeIf { provider == owner }
                val level = enumOf<ReasoningEffort>(stored)?.takeIf { it in scale.levels }
                when {
                    stored == PROVIDER_DEFAULT_EFFORT -> read.withEffort(provider, null)
                    level != null -> read.withEffort(provider, level)
                    else -> read
                }
            }.aiEfforts
    }

    /**
     * Stores the AI settings of [settings] that differ from [stored], what [prefs] reads as, after
     * moving the legacy model and effort to their provider's keys.
     */
    fun write(
        settings: Settings,
        stored: Settings,
        prefs: MutablePreferences,
    ) {
        migrateLegacy(stored, prefs)
        if (settings.aiProvider != stored.aiProvider) prefs.put(PROVIDER, settings.aiProvider?.name, null)
        for (provider in ProviderId.entries) {
            if (settings.aiModels[provider] != stored.aiModels[provider]) writeModel(provider, settings, prefs)
            if (settings.effort(provider) != stored.effort(provider)) writeEffort(provider, settings, prefs)
        }
    }

    /**
     * The provider the one model and effort stored before #227 belong to: the one chosen then, or
     * Anthropic with none chosen, the only provider offered until then. An effort could be set with
     * no service chosen, and was then Anthropic's once it was chosen.
     */
    private fun legacyOwner(prefs: Preferences): ProviderId = readProvider(prefs) ?: ProviderId.ANTHROPIC

    private fun writeModel(
        provider: ProviderId,
        settings: Settings,
        prefs: MutablePreferences,
    ) = prefs.put(modelKey(provider), settings.aiModels[provider]?.takeIf { it.isNotBlank() }, null)

    /** [provider]'s effort in [settings], or no key at its scale's default; none without a scale. */
    private fun writeEffort(
        provider: ProviderId,
        settings: Settings,
        prefs: MutablePreferences,
    ) {
        val scale = Settings.EFFORT_SCALES[provider] ?: return
        val effort = settings.effort(provider)?.name ?: PROVIDER_DEFAULT_EFFORT
        prefs.put(effortKey(provider), effort, scale.default?.name ?: PROVIDER_DEFAULT_EFFORT)
    }

    /**
     * Moves the one model and effort stored before #227 to [legacyOwner]'s own keys, as [stored]
     * reads them, and removes them, so the provider's own keys alone hold its choices from then on:
     * left, they would come back as its choice once it was set to its default, which has no key.
     * A key of the provider's own already stored (as after a downgrade and an upgrade again) is
     * kept as it is, a value this version cannot read included: [stored] read it, not the legacy
     * one, so only a key not yet stored is written.
     */
    private fun migrateLegacy(
        stored: Settings,
        prefs: MutablePreferences,
    ) {
        if (LEGACY_MODEL !in prefs && LEGACY_EFFORT !in prefs) return
        val owner = legacyOwner(prefs)
        prefs.remove(LEGACY_MODEL)
        prefs.remove(LEGACY_EFFORT)
        if (modelKey(owner) !in prefs) writeModel(owner, stored, prefs)
        if (effortKey(owner) !in prefs) writeEffort(owner, stored, prefs)
    }
}
