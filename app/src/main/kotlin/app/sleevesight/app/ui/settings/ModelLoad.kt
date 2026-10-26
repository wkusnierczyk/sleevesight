package app.sleevesight.app.ui.settings

import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.ai.ModelInfo
import app.sleevesight.core.ai.ModelList
import app.sleevesight.core.model.ProviderId

/** Where the model list stands in [ModelDialog]. */
internal sealed interface ModelLoad {
    data object Loading : ModelLoad

    /** No client for the service in this build, so its models cannot be listed. */
    data object Unavailable : ModelLoad

    data object NoKey : ModelLoad

    data class Listed(
        val models: List<ModelInfo>,
    ) : ModelLoad

    data class Failed(
        val kind: AiErrorKind,
    ) : ModelLoad
}

/**
 * The models of [provider] as its service lists them for the saved key ([AiProvider.models]), or
 * why there are none. The key goes only to the service's own client, and is read only when there
 * is one, so *Unavailable* does not wait for the key store. A list had is also told to [heard],
 * with the key it was made with (for `ModelCheck.listed`, #540).
 */
internal suspend fun loadModels(
    provider: ProviderId,
    secrets: SecretStore,
    providers: Map<ProviderId, AiProvider>,
    heard: suspend (Secret, List<ModelInfo>) -> Unit = { _, _ -> },
): ModelLoad {
    val client = providers[provider] ?: return ModelLoad.Unavailable
    val key = SecretId.forProvider(provider)?.let { secrets.get(it) }
    return when (val list = key?.let { client.models(it) }) {
        null -> ModelLoad.NoKey
        is ModelList.Listed -> ModelLoad.Listed(list.models).also { heard(checkNotNull(key), list.models) }
        is ModelList.Failed -> ModelLoad.Failed(list.kind)
    }
}
