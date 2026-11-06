package app.sleevesight.core.resolution

/**
 * The tracklist of an album, for the back of Home's cover (#984), from [musicBrainz]: the album by
 * its release group as the pipeline records it (`ReleaseDate.group`, the album card's
 * `CardEntity.Album.group`), whose release is chosen here, then that release looked up with its
 * recordings. Each request is answered once by a [RememberedMusicBrainz] for a while.
 *
 * The release shown is the one the song was matched to: of the playing recording's releases
 * (`MusicBrainzLookup.recordingReleases`, which resolution has most often asked already), those in
 * the album's group, by [choose], so a bonus track shows the edition it is on. When the recording is
 * not known, or is on none of the group's releases it lists, only the group is: its earliest official
 * release ([choose]); for an id that is no group's (`ReleaseDate.group` keeps a release's own for
 * want of a group), the release of that id. The group's lookup lists only its 25 earliest releases:
 * when none of them is official, its official releases are browsed
 * (`MusicBrainzLookup.officialReleases`) before one with no status is taken.
 */
class AlbumTracklists(
    private val musicBrainz: MusicBrainzLookup,
) {
    /**
     * The tracklist of the album [group], of the release the playing [recording] is on when known:
     * found with at least one track; not found when the group has no release to show, or its release
     * lists no track; failed when MusicBrainz could not be asked.
     */
    suspend fun of(
        group: String,
        recording: String? = null,
    ): Lookup<MbTracklist> {
        val matched =
            when (val releases = recording?.let { musicBrainz.recordingReleases(it) }) {
                is Lookup.Found ->
                    choose(releases.value.releases.filter { (it.releaseGroup?.mbid ?: it.mbid) == group })
                        ?.let { Lookup.Found(it) }
                is Lookup.Failed -> releases
                Lookup.NotFound, null -> null
            }
        val tracklist =
            when (matched) {
                is Lookup.Found -> musicBrainz.tracklist(matched.value.mbid)
                is Lookup.Failed -> matched
                else -> ofGroup(group)
            }
        return if (tracklist is Lookup.Found && tracklist.value.media.all { it.tracks.isEmpty() }) {
            Lookup.NotFound
        } else {
            tracklist
        }
    }

    // The tracklist of [group]'s own release: its earliest official one, as [of] says.
    private suspend fun ofGroup(group: String): Lookup<MbTracklist> =
        when (val releases = musicBrainz.groupReleases(group)) {
            is Lookup.Found ->
                when (val release = release(group, releases.value)) {
                    is Lookup.Found -> release.value?.let { musicBrainz.tracklist(it.mbid) } ?: Lookup.NotFound
                    Lookup.NotFound -> Lookup.NotFound
                    is Lookup.Failed -> release
                }
            // No group of that id: the id is a release's own, which the pipeline keeps for want of a group.
            Lookup.NotFound -> musicBrainz.tracklist(group)
            is Lookup.Failed -> releases
        }

    // The release of [group] to show, of its 25 earliest, [listed]: [choose]'s, but with none of them
    // official, the earliest of its official releases beyond them, when MusicBrainz has one. Found
    // null when there is none to show.
    private suspend fun release(
        group: String,
        listed: List<MbRelease>,
    ): Lookup<MbRelease?> =
        earliestOfficial(listed)?.let { Lookup.Found(it) }
            ?: when (val browsed = musicBrainz.officialReleases(group)) {
                is Lookup.Found -> Lookup.Found(earliestOfficial(browsed.value) ?: choose(listed))
                Lookup.NotFound -> Lookup.Found(choose(listed))
                is Lookup.Failed -> browsed
            }

    companion object {
        /**
         * The release of [releases] (a group's) whose tracklist is shown: the earliest official one;
         * with none official listed, the earliest with no status recorded (MusicBrainz leaves many
         * unset); never a bootleg, promotion or pseudo-release. The earliest by date, an undated one
         * after every dated one; of one date, in MusicBrainz's order.
         */
        fun choose(releases: List<MbRelease>): MbRelease? =
            earliestOfficial(releases) ?: releases.filter { it.status == null }.minWithOrNull(EARLIEST)

        // The earliest official release of [releases], or null.
        private fun earliestOfficial(releases: List<MbRelease>): MbRelease? =
            releases.filter { it.status.equals(OFFICIAL, ignoreCase = true) }.minWithOrNull(EARLIEST)

        private const val OFFICIAL = "Official"

        // The earliest date first, an undated release after every dated one; `minWithOrNull` keeps
        // the first of equals, MusicBrainz's order.
        private val EARLIEST: Comparator<MbRelease> =
            compareBy<MbRelease> { it.date == null }.thenBy { it.date.orEmpty() }
    }
}
