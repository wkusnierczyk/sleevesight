package io.github.wkusnierczyk.sleevesight.core.resolution

import io.github.wkusnierczyk.sleevesight.core.model.NormalizedTrack
import io.github.wkusnierczyk.sleevesight.core.model.Outcome
import io.github.wkusnierczyk.sleevesight.core.sources.Credit
import io.github.wkusnierczyk.sleevesight.core.sources.CreditRole
import io.github.wkusnierczyk.sleevesight.core.sources.FirstRelease
import io.github.wkusnierczyk.sleevesight.core.sources.OriginalRecording
import io.github.wkusnierczyk.sleevesight.core.sources.Resolution
import io.github.wkusnierczyk.sleevesight.core.sources.ResolvedArtist
import io.github.wkusnierczyk.sleevesight.core.sources.ResolvedWork
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The thresholds of recording resolution. They decide whether a match is correct, so they are
 * constants, not user settings (driver 6); they start here and are tuned at the Phase 0.5 gate
 * (#46) against the fixture's known cases.
 */
object ResolutionThresholds {
    /** The least [NameMatcher.titleSimilarity] for a candidate's title to count as the track's. */
    const val TITLE_SIMILARITY: Double = 0.85

    /**
     * How far a candidate's length may be from the player's. Remasters and editions differ by a
     * few seconds; a live take, an edit or an extended mix by more.
     */
    val DURATION_TOLERANCE: Duration = 7.seconds
}

/**
 * Entity resolution, steps 1 to 4 (`design/architecture.md`, *Entity resolution*): finds the
 * recording on MusicBrainz, its first release, each credited artist's MusicBrainz id, and whether
 * it is a cover. Deterministic: the same answers from MusicBrainz give the same [Resolution].
 *
 * [resolve] never throws, apart from cancellation: a failed lookup is [Resolution.UNREACHABLE], so
 * the pipeline can complete the adapters' `Deferred<Resolution>` normally.
 */
class RecordingResolver(
    private val musicBrainz: MusicBrainzLookup,
    private val names: NameMatcher,
) {
    /** Resolves [track], whose length the player reports as [duration] (null when it does not). */
    suspend fun resolve(
        track: NormalizedTrack,
        duration: Duration?,
    ): Resolution {
        val search =
            if (track.title.isBlank() ||
                track.primary.isBlank()
            ) {
                null
            } else {
                musicBrainz.searchRecordings(track.title, track.primary)
            }
        return when (search) {
            null, is Lookup.NotFound -> Resolution.NONE
            is Lookup.Failed -> Resolution.UNREACHABLE
            is Lookup.Found -> choose(track, duration, search.value)
        }
    }

    private suspend fun choose(
        track: NormalizedTrack,
        duration: Duration?,
        found: List<MbRecording>,
    ): Resolution {
        val candidates = found.map { candidate(track, duration, it) }
        val accepted = candidates.filter { it.titleMatches && it.durationMatches && it.creditedArtists > 0 }
        val chosen = accepted.minWithOrNull(PREFERENCE)
        return if (chosen == null) unmatched(track, candidates) else accept(track, chosen.recording)
    }

    private fun candidate(
        track: NormalizedTrack,
        duration: Duration?,
        recording: MbRecording,
    ): Candidate {
        val length = recording.lengthMs?.milliseconds
        val gap = if (duration == null || length == null) null else (length - duration).absoluteValue
        return Candidate(
            recording = recording,
            titleSimilarity = names.titleSimilarity(track.title, recording.title),
            creditedArtists = track.artists.count { artist -> recording.credits.any { credits(it, artist) } },
            durationGap = gap,
        )
    }

    // A near miss: the title and length fit, but no credited artist does.
    private fun unmatched(
        track: NormalizedTrack,
        candidates: List<Candidate>,
    ): Resolution {
        val conflicting =
            candidates.firstOrNull { it.titleMatches && it.durationMatches && it.creditedArtists == 0 }
                ?: return Resolution.NONE
        val credit = creditString(conflicting.recording.credits)
        return Resolution(
            outcome =
                Outcome.Rejected(
                    "MusicBrainz has “${conflicting.recording.title}” by $credit, not by ${track.primary}",
                ),
        )
    }

    private suspend fun accept(
        track: NormalizedTrack,
        recording: MbRecording,
    ): Resolution {
        val base =
            Resolution(
                outcome = Outcome.SongArticle,
                recordingMbid = recording.mbid,
                title = recording.title,
                artistCredit = creditString(recording.credits),
                artists =
                    track.artists.map { artist ->
                        ResolvedArtist(artist, artistMbid(artist, recording.credits, emptyList()))
                    },
                firstRelease = firstRelease(recording),
            )
        val detail = (musicBrainz.recording(recording.mbid) as? Lookup.Found)?.value
        return if (detail == null) base else withDetail(track, recording, base, detail)
    }

    private suspend fun withDetail(
        track: NormalizedTrack,
        recording: MbRecording,
        base: Resolution,
        detail: MbRecordingDetail,
    ): Resolution {
        val artists =
            track.artists.map { artist ->
                ResolvedArtist(artist, artistMbid(artist, recording.credits, detail.artistRelations))
            }
        val recordingCredits = recordingCredits(detail.artistRelations)
        val link = detail.works.firstOrNull()
        val work = link?.let { (musicBrainz.work(it.mbid) as? Lookup.Found)?.value }
        val withRecording =
            base.copy(artists = artists, credits = recordingCredits, wikidataIds = detail.wikidataIds)
        return if (link == null || work == null) {
            withRecording.copy(work = link?.let { ResolvedWork(it.mbid, it.title) }, cover = link?.cover ?: false)
        } else {
            withWork(recording, withRecording, link, work)
        }
    }

    private fun withWork(
        recording: MbRecording,
        withRecording: Resolution,
        link: MbWorkLink,
        work: MbWork,
    ): Resolution {
        val ours = recording.credits.map { it.artistMbid }.toSet()
        val recorded = work.performances.firstOrNull { it.recordingMbid == recording.mbid }?.date
        val ourDate = listOfNotNull(recorded, recording.firstReleaseDate).minOrNull()
        val original = originalOf(work, ours)
        // An earlier recording by someone else makes this one a version, whether or not MusicBrainz
        // marks it as a cover; with no date of our own, only the mark does.
        val predates = original != null && (ourDate == null || isEarlier(original.date, ourDate))
        val cover = link.cover || (original != null && ourDate != null && predates)
        return withRecording.copy(
            work = ResolvedWork(work.mbid, work.title),
            cover = cover,
            original = original?.takeIf { cover && predates },
            credits = workCredits(work.artistRelations) + withRecording.credits,
            wikidataIds = (withRecording.wikidataIds + work.wikidataIds).distinct(),
        )
    }

    private fun credits(
        credit: MbCredit,
        artist: String,
    ): Boolean = names.sameName(credit.name, artist) || names.sameName(credit.artistName, artist)

    // The credit first; else a performer relationship of the recording ("Them" is credited, Van Morrison sings).
    private fun artistMbid(
        artist: String,
        credits: List<MbCredit>,
        relations: List<MbArtistRelation>,
    ): String? =
        credits.firstOrNull { credits(it, artist) }?.artistMbid
            ?: relations.firstOrNull { it.type in PERFORMER_TYPES && names.sameName(it.artistName, artist) }?.artistMbid

    private data class Candidate(
        val recording: MbRecording,
        val titleSimilarity: Double,
        val creditedArtists: Int,
        val durationGap: Duration?,
    ) {
        val titleMatches: Boolean get() = titleSimilarity >= ResolutionThresholds.TITLE_SIMILARITY
        val durationMatches: Boolean get() =
            durationGap == null ||
                durationGap <= ResolutionThresholds.DURATION_TOLERANCE
    }

    companion object {
        /** MusicBrainz's relationship types that name someone performing on a recording. */
        val PERFORMER_TYPES: Set<String> =
            setOf("vocal", "instrument", "performer", "performing orchestra", "conductor")

        private val WORK_ROLES =
            mapOf(
                "composer" to CreditRole.COMPOSER,
                "lyricist" to CreditRole.LYRICIST,
                "writer" to CreditRole.WRITER,
            )

        // Of the accepted candidates: the earliest released (the original recording rather than a
        // later remaster of it), then the closest title, the closest length, the most credited
        // artists, and the id, so the choice never depends on the search's order.
        private val PREFERENCE: Comparator<Candidate> =
            compareBy<Candidate> { it.recording.firstReleaseDate ?: UNDATED }
                .thenByDescending { it.titleSimilarity }
                .thenBy { it.durationGap ?: Duration.INFINITE }
                .thenByDescending { it.creditedArtists }
                .thenBy { it.recording.mbid }

        // Sorts after every date MusicBrainz gives.
        private const val UNDATED = "9999"

        /** MusicBrainz's credit as it writes it: the names with their join phrases (`A feat. B`). */
        fun creditString(credits: List<MbCredit>): String = credits.joinToString("") { it.name + it.joinPhrase }.trim()

        /**
         * Whether date [a] is before date [b], compared at the precision both have: `1975` is
         * before `1978-09-18`, `1978` is not before `1978-09-18`.
         */
        fun isEarlier(
            a: String,
            b: String,
        ): Boolean {
            val n = minOf(a.length, b.length)
            return a.take(n) < b.take(n)
        }

        private fun firstRelease(recording: MbRecording): FirstRelease? {
            val dated = recording.releases.mapNotNull { release -> release.date?.let { it to release } }
            val first =
                dated.filter { it.first == recording.firstReleaseDate }.minByOrNull { it.second.mbid }
                    ?: dated.minWithOrNull(compareBy<Pair<String, MbRelease>> { it.first }.thenBy { it.second.mbid })
            return first?.let { (date, release) ->
                FirstRelease(
                    date = date,
                    title = release.releaseGroup?.title ?: release.title,
                    releaseGroupMbid = release.releaseGroup?.mbid,
                    releaseMbid = release.mbid,
                    type = release.releaseGroup?.primaryType,
                )
            }
        }

        // The work's earliest dated recording by an artist not credited on ours.
        private fun originalOf(
            work: MbWork,
            ours: Set<String>,
        ): OriginalRecording? =
            work.performances
                .filter { it.credits.isNotEmpty() && it.credits.none { c -> c.artistMbid in ours } }
                .mapNotNull { performance -> performance.date?.let { it to performance } }
                .minWithOrNull(compareBy<Pair<String, MbPerformance>> { it.first }.thenBy { it.second.recordingMbid })
                ?.let { (date, performance) ->
                    OriginalRecording(
                        recordingMbid = performance.recordingMbid,
                        title = performance.title,
                        artists = performance.credits.map { ResolvedArtist(it.artistName, it.artistMbid) },
                        artistCredit = creditString(performance.credits),
                        date = date,
                    )
                }

        private fun workCredits(relations: List<MbArtistRelation>): List<Credit> =
            merge(relations.mapNotNull { r -> WORK_ROLES[r.type]?.let { Credit(it, r.artistName, r.artistMbid) } })

        private fun recordingCredits(relations: List<MbArtistRelation>): List<Credit> {
            val producers =
                relations
                    .filter {
                        it.type == "producer"
                    }.map { Credit(CreditRole.PRODUCER, it.artistName, it.artistMbid) }
            val performers =
                relations
                    .filter { it.type in PERFORMER_TYPES }
                    .map { Credit(CreditRole.PERFORMER, it.artistName, it.artistMbid, performerDetail(it)) }
            return merge(producers + performers)
        }

        // `vocal` with no attribute is plain vocals; an instrument relationship names its instrument.
        private fun performerDetail(relation: MbArtistRelation): List<String> =
            relation.attributes.ifEmpty { if (relation.type == "vocal") listOf("vocals") else emptyList() }

        // One credit per person and role, its details in order and without repeats.
        private fun merge(credits: List<Credit>): List<Credit> =
            credits
                .groupBy { it.role to it.mbid }
                .values
                .map { same -> same.first().copy(detail = same.flatMap { it.detail }.distinct()) }
    }
}
