package app.sleevesight.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretState
import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.settings.Settings

// *AI services* (#227, #444): one row per provider offered, with its model and effort; the keys are
// under *Secrets*.

/**
 * The providers offered, in the order shown: those with a client in `:ai`, Anthropic, OpenAI
 * (#228) and Google (#229). Another provider joins with its client, and needs nothing more here
 * than its entry, its key ([SecretId.forProvider]) and its names ([providerName], [keyName],
 * [keyRecipient]): its rows, model and effort come from `Settings` ([Settings.DEFAULT_MODELS],
 * [Settings.EFFORT_SCALES]).
 */
internal val OFFERED_PROVIDERS: List<ProviderId> = listOf(ProviderId.ANTHROPIC, ProviderId.OPENAI, ProviderId.GOOGLE)

/**
 * The AI services: per provider offered, a switch with its badge ([ServiceBadge]), and under it
 * its model, by name ([modelName]), and its reasoning effort. The switches are exclusive: switching
 * one on switches the one on before off, and with all off no insight is written. A provider's
 * model and effort can be set while it is off, and stay when it is switched off, so switching back
 * restores them. Its key is under *Secrets*, which a link under the switch leads to ([KeyLink]);
 * switched on without it, the provider says so. A provider whose selected model was found
 * unavailable ([unavailable], by provider; #540) says so under its model, and asks for another.
 */
@Suppress("LongParameterList") // What the rows show, and what they change.
@Composable
internal fun AiSection(
    settings: Settings,
    secrets: Map<SecretId, SecretState>,
    modelName: (String) -> String,
    update: Update,
    open: (SettingsDialog) -> Unit,
    unavailable: Map<ProviderId, String> = emptyMap(),
) {
    for (provider in OFFERED_PROVIDERS) {
        val gone = settings.model(provider)?.takeIf { unavailable[provider] == it }
        ProviderRows(provider, settings, secrets, modelName, update, open, gone)
    }
}

/** [provider]'s switch, model and effort; [gone] its selected model, when found unavailable. */
@Suppress("LongParameterList") // The provider, what the rows show, and what they change.
@Composable
private fun ProviderRows(
    provider: ProviderId,
    settings: Settings,
    secrets: Map<SecretId, SecretState>,
    modelName: (String) -> String,
    update: Update,
    open: (SettingsDialog) -> Unit,
    gone: String?,
) {
    val on = settings.aiProvider == provider
    val keyId = SecretId.forProvider(provider)
    val name = stringResource(providerName(provider))
    SwitchRow(
        title = name,
        // Switched on, it says when its key is missing or cannot be read: insights wait for it.
        summary = keyId?.let { keyNote(it, secrets, linked = true) }.takeIf { on },
        checked = on,
        leading = { ServiceBadge(providerBadge(name)) },
        below = keyId?.let { { KeyLink(it) } },
    ) { switchedOn ->
        update {
            when {
                switchedOn -> it.copy(aiProvider = provider)
                it.aiProvider == provider -> it.copy(aiProvider = null)
                else -> it
            }
        }
    }
    val default = Settings.DEFAULT_MODELS[provider]
    val chosen = settings.aiModels[provider]
    ValueRow(
        // Each row and dialog names its provider: with several providers, a screen reader reads
        // each row on its own.
        title = stringResource(R.string.settings_ai_model_of, name),
        value =
            when {
                chosen != null && chosen != default -> modelName(chosen)
                default != null -> stringResource(R.string.settings_ai_model_default, modelName(default))
                else -> ""
            },
        summary = gone?.let { stringResource(R.string.settings_model_not_available, name, modelName(it)) },
        underBadge = true,
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
        underBadge = true,
    ) {
        open(
            SettingsDialog.Choice(R.string.settings_ai_effort_of, efforts, current, titleArg = name) { effort ->
                update { it.withEffort(provider, effort) }
            },
        )
    }
}
