package app.sleevesight.app.pipeline

import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.AiAccess
import app.sleevesight.core.pipeline.PipelineCache
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.pipeline.SessionFacts
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WebEngine
import app.sleevesight.wiring.PipelineWiring
import app.sleevesight.wiring.SourceKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

/**
 * The pipeline's stages in the app, for [PipelineHost]: the adapters, the resolver, MusicBrainz's
 * genre labels and the AI services from [wiring], on the process's one `NetClient`; the caches from
 * [cache], asked for at each build, which follows the first-run acknowledgement. (`cache.db` is
 * opened at its first query: the purge `AppGraph` runs after the acknowledgement, or a lookup.)
 * The adapters are those [PipelineWiring.adapters] builds from the keys and the web engines
 * switched on: Last.fm only with its key, web search only through an engine switched on
 * with its key. The AI access is the chosen service's client with its key, or none when no service
 * is chosen, it has no client yet, or its key is not saved or cannot be read: insights then wait,
 * and the evidence is still shown. They wait too while [models] has the service's selected model
 * found unavailable (#540), which no other model replaces until the user chooses one; and each call
 * through the client is watched for a model the service does not know or refuses
 * ([ModelCheck.watching]). [sessionFacts], the artist facts the listening session's insights cited
 * (#239), is one for the process, passed to every build.
 */
class AppPipelineParts(
    private val wiring: PipelineWiring,
    private val normalizer: Normalizer,
    private val sessionFacts: SessionFacts = SessionFacts.None,
    private val models: ModelCheck? = null,
    private val cache: () -> PipelineCache,
) : (PipelineKeys, Settings) -> PipelineParts {
    override fun invoke(
        keys: PipelineKeys,
        settings: Settings,
    ): PipelineParts =
        PipelineParts(
            normalizer = normalizer,
            adapters = wiring.adapters(keys.sources, settings),
            resolver = wiring.resolver,
            hasher = JvmHasher,
            ai =
                settings.aiProvider
                    ?.takeUnless { models?.blocks(settings) == true }
                    ?.let { wiring.aiProviders[it] }
                    ?.let { p -> keys.ai?.let { AiAccess(models?.watching(p) ?: p, it) } },
            cache = cache(),
            musicBrainzLabels = wiring.musicBrainzLabels,
            lastFmLabels = keys.sources.lastFm?.let(wiring::lastFmLabels),
            sessionFacts = sessionFacts,
        )
}

/**
 * The keys a pipeline for [provider] (the AI service chosen, if any) is built with, as saved: a key
 * not saved, or one that cannot be read, is none.
 */
suspend fun SecretStore.pipelineKeys(provider: ProviderId?): PipelineKeys =
    PipelineKeys(
        ai = provider?.let(SecretId::forProvider)?.let { get(it) },
        sources =
            SourceKeys(
                lastFm = get(SecretId.LASTFM),
                web = listOfNotNull(get(SecretId.EXA)?.let { WebEngine.EXA to it }).toMap(),
            ),
    )

/** This flow, collected only once [acknowledged] is true: nothing is read before the first run. */
fun <T> Flow<T>.afterAcknowledged(acknowledged: Flow<Boolean>): Flow<T> =
    flow {
        acknowledged.first { it }
        emitAll(this@afterAcknowledged)
    }
