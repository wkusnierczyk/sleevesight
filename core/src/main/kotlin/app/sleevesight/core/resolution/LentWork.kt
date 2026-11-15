package app.sleevesight.core.resolution

import app.sleevesight.core.model.NormalizedTrack

// The work a recording that links none borrows from another recording of the track's artists (#1289), or
// finds by a search of the composer's works (#1299).

/**
 * The work a resolution reads (#1289): the recording's [own], else the one [lent] by another recording of
 * the track's artists (`RecordingResolver.Leaders.lentWork`), with its lookup. With a composer the title
 * leads with ([NormalizedTrack.composer]), a lent work counts only when its composer relationship names
 * them, whatever its type: a lead is only a credited artist's name before a colon (`Band: Song` has one
 * too), and `Schubert: Ave Maria` is not Verdi's `Otello` aria. Without one, only a work of a classical
 * form ([MbWorkLink.classical]). One that does not count is dropped with its lookup; one whose
 * lookup failed, dropped with the failure kept, so the resolution is asked again later.
 *
 * With that composer and no work so far, and no lookup failed, the composer's work of the title is
 * searched for ([searchedWork], #1299).
 */
internal suspend fun MusicBrainzLookup.workOf(
    names: NameMatcher,
    track: NormalizedTrack,
    own: MbWorkLink?,
    lent: Lent?,
): Pair<MbWorkLink?, Lookup<MbWork>?> {
    val link = own ?: lent?.link
    // A lent work's lookup made while it was weighed is not made again.
    val lookup = if (own == null && lent?.work != null) lent.work else link?.let { work(it.mbid) }
    val written = (lookup as? Lookup.Found)?.value?.let { names.writtenByLead(track, it) } == true
    return when {
        own != null -> link to lookup
        lent != null && (if (track.composer == null) lent.link.classical else written) -> link to lookup
        lookup is Lookup.Failed -> null to lookup
        else -> searchedWork(names, track)
    }
}

/**
 * The work of a recording that links none, nor is lent one (#1299): with a composer the title leads with
 * ([NormalizedTrack.composer]), one search of MusicBrainz's works by the title without the lead
 * ([NormalizedTrack.searchTitle]) and the composer's name ([MusicBrainzLookup.searchWorks]). A work found
 * counts only when its composer relationship names that composer, and its title fits the track's as a
 * recording's does (as written or as a part of a work, [NameMatcher.titleSimilarity],
 * [NameMatcher.pieceSimilarity]) with the same numbers ([NameMatcher.sameNumbers]: Barber's *Symphony No.
 * 1* is not his *No. 2*). Of those, the one linking the most recordings, MusicBrainz's order deciding a
 * tie: MusicBrainz keeps a work for each arrangement of a much-arranged piece (Barber's *Adagio for
 * Strings* for organ, for clarinet choir, a catch-all for the rest), all of the same title and composer,
 * and the piece itself is the most recorded. It is linked as this recording's own performance of it (no
 * cover, not instrumental), and looked up as any work. Without a composer, none, no request made; a search
 * that fails, none with the failure, so the resolution is asked again later.
 */
internal suspend fun MusicBrainzLookup.searchedWork(
    names: NameMatcher,
    track: NormalizedTrack,
): Pair<MbWorkLink?, Lookup<MbWork>?> {
    val composer = track.composer?.name ?: return null to null
    val title = track.searchTitle
    return when (val found = searchWorks(title, composer)) {
        is Lookup.Failed -> null to found
        Lookup.NotFound -> null to null
        is Lookup.Found ->
            found.value
                .filter { work ->
                    work.artistRelations.any { it.type == COMPOSER && names.sameName(it.artistName, composer) } &&
                        names.titleFits(title, work.title)
                }.maxByOrNull { it.recordings }
                ?.let { MbWorkLink(it.mbid, it.title, cover = false, type = it.type) to work(it.mbid) }
                ?: (null to null)
    }
}

// Whether a work's title [workTitle] fits [title] as a recording's does ([searchedWork], #1299).
private fun NameMatcher.titleFits(
    title: String,
    workTitle: String,
): Boolean =
    maxOf(titleSimilarity(title, workTitle), pieceSimilarity(title, workTitle) ?: 0.0) >=
        ResolutionThresholds.TITLE_SIMILARITY &&
        sameNumbers(title, workTitle)

private const val COMPOSER = "composer"

/**
 * A work lent to a recording that links none (#1289, `RecordingResolver.Leaders.lentWork`): its [link], as
 * the recording's own performance of it, and its [work] lookup when one was made to weigh it (with a
 * composer lead), so [workOf] does not make it again.
 */
internal class Lent(
    val link: MbWorkLink,
    val work: Lookup<MbWork>? = null,
)

// Whether [work]'s composer relationship names the composer [track]'s title leads with (#1289); false
// without one.
internal fun NameMatcher.writtenByLead(
    track: NormalizedTrack,
    work: MbWork,
): Boolean {
    val lead = track.composer?.name
    return lead != null && work.artistRelations.any { it.type == "composer" && sameName(it.artistName, lead) }
}
