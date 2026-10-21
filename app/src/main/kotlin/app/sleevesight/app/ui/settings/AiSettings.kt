package app.sleevesight.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretState
import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.settings.Settings

// *AI service* (#227): one row per provider offered, as *Web search* has one per engine.

/**
 * The providers offered, in the order shown: those with a client in `:ai`, Anthropic, OpenAI
 * (#228) and Google (#229). Another provider joins with its client, and needs nothing more here
 * than its entry, its key ([SecretId.forProvider]) and its names ([providerName], [keyName],
 * [keyRecipient]): its rows, model and effort come from `Settings` ([Settings.DEFAULT_MODELS],
 * [Settings.EFFORT_SCALES]).
 */
internal val OFFERED_PROVIDERS: List<ProviderId> = listOf(ProviderId.ANTHROPIC, ProviderId.OPENAI, ProviderId.GOOGLE)

/**
 * The AI services: per provider offered, a switch, and under it its key, its model and its
 * reasoning effort. The switches are exclusive: switching one on switches the one on before off,
 * and with all off no insight is written. A provider's key, model and effort can be set while it
 * is off, and stay when it is switched off, so switching back restores them.
 */
@Composable
internal fun AiSection(
    settings: Settings,
    secrets: Map<SecretId, SecretState>,
    update: Update,
    open: (SettingsDialog) -> Unit,
) {
    SectionHeading(R.string.settings_section_ai)
    Hint(R.string.settings_ai_providers_hint)
    for (provider in OFFERED_PROVIDERS) ProviderRows(provider, settings, secrets, update, open)
}

/** [provider]'s switch, key, model and effort. */
@Composable
private fun ProviderRows(
    provider: ProviderId,
    settings: Settings,
    secrets: Map<SecretId, SecretState>,
    update: Update,
    open: (SettingsDialog) -> Unit,
) {
    val on = settings.aiProvider == provider
    val keyId = SecretId.forProvider(provider)
    // Switched on, it says when its key is missing or cannot be read: insights wait for it. Not
    // before the keys' states are read, which would show it, for a moment, beside a saved key.
    val needsKey = keyId != null && secrets[keyId].let { it != null && it != SecretState.SAVED }
    val name = stringResource(providerName(provider))
    SwitchRow(
        title = name,
        summary = stringResource(R.string.settings_ai_provider_needs_key).takeIf { on && needsKey },
        checked = on,
    ) { switchedOn ->
        update {
            when {
                switchedOn -> it.copy(aiProvider = provider)
                it.aiProvider == provider -> it.copy(aiProvider = null)
                else -> it
            }
        }
    }
    keyId?.let { KeyRow(stringResource(keyName(it)), it, secrets, indent = true, open) }
    val default = Settings.DEFAULT_MODELS[provider]?.let { stringResource(R.string.settings_ai_model_default, it) }
    ValueRow(
        // Each row and dialog names its provider, as the key row does: with several providers,
        // a screen reader reads each row on its own.
        title = stringResource(R.string.settings_ai_model_of, name),
        value = settings.aiModels[provider] ?: default.orEmpty(),
        indent = true,
        onClick = { open(SettingsDialog.Model(provider)) },
    )
    EffortRow(provider, name, settings, update, open)
}

/** [provider]'s reasoning effort, from its own scale, or the provider's default; none without a scale. */
@Composable
private fun EffortRow(
    provider: ProviderId,
    name: String,
    settings: Settings,
    update: Update,
    open: (SettingsDialog) -> Unit,
) {
    val scale = Settings.EFFORT_SCALES[provider] ?: return
    val efforts =
        scale.levels.map<ReasoningEffort, Pair<ReasoningEffort?, String>> { it to effortLabel(it) } +
            (null to stringResource(R.string.settings_effort_provider_default))
    val current = settings.effort(provider)
    ValueRow(
        title = stringResource(R.string.settings_ai_effort_of, name),
        value = efforts.first { it.first == current }.second,
        summary = stringResource(R.string.settings_ai_effort_summary),
        indent = true,
    ) {
        open(
            SettingsDialog.Choice(R.string.settings_ai_effort_of, efforts, current, titleArg = name) { effort ->
                update { it.withEffort(provider, effort) }
            },
        )
    }
}
