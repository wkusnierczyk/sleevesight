package app.sleevesight.core.resolution

/**
 * The tracklist of an album, for the back of Home's cover (#984), from [musicBrainz]: the album by
 * its release group as the pipeline records it (`ReleaseDate.group`, the album card's
 * `CardEntity.Album.group`), whose release is chosen here ([choose]), then that release looked up with
 * its recordings. Two requests, each answered once by a [RememberedMusicBrainz] for a while.
 *
 * The pipeline records the album the player names by its release group, not by a release: the
 * group's id, or a release's own when MusicBrainz names no group. So the release shown is the group's
 * earliest official one ([choose]); for an id that is no group's, the release of that id.
 */
class AlbumTracklists(
    private val musicBrainz: MusicBrainzLookup,
) {
    /**
     * The tracklist of the album [group]: found with at least one track; not found when the group
     * has no release to show, or its release lists no track; failed when MusicBrainz could not be
     * asked.
     */
    suspend fun of(group: String): Lookup<MbTracklist> {
        val tracklist =
            when (val releases = musicBrainz.groupReleases(group)) {
                is Lookup.Found -> choose(releases.value)?.let { musicBrainz.tracklist(it.mbid) } ?: Lookup.NotFound
                // No group of that id: the id is a release's own, which the pipeline keeps for want of a group.
                Lookup.NotFound -> musicBrainz.tracklist(group)
                is Lookup.Failed -> releases
            }
        return if (tracklist is Lookup.Found && tracklist.value.media.all { it.tracks.isEmpty() }) {
            Lookup.NotFound
        } else {
            tracklist
        }
    }

    companion object {
        /**
         * The release of [releases] (a group's) whose tracklist is shown: the earliest official one;
         * with none official listed, the earliest with no status recorded (MusicBrainz leaves many
         * unset); never a bootleg, promotion or pseudo-release. The earliest by date, an undated one
         * after every dated one; of one date, in MusicBrainz's order.
         */
        fun choose(releases: List<MbRelease>): MbRelease? {
            val official = releases.filter { it.status.equals(OFFICIAL, ignoreCase = true) }
            return official.minWithOrNull(EARLIEST) ?: releases.filter { it.status == null }.minWithOrNull(EARLIEST)
        }

        private const val OFFICIAL = "Official"

        // The earliest date first, an undated release after every dated one; `minWithOrNull` keeps
        // the first of equals, MusicBrainz's order.
        private val EARLIEST: Comparator<MbRelease> =
            compareBy<MbRelease> { it.date == null }.thenBy { it.date.orEmpty() }
    }
}
