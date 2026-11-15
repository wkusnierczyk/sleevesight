package app.sleevesight.core.resolution

import app.sleevesight.core.model.NormalizedTrack

// The work a recording that links none borrows from another recording of the track's artists (#1289).

/**
 * The work a resolution reads (#1289): the recording's [own], else the one [lent] by another recording of
 * the track's artists (`RecordingResolver.Leaders.lentWork`), with its lookup. With a composer the title
 * leads with ([NormalizedTrack.composer]), a lent work counts only when its composer relationship names
 * them, whatever its type: a lead is only a credited artist's name before a colon (`Band: Song` has one
 * too), and `Schubert: Ave Maria` is not Verdi's `Otello` aria. Without one, only a work of a classical
 * form ([MbWorkLink.classical]). One that does not count is dropped with its lookup; one whose
 * lookup failed, dropped with the failure kept, so the resolution is asked again later.
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
        own != null || lent == null || (if (track.composer == null) lent.link.classical else written) -> link to lookup
        lookup is Lookup.Failed -> null to lookup
        else -> null to null
    }
}

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
