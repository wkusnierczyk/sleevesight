package app.sleevesight.app.ui.settings

import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretStore
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
 * why there are none. The key goes only to the service's own client.
 */
internal suspend fun loadModels(
    provider: ProviderId,
    secrets: SecretStore,
    providers: Map<ProviderId, AiProvider>,
): ModelLoad {
    val client = providers[provider]
    val key = SecretId.forProvider(provider)?.let { secrets.get(it) }
    return when {
        client == null -> {
            ModelLoad.Unavailable
        }

        key == null -> {
            ModelLoad.NoKey
        }

        else -> {
            when (val list = client.models(key)) {
                is ModelList.Listed -> ModelLoad.Listed(list.models)
                is ModelList.Failed -> ModelLoad.Failed(list.kind)
            }
        }
    }
}
