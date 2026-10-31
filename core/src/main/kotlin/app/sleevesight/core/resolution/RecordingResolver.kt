package app.sleevesight.core.resolution

import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.sources.Credit
import app.sleevesight.core.sources.CreditRole
import app.sleevesight.core.sources.FirstRelease
import app.sleevesight.core.sources.OriginalRecording
import app.sleevesight.core.sources.ReleaseDate
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.ResolvedArtist
import app.sleevesight.core.sources.ResolvedWork
import app.sleevesight.core.sources.WorkLyrics
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
 * [resolve] never throws, apart from cancellation: a failed lookup is [Resolution.unreachable], with
 * the lookup's reason, so the pipeline can complete the adapters' `Deferred<Resolution>` normally.
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
            is Lookup.Failed -> Resolution.unreachable(search.reason)
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
                ?: return Resolution.unreachable(checkNotNull(leaders.failure))
        val candidates = searched.map { it.identified(confirmed) }
        // Every candidate whose title and length fit and that names one of the track's artists, by
        // name or as a band's leader, confirmed or not: the same-name check weighs them all.
        val fitting = candidates.filter { it.titleMatches && it.durationMatches && it.ids.isNotEmpty() }
        val credited = fitting.filter { it.creditedArtists > 0 }
        val accepted = narrowed(credited, duration)
        val collision = collision(track, (narrowed(fitting, duration) + accepted).distinct())
        val chosen = accepted.minWithOrNull(PREFERENCE)
        return when {
            collision != null -> collision
            chosen == null -> weaker(track, duration, candidates, leaders, fitting) ?: unmatched(track, candidates)
            else -> accept(track, chosen, leaders.details[chosen.recording.mbid], accepted)
        }
    }

    // The weaker forms of a credit, each weighed only when no candidate whose title and length fit
    // ([fitting]) names the track's artists in a stronger way, so a weaker form never stands beside or
    // against a stronger one; the first form that some fitting candidate has decides. Then those credits
    // are taken as the artists' credits, with the same rules: the earliest of the fitting is chosen, and
    // a name two MusicBrainz identities share collides. Null when no fitting candidate has either form.
    // - A credit under an honorific variant of an artist's name (`Mr. A Balladeer` for `a balladeer`,
    //   #79).
    // - Then a joint credit (#661): the player names as one artist what MusicBrainz credits as several,
    //   such as "Derya Yıldırım & Grup Şimşek", credited "Derya Yıldırım" and "Grup Şimşek" joined by
    //   " & ". A joint credit is one identity, the run of artists it credits, so one name two different
    //   runs share collides, even on one recording. The artist gets no MusicBrainz id, since no one
    //   MusicBrainz artist is theirs; the run's artists count as theirs for the work's original, and
    //   give the genre labels ([Resolution.jointArtists]). Looked for only here, in the fallback.
    private suspend fun weaker(
        track: NormalizedTrack,
        duration: Duration?,
        candidates: List<Candidate>,
        leaders: Leaders,
        fitting: List<Candidate>,
    ): Resolution? {
        val fits = candidates.filter { it.titleMatches && it.durationMatches }
        val accepted: List<Candidate> =
            if (fitting.isNotEmpty()) {
                emptyList()
            } else {
                sequenceOf<(Candidate) -> Candidate?>(
                    { it.asVariant() },
                    { c ->
                        c.jointly(
                            track.artists
                                .associateWith { names.jointCredits(c.recording.credits, it) }
                                .filterValues { it.isNotEmpty() },
                        )
                    },
                ).map { form -> narrowed(fits.mapNotNull(form), duration) }
                    .firstOrNull { it.isNotEmpty() }
                    .orEmpty()
            }
        val chosen = accepted.minWithOrNull(PREFERENCE) ?: return null
        return collision(track, accepted) ?: accept(track, chosen, leaders.details[chosen.recording.mbid], accepted)
    }

    private fun candidate(
        track: NormalizedTrack,
        duration: Duration?,
        recording: MbRecording,
    ): Candidate {
        val length = recording.lengthMs?.milliseconds
        val gap = if (duration == null || length == null) null else (length - duration).absoluteValue

        // Every matching credit's id, so two same-name artists on one recording are both seen.
        fun matching(matches: (MbCredit, String) -> Boolean): Map<String, Set<String>> =
            track.artists
                .associateWith { artist ->
                    recording.credits
                        .filter { matches(it, artist) }
                        .map { it.artistMbid }
                        .toSet()
                }.filterValues { it.isNotEmpty() }
        val named = matching { credit, artist -> credits(credit, artist, byName = true) }
        val any = matching { credit, artist -> credits(credit, artist) }
        val variants =
            matching { credit, artist ->
                listOf(credit.name, credit.artistName).any { names.honorificVariant(it, artist) }
            }
        return Candidate(
            recording = recording,
            titleSimilarity = names.titleSimilarity(track.title, recording.title),
            named = named,
            // A band credit counts beside a credit by name: "Alex Smith" and "Alex Smith and His
            // Orchestra" on one recording may be two artists.
            led = any.mapValues { (artist, ids) -> ids - named[artist].orEmpty() }.filterValues { it.isNotEmpty() },
            variants = variants,
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

    // [pool]: the candidates the choice was made from (title and length fit, the track's artists
    // credited, narrowed by length as the choice is), whose releases give the album's year (#556). The
    // same-name check passed over them, so each artist they credit has one identity among them, the
    // accepted one: never a namesake's recording.
    private suspend fun accept(
        track: NormalizedTrack,
        chosen: Candidate,
        fetched: MbRecordingDetail?,
        pool: List<Candidate>,
    ): Resolution {
        val recording = chosen.recording
        // Each release, by its own title, in its release group (else alone); undated ones too.
        val releases =
            pool.flatMap { it.recording.releases }.map { release ->
                ReleaseDate(release.title, release.date, release.releaseGroup?.mbid ?: release.mbid)
            }
        val base =
            Resolution(
                outcome = Outcome.SongArticle,
                recordingMbid = recording.mbid,
                title = recording.title,
                artistCredit = creditString(recording.credits),
                artists =
                    track.artists.map { artist ->
                        ResolvedArtist(artist, artistMbid(artist, chosen, emptyList()))
                    },
                jointArtists = chosen.jointArtists,
                firstRelease = firstRelease(recording),
                releaseDates = ReleaseDate.collapse(releases, names::key),
            )
        val detail = fetched ?: (musicBrainz.recording(recording.mbid) as? Lookup.Found)?.value
        return if (detail == null) base else withDetail(track, chosen, base, detail)
    }

    private suspend fun withDetail(
        track: NormalizedTrack,
        chosen: Candidate,
        base: Resolution,
        detail: MbRecordingDetail,
    ): Resolution {
        val recording = chosen.recording
        val artists =
            track.artists.map { artist ->
                ResolvedArtist(
                    artist,
                    artistMbid(artist, chosen, detail.artistRelations),
                )
            }
        val recordingCredits = recordingCredits(detail.artistRelations)
        val link = detail.works.firstOrNull()
        val lookup = link?.let { musicBrainz.work(it.mbid) }
        val work = (lookup as? Lookup.Found)?.value
        val withRecording =
            base.copy(
                artists = artists,
                credits = recordingCredits,
                wikidataIds = detail.wikidataIds,
                recordingTags = detail.tags,
                lyrics = lyricsOf(detail, lookup),
            )
        return if (link == null || work == null) {
            withRecording.copy(work = link?.let { ResolvedWork(it.mbid, it.title) }, cover = link?.cover ?: false)
        } else {
            withWork(recording, withRecording, link, work, chosen.credited)
        }
    }

    private fun withWork(
        recording: MbRecording,
        withRecording: Resolution,
        link: MbWorkLink,
        work: MbWork,
        credited: Set<String>,
    ): Resolution {
        // The track's artists: their resolved ids and the credits that stand for them ([credited]), a
        // band whose leader is confirmed among them. Not every credit: a collaborator the player does
        // not name, or a band only worded like one of theirs, is not ours, so their earlier recording
        // of the work is someone else's.
        val ours = withRecording.artists.mapNotNull { it.mbid }.toSet() + credited
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
        // Recording dates only, on both sides: others' recordings carry no release date, so the
        // chosen one's release date standing in would favour ours. Never for a cover.
        val ourFirstSession = own.mapNotNull { it.date }.minOrNull()
        val earliest =
            !cover && original != null && ourFirstSession != null && isEarlier(ourFirstSession, original.date)
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

    // On [chosen]'s recording, the credit by name first; else a performer relationship of the recording
    // ("Them" is credited, Van Morrison sings); else the id the choice established ([Candidate.known]: a
    // band's leader confirmed on another of its recordings). A band the artist leads is not the artist,
    // so its id is never theirs; nor is any for an artist accepted under a joint credit, which no one
    // MusicBrainz artist is, even one a relationship names alike.
    private fun artistMbid(
        artist: String,
        chosen: Candidate,
        relations: List<MbArtistRelation>,
    ): String? {
        val byName = chosen.recording.credits.firstOrNull { credits(it, artist, byName = true) }
        val performer = relations.firstOrNull { it.type in PERFORMER_TYPES && names.sameName(it.artistName, artist) }
        return if (artist in chosen.joint) null else byName?.artistMbid ?: performer?.artistMbid ?: chosen.known[artist]
    }

    /**
     * A search result as a candidate: [named] maps each track artist it credits by name to the
     * credits' MusicBrainz ids; [led] each one's band credits (a band they would lead) to the bands'
     * ids, beside any credit by name; [variants] each one's credits under an honorific variant of
     * the name ([NameMatcher.honorificVariant]), for [weaker] ([asVariant]); once [jointly], [joint]
     * each one's joint credits, the runs of credits that together write the name, whose artists' ids
     * are [parts]. Once [identified], [ids] maps each artist to who they are on this
     * recording (the credits' ids, a band's leaders, a joint credit's run, or the band as an unknown
     * artist), [creditedArtists] counts those credited by name, jointly or as a confirmed leader, and
     * [bands] holds the ids of the bands whose leader is confirmed.
     */
    private data class Candidate(
        val recording: MbRecording,
        val titleSimilarity: Double,
        val named: Map<String, Set<String>>,
        val led: Map<String, Set<String>>,
        val variants: Map<String, Set<String>> = emptyMap(),
        val joint: Map<String, Set<List<MbCredit>>> = emptyMap(),
        val durationGap: Duration?,
        val ids: Map<String, Set<String>> = emptyMap(),
        val creditedArtists: Int = 0,
        val bands: Set<String> = emptySet(),
    ) {
        /** With [leaders], the leader ids found for each (artist, band id), none when unconfirmed. */
        fun identified(leaders: Map<Pair<String, String>, Set<String>>): Candidate {
            val byBand =
                led.mapValues { (artist, bandIds) ->
                    bandIds.flatMap { band -> leaders[artist to band].orEmpty().ifEmpty { setOf(BAND + band) } }.toSet()
                }
            val all = (named.keys + byBand.keys).associateWith { named[it].orEmpty() + byBand[it].orEmpty() }
            val confirmed =
                led.flatMap { (artist, bandIds) ->
                    bandIds.filter { leaders[artist to it].orEmpty().isNotEmpty() }
                }
            return copy(
                ids = all,
                creditedArtists = all.count { (_, ids) -> ids.any { !it.startsWith(BAND) } },
                bands = confirmed.toSet(),
            )
        }

        /**
         * Each artist's id as the choice established it, for a leader confirmed on another of the
         * band's recordings.
         */
        val known: Map<String, String?> get() =
            ids.mapValues { (_, ids) -> ids.singleOrNull()?.takeUnless { it.startsWith(BAND) || it.startsWith(JOINT) } }

        /**
         * The credits that stand for the track's artists: by name, a band whose leader is confirmed, or
         * the artists of a joint credit.
         */
        val credited: Set<String> get() {
            val byName = named.values.flatten().filterNot { it.startsWith(JOINT) }
            return byName.toSet() + bands + parts
        }

        /** As a candidate of its variant credits, for [weaker]: each one as the artist's credit; null with none. */
        fun asVariant(): Candidate? =
            if (variants.isEmpty()) null else copy(named = variants, led = emptyMap()).identified(emptyMap())

        /**
         * As a candidate of the joint credits [runs] (each artist's, none left empty), for [weaker]:
         * each run as one of the artist's credits, a single identity standing for its artists, so two
         * different runs for one artist collide; null with none.
         */
        fun jointly(runs: Map<String, Set<List<MbCredit>>>): Candidate? =
            if (runs.isEmpty()) {
                null
            } else {
                copy(
                    named =
                        runs.mapValues { (_, each) ->
                            each.map { run -> JOINT + run.joinToString("+") { it.artistMbid } }.toSet()
                        },
                    led = emptyMap(),
                    joint = runs,
                ).identified(emptyMap())
            }

        /** The artists of the joint credits, by their credited names: their ids are [parts]. */
        val jointArtists: List<ResolvedArtist> get() =
            joint.values
                .flatten()
                .flatten()
                .map { ResolvedArtist(it.name, it.artistMbid) }
                .distinctBy { it.mbid }

        /** The ids of the artists of the joint credits, counted as the track's artists' ([credited]). */
        val parts: Set<String> get() = jointArtists.mapNotNull { it.mbid }.toSet()

        val titleMatches: Boolean get() = titleSimilarity >= ResolutionThresholds.TITLE_SIMILARITY
        val durationMatches: Boolean get() =
            durationGap == null ||
                durationGap <= ResolutionThresholds.DURATION_TOLERANCE

        companion object {
            /** The prefix of a band's id standing for its unconfirmed leader, an unknown artist. */
            const val BAND = "band:"

            /** The prefix of a joint credit's identity: the ids of the artists it credits, in order. */
            const val JOINT = "joint:"
        }
    }

    /**
     * Confirms band credits (#77): a band credit (`X and His …`) stands for the track's artist X
     * only when a recording of that band among the candidates names X in a performer relationship;
     * the wording alone would let "Echo" match Echo & the Bunnymen. The relationships give X's own
     * ids, which the same-name check then counts. A performer the recording also credits by name
     * confirms nothing: beside "Alex Smith", a vocal by that Alex Smith does not show who leads "Alex
     * Smith and His Orchestra". So the band's recordings that do not credit X by name are tried
     * first, then the others, each group in order of preference, at most [TRIES] in all: an
     * unconfirmed band costs a few requests, not one per recording. Since the cap proves nothing, an
     * unconfirmed band still counts in that check, as an unknown artist.
     */
    private class Leaders(
        private val musicBrainz: MusicBrainzLookup,
        private val names: NameMatcher,
    ) {
        /** The recordings fetched, so the chosen one is not fetched twice. */
        val details: MutableMap<String, MbRecordingDetail> = mutableMapOf()

        /** The reason of the lookup that made [confirm] return null. */
        var failure: String? = null
            private set

        /**
         * For each (artist, band id) of [candidates]' band credits, every id of a performer of the
         * artist's name on up to [TRIES] of the band's recordings, chosen as above: none when
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
                val tried =
                    recordings
                        .sortedWith(compareBy<Candidate> { !it.named[key.first].isNullOrEmpty() }.then(PREFERENCE))
                        .take(TRIES)
                for (candidate in tried) {
                    val relations =
                        when (val detail = detail(candidate.recording.mbid)) {
                            is Lookup.Found -> detail.value.artistRelations
                            is Lookup.NotFound -> emptyList()
                            is Lookup.Failed -> {
                                failure = detail.reason
                                return null
                            }
                        }
                    leaders +=
                        relations
                            .filter { it.type in PERFORMER_TYPES && names.sameName(it.artistName, key.first) }
                            .map { it.artistMbid }
                            .filter { it !in candidate.named[key.first].orEmpty() }
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

        // A credited name that the candidates, after the length has narrowed them, give to more than one
        // MusicBrainz artist cannot be resolved: it is a same-name collision. A band credit counts as
        // every leader of that name found on the band's recordings, or, with none found, as an unknown
        // artist: a namesake's solo recording beside the real leader's band is a collision, whether or
        // not MusicBrainz shows the leader. Null when there is none.
        private fun collision(
            track: NormalizedTrack,
            pool: List<Candidate>,
        ): Resolution? =
            track.artists
                .firstOrNull { artist -> pool.flatMap { it.ids[artist].orEmpty() }.toSet().size > 1 }
                ?.let {
                    Resolution(
                        outcome = Outcome.Rejected("several MusicBrainz artists named $it have a matching recording"),
                    )
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

// What MusicBrainz says of the lyrics (#728): the lyrics pages of the work [lookup] found (only the
// first work is looked up; none without a work, or when MusicBrainz has no such work; not known when
// the lookup failed), the languages of every work the recording performs, and whether its
// performance of any of them is marked instrumental.
private fun lyricsOf(
    detail: MbRecordingDetail,
    lookup: Lookup<MbWork>?,
): WorkLyrics {
    val work = (lookup as? Lookup.Found)?.value
    return WorkLyrics(
        links =
            when (lookup) {
                null, Lookup.NotFound -> emptyList()
                is Lookup.Found -> lookup.value.lyricsUrls.distinct()
                is Lookup.Failed -> null
            },
        languages = (detail.works.flatMap { it.languages } + work?.languages.orEmpty()).distinct(),
        instrumental = detail.works.any { it.instrumental },
    )
}
