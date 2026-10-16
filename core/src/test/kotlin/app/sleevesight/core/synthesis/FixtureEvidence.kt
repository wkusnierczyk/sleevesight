package app.sleevesight.core.synthesis

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.Settings
import app.sleevesight.fixtures.BelowSoundtrack
import kotlin.time.Instant

/**
 * The evidence the simulator assembles for a fixture track, from its frozen lookups: its
 * `querySource` for Wikipedia, Last.fm and web search, and the player's release line of its
 * `runPipeline`, in the same words.
 */
object FixtureEvidence {
    private val retrieved = Instant.parse("2026-10-14T00:00:00Z")

    fun nowPlaying(n: Int): NowPlaying {
        val track = BelowSoundtrack.track(n)
        return NowPlaying(
            title = track.title,
            artists = track.artist,
            album = BelowSoundtrack.albumFor(n).album,
            player = Settings.SPOTIFY,
        )
    }

    fun of(n: Int): EvidenceSet = EvidenceSet.of(drafts(n), JvmHasher)

    private fun drafts(n: Int): List<EvidenceDraft> = player(n) + wikipedia(n) + lastFm(n) + web(n)

    private fun draft(
        source: SourceId,
        scope: Scope,
        text: String,
        url: String?,
    ) = EvidenceDraft(source, scope, text, url, retrieved)

    private fun player(n: Int): List<EvidenceDraft> {
        val album = BelowSoundtrack.albumFor(n)
        val name = album.album ?: return emptyList()
        val year = album.year?.let { " ($it)" } ?: ""
        return listOf(
            draft(SourceId.PLAYER, Scope.RELEASE, "This recording is on the release \"$name\"$year.", album.albumUrl),
        )
    }

    private fun wikipedia(n: Int): List<EvidenceDraft> {
        val wikipedia = BelowSoundtrack.factsFor(n).wikipedia
        val scope =
            when (wikipedia.status) {
                "mismatch" -> return emptyList()
                "song_article" -> Scope.SONG
                "original_song" -> Scope.ORIGINAL_SONG
                else -> Scope.ARTIST
            }
        return wikipedia.facts.map { draft(SourceId.WIKIPEDIA, scope, it, wikipedia.url) }
    }

    private fun lastFm(n: Int): List<EvidenceDraft> {
        val lastFm = BelowSoundtrack.factsFor(n).lastFm
        if (lastFm.status == "not_found") return emptyList()
        val lines =
            lastFm.facts +
                listOfNotNull(
                    lastFm.listeners?.let { "Last.fm counts $it listeners for this artist." },
                    lastFm.tags.takeIf { it.isNotEmpty() }?.let { "Last.fm tags the artist as: ${it.joinToString()}." },
                    lastFm.similar.takeIf { it.isNotEmpty() }?.let {
                        "Last.fm lists similar artists: ${it.joinToString()}."
                    },
                )
        return lines.map { draft(SourceId.LASTFM, Scope.ARTIST, it, lastFm.url) }
    }

    private fun web(n: Int): List<EvidenceDraft> =
        BelowSoundtrack.factsFor(n).web.results.flatMap { result ->
            result.facts.map { draft(SourceId.WEB, Scope.WEB, it, result.url) }
        }
}
