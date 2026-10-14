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
        val searched = found.map { candidate(track, duration, it) }
        val leaders = Leaders(musicBrainz, names)
        val confirmed =
            leaders.confirm(searched.filter { it.titleMatches && it.durationMatches && it.led.isNotEmpty() })
                ?: return Resolution.UNREACHABLE
        val candidates = searched.map { it.identified(confirmed) }
        // Every candidate whose title and length fit and that names one of the track's artists, by
        // name or as a band's leader, confirmed or not: the same-name check weighs them all.
        val fitting = candidates.filter { it.titleMatches && it.durationMatches && it.ids.isNotEmpty() }
        val accepted = narrowed(fitting.filter { it.creditedArtists > 0 }, duration)
        val ambiguous = ambiguousArtist(track, (narrowed(fitting, duration) + accepted).distinct())
        val chosen = accepted.minWithOrNull(PREFERENCE)
        return when {
            ambiguous != null ->
                Resolution(
                    outcome =
                        Outcome.Rejected(
                            "several MusicBrainz artists named $ambiguous have a matching recording",
                        ),
                )
            chosen == null -> unmatched(track, candidates)
            else -> accept(track, chosen, leaders.details[chosen.recording.mbid])
        }
    }

    // A credited name that the candidates, after the length has narrowed them, give to more than one
    // MusicBrainz artist cannot be resolved: it is a same-name collision. A band credit counts as
    // every leader of that name found on the band's recordings, or, with none found, as an unknown
    // artist: a namesake's solo recording beside the real leader's band is a collision, whether or
    // not MusicBrainz shows the leader.
    private fun ambiguousArtist(
        track: NormalizedTrack,
        pool: List<Candidate>,
    ): String? =
        track.artists.firstOrNull { artist ->
            pool.flatMap { it.ids[artist].orEmpty() }.toSet().size > 1
        }

    private fun candidate(
        track: NormalizedTrack,
        duration: Duration?,
        recording: MbRecording,
    ): Candidate {
        val length = recording.lengthMs?.milliseconds
        val gap = if (duration == null || length == null) null else (length - duration).absoluteValue

        // Every matching credit's id, so two same-name artists on one recording are both seen.
        fun matching(byName: Boolean): Map<String, Set<String>> =
            track.artists
                .associateWith { artist ->
                    recording.credits
                        .filter { credits(it, artist, byName) }
                        .map { it.artistMbid }
                        .toSet()
                }.filterValues { it.isNotEmpty() }
        val named = matching(byName = true)
        return Candidate(
            recording = recording,
            titleSimilarity = names.titleSimilarity(track.title, recording.title),
            named = named,
            led = matching(byName = false).filterKeys { it !in named },
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
        chosen: Candidate,
        fetched: MbRecordingDetail?,
    ): Resolution {
        val recording = chosen.recording
        // The artist's id as the choice established it, for a leader confirmed on another of the band's recordings.
        val known =
            chosen.ids.mapValues { (_, ids) ->
                ids.singleOrNull()?.takeUnless { it.startsWith(Candidate.BAND) }
            }
        val base =
            Resolution(
                outcome = Outcome.SongArticle,
                recordingMbid = recording.mbid,
                title = recording.title,
                artistCredit = creditString(recording.credits),
                artists =
                    track.artists.map { artist ->
                        ResolvedArtist(artist, artistMbid(artist, recording.credits, emptyList(), known[artist]))
                    },
                firstRelease = firstRelease(recording),
            )
        val detail = fetched ?: (musicBrainz.recording(recording.mbid) as? Lookup.Found)?.value
        return if (detail == null) base else withDetail(track, recording, base, detail, known)
    }

    private suspend fun withDetail(
        track: NormalizedTrack,
        recording: MbRecording,
        base: Resolution,
        detail: MbRecordingDetail,
        known: Map<String, String?>,
    ): Resolution {
        val artists =
            track.artists.map { artist ->
                ResolvedArtist(artist, artistMbid(artist, recording.credits, detail.artistRelations, known[artist]))
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
        // Our artists' recordings of the work, the chosen one among them: the search finds only some,
        // and MusicBrainz leaves many undated, so the chosen one need not be their first.
        val own = work.performances.filter { performance -> performance.credits.any { it.artistMbid in ours } }
        // The chosen recording's release date stands in for its missing recording date; with neither,
        // it is as undated as any other.
        val ownUndated =
            own.any {
                it.date == null &&
                    (it.recordingMbid != recording.mbid || recording.firstReleaseDate == null)
            }
        val ourEarliest = (own.mapNotNull { it.date } + listOfNotNull(recording.firstReleaseDate)).minOrNull()
        val original = originalOf(work, ours)
        // Someone else's recording counts as earlier only when it predates every dated one of ours.
        val predates = original != null && ourEarliest != null && isEarlier(original.date, ourEarliest)
        // From dates, a cover only when all our artists' recordings of the work are dated, since an
        // undated one may be the original; otherwise MusicBrainz's cover mark alone decides.
        val cover = link.cover || (predates && !ownUndated)
        val named = cover && (predates || (link.cover && ourEarliest == null))
        // Ours first: before every recording by others that MusicBrainz dates, of which there is one.
        val earliest = original != null && ourEarliest != null && isEarlier(ourEarliest, original.date)
        return withRecording.copy(
            work = ResolvedWork(work.mbid, work.title),
            cover = cover,
            earliest = earliest,
            original = original?.takeIf { named },
            credits = workCredits(work.artistRelations) + withRecording.credits,
            wikidataIds = (withRecording.wikidataIds + work.wikidataIds).distinct(),
        )
    }

    // Whether [credit] is [artist]'s, as written on the recording or as MusicBrainz names the artist: by
    // name, or, unless [byName], a band they lead ("Dick Dale and His Del-Tones", #77).
    private fun credits(
        credit: MbCredit,
        artist: String,
        byName: Boolean = false,
    ): Boolean =
        listOf(credit.name, credit.artistName).any {
            names.sameName(it, artist) || (!byName && names.leads(it, artist))
        }

    // The credit by name first; else a performer relationship of the recording ("Them" is credited, Van
    // Morrison sings); else [known], the id the choice established (a band's leader confirmed on another
    // of its recordings). A band the artist leads is not the artist, so its id is never theirs.
    private fun artistMbid(
        artist: String,
        credits: List<MbCredit>,
        relations: List<MbArtistRelation>,
        known: String?,
    ): String? =
        credits.firstOrNull { credits(it, artist, byName = true) }?.artistMbid
            ?: relations.firstOrNull { it.type in PERFORMER_TYPES && names.sameName(it.artistName, artist) }?.artistMbid
            ?: known

    /**
     * A search result as a candidate: [named] maps each track artist it credits by name to the
     * credits' MusicBrainz ids; [led] each one credited only through a band they would lead to the
     * bands' ids. Once [identified], [ids] maps each artist to who they are on this recording (the
     * credits' ids, a band's leaders, or the band as an unknown artist), and [creditedArtists]
     * counts those credited by name or as a confirmed leader.
     */
    private data class Candidate(
        val recording: MbRecording,
        val titleSimilarity: Double,
        val named: Map<String, Set<String>>,
        val led: Map<String, Set<String>>,
        val durationGap: Duration?,
        val ids: Map<String, Set<String>> = emptyMap(),
        val creditedArtists: Int = 0,
    ) {
        /** With [leaders], the leader ids found for each (artist, band id), none when unconfirmed. */
        fun identified(leaders: Map<Pair<String, String>, Set<String>>): Candidate {
            val byBand =
                led.mapValues { (artist, bands) ->
                    bands.flatMap { band -> leaders[artist to band].orEmpty().ifEmpty { setOf(BAND + band) } }.toSet()
                }
            val all = (named.keys + byBand.keys).associateWith { named[it].orEmpty() + byBand[it].orEmpty() }
            return copy(ids = all, creditedArtists = all.count { (_, ids) -> ids.any { !it.startsWith(BAND) } })
        }

        val titleMatches: Boolean get() = titleSimilarity >= ResolutionThresholds.TITLE_SIMILARITY
        val durationMatches: Boolean get() =
            durationGap == null ||
                durationGap <= ResolutionThresholds.DURATION_TOLERANCE

        companion object {
            /** The prefix of a band's id standing for its unconfirmed leader, an unknown artist. */
            const val BAND = "band:"
        }
    }

    /**
     * Confirms band credits (#77): a band credit (`X and His …`) stands for the track's artist X
     * only when a recording of that band among the candidates names X in a performer relationship;
     * the wording alone would let "Echo" match Echo & the Bunnymen. The relationships give X's own
     * ids, which the same-name check then counts. Each band is tried on its preferred candidates,
     * at most [TRIES] of them, so an unconfirmed band costs a few requests, not one per recording;
     * since the cap proves nothing, an unconfirmed band still counts in that check, as an unknown
     * artist.
     */
    private class Leaders(
        private val musicBrainz: MusicBrainzLookup,
        private val names: NameMatcher,
    ) {
        /** The recordings fetched, so the chosen one is not fetched twice. */
        val details: MutableMap<String, MbRecordingDetail> = mutableMapOf()

        /**
         * For each (artist, band id) of [candidates]' band credits, every id of a performer of the
         * artist's name on the band's preferred recordings, at most [TRIES] of them: none when
         * unconfirmed, two when two artists of that name lead it. Null when MusicBrainz did not
         * answer, since a band left unchecked could hide a collision.
         */
        suspend fun confirm(candidates: List<Candidate>): Map<Pair<String, String>, Set<String>>? {
            val bands =
                candidates
                    .flatMap { c -> c.led.flatMap { (artist, bands) -> bands.map { (artist to it) to c } } }
                    .groupBy({ it.first }, { it.second })
            val confirmed = mutableMapOf<Pair<String, String>, Set<String>>()
            for ((key, recordings) in bands) {
                val leaders = mutableSetOf<String>()
                for (candidate in recordings.sortedWith(PREFERENCE).take(TRIES)) {
                    val relations =
                        when (val detail = detail(candidate.recording.mbid)) {
                            is Lookup.Found -> detail.value.artistRelations
                            is Lookup.NotFound -> emptyList()
                            is Lookup.Failed -> return null
                        }
                    leaders +=
                        relations
                            .filter { it.type in PERFORMER_TYPES && names.sameName(it.artistName, key.first) }
                            .map { it.artistMbid }
                }
                confirmed[key] = leaders
            }
            return confirmed
        }

        private suspend fun detail(mbid: String): Lookup<MbRecordingDetail> =
            details[mbid]?.let { Lookup.Found(it) }
                ?: musicBrainz.recording(mbid).also { if (it is Lookup.Found) details[mbid] = it.value }

        private companion object {
            const val TRIES = 3
        }
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

        // With the player's length, a candidate whose length is known and fits beats one with no
        // length, however early: a lengthless recording may be a demo, a bootleg or a live take.
        private fun narrowed(
            candidates: List<Candidate>,
            duration: Duration?,
        ): List<Candidate> {
            val measured = candidates.filter { it.durationGap != null }
            return if (duration != null && measured.isNotEmpty()) measured else candidates
        }

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
