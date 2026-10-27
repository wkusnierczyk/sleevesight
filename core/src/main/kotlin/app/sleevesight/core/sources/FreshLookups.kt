package app.sleevesight.core.sources

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.CoroutineContext

/**
 * The mark of work that looks its sources up afresh (*Look again*, #617): a coroutine context
 * element its children inherit, set by a run that looks again (`RunInput.lookAgain`). Within it, the
 * caches that would answer a lookup without a request read nothing: the retrieval cache (the run
 * itself skips it), the artist cache (`StoreArtistResults`) and MusicBrainz's answers held in memory
 * (`RememberedMusicBrainz`). They still keep what the fresh lookups find, as a usual lookup's, under
 * the same rules (each source's lifetime, *Keep what sources said about each artist*), so the next
 * track's lookups find it.
 *
 * The adapters' own memory, which stands in for the artist cache only where there is none (the CLI
 * without a cache, the tests: [ArtistResults.persists] false), is not bypassed: the app always has
 * the artist cache.
 */
object FreshLookups : CoroutineContext.Element, CoroutineContext.Key<FreshLookups> {
    override val key: CoroutineContext.Key<*> get() = this

    /** Whether the work calling this looks its sources up afresh. */
    @Suppress("RedundantSuspendModifier") // Detekt 1.23.8 does not see `currentCoroutineContext` as suspending; it is.
    suspend fun active(): Boolean = currentCoroutineContext()[FreshLookups] != null
}
