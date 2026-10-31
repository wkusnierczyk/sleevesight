package app.sleevesight.core.lyrics

import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.AccentFolder
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.sources.WorkLyrics

/**
 * What Home's *Lyrics* panel holds for the playing track (#728), from MusicBrainz's answer
 * ([MusicBrainzAnswer]), the lyrics pages its work links ([WorkLyrics]), the track as the sources
 * name it ([NormalizedTrack]: "2006 Remaster" and the like already removed) and *Search Genius when
 * there is no link*. Built by [lyricsLinks]. Sleevesight only links: it never fetches or shows lyrics.
 */
sealed interface LyricsLinks {
    /** MusicBrainz has not answered yet: no link, and no search until it does. */
    data object Looking : LyricsLinks

    /**
     * MusicBrainz marks the recording's performance `instrumental`, or every language of its works
     * is `zxx` (no linguistic content): no link, and no search.
     */
    data object Instrumental : LyricsLinks

    /** The lyrics pages MusicBrainz links, one per site, in [LyricsSite]'s order; never empty. */
    data class Links(
        val links: List<LyricsLink>,
    ) : LyricsLinks

    /**
     * No page to link, for [reason], and the Genius search for the title and artist ([search]),
     * null while *Search Genius when there is no link* is off.
     */
    data class NoLink(
        val reason: NoLinkReason,
        val search: LyricsLink?,
    ) : LyricsLinks
}

/** Why the *Lyrics* panel has no page to link (#728). */
sealed interface NoLinkReason {
    /** MusicBrainz is disabled under *Settings → Sources*. */
    data object MusicBrainzOff : NoLinkReason

    /** MusicBrainz could not be reached, or its lookup went wrong. */
    data object Unreachable : NoLinkReason

    /** MusicBrainz answered without the song (nothing found, or a recording by another artist). */
    data object NotFound : NoLinkReason

    /** MusicBrainz found the song, and links to no page on a site of its lyrics whitelist. */
    data object NoLyricsPage : NoLinkReason
}

/** A page on [site]: a song's lyrics page, or, as [LyricsLinks.NoLink.search], a search. */
data class LyricsLink(
    val site: LyricsSite,
    val url: String,
)

/**
 * The sites MusicBrainz allows a work's lyrics relationship to link to, its lyrics whitelist
 * (https://musicbrainz.org/doc/Style/Relationships/URLs/Lyrics_whitelist), each by the name it
 * goes by and its [domain]: a link's host is the domain or a subdomain of it
 * (`artist.bandcamp.com`, `en.wikisource.org`). In the *Lyrics* panel's order: Genius, then
 * Musixmatch, then the others as the whitelist lists them (#728).
 */
enum class LyricsSite(
    val siteName: String,
    val domain: String,
) {
    GENIUS("Genius", "genius.com"),
    MUSIXMATCH("Musixmatch", "musixmatch.com"),
    BANDCAMP("Bandcamp", "bandcamp.com"),
    LIEDERNET("LiederNet", "lieder.net"),
    MUZIKUM("Muzikum", "muzikum.eu"),
    WIKISOURCE("Wikisource", "wikisource.org"),
    PROJECT_GUTENBERG("Project Gutenberg", "gutenberg.org"),
    PROJECT_RUNEBERG("Project Runeberg", "runeberg.org"),
    ONLINE_BIJBEL("Online-Bijbel.nl", "online-bijbel.nl"),
    UTA_NET("Uta-Net", "uta-net.com"),
    J_LYRIC("J-Lyric.net", "j-lyric.net"),
    UTATEN("Utaten", "utaten.com"),
    KASHINAVI("Kashinavi", "kashinavi.com"),
    PETITLYRICS("PetitLyrics", "petitlyrics.com"),
    JOYSOUND("JOYSOUND", "joysound.com"),
    HOICK("Hoick", "hoick.jp"),
    ;

    /** Whether [host] (lowercase) is this site's domain or a subdomain of it. */
    fun hosts(host: String): Boolean = host == domain || host.endsWith(".$domain")

    companion object {
        /** The site [host] (lowercase) belongs to, or null for a host off the whitelist. */
        fun of(host: String): LyricsSite? = entries.firstOrNull { it.hosts(host) }
    }
}

/**
 * MusicBrainz's state for the track's run, as the lyrics links read it (#728), told apart as
 * *Sources* tells them ([of]).
 */
sealed interface MusicBrainzAnswer {
    /** Disabled under *Settings → Sources*. */
    data object Off : MusicBrainzAnswer

    /** Not answered yet. */
    data object Looking : MusicBrainzAnswer

    /** Not reached, or its lookup went wrong. */
    data object Unreachable : MusicBrainzAnswer

    /** Answered without the song. */
    data object NotFound : MusicBrainzAnswer

    /**
     * Answered with the song, and what it says of its lyrics; [lyrics] null when not known, read as
     * links not known and not instrumental.
     */
    data class Found(
        val lyrics: WorkLyrics?,
    ) : MusicBrainzAnswer

    companion object {
        /**
         * MusicBrainz's answer from its [state] in the track's run (`TrackState.sources`), as the
         * *Sources* chips read it: switched off ([Outcome.SourceOff], or not [enabled] in the
         * settings) is [Off]; not asked yet (no state) or [SourceState.Pending] is [Looking];
         * [Outcome.Unreachable], or a lookup that failed ([SourceState.Failed], which *Retry* may
         * mend), is [Unreachable]; [Outcome.None] or [Outcome.Rejected] (a recording by another
         * artist) is [NotFound]; an accepted recording is [Found], with its result's
         * [app.sleevesight.core.sources.SourceResult.lyrics].
         */
        fun of(
            state: SourceState?,
            enabled: Boolean = true,
        ): MusicBrainzAnswer =
            if (!enabled) {
                Off
            } else {
                when (state) {
                    null, SourceState.Pending -> Looking
                    is SourceState.Failed -> Unreachable
                    is SourceState.Done -> answerOf(state)
                }
            }

        private fun answerOf(state: SourceState.Done): MusicBrainzAnswer =
            when (state.result.outcome) {
                Outcome.SourceOff -> Off
                Outcome.Unreachable -> Unreachable
                Outcome.None, is Outcome.Rejected -> NotFound
                Outcome.SongArticle, Outcome.ArtistArticle, Outcome.OriginalSong -> Found(state.result.lyrics)
            }
    }
}

/**
 * The *Lyrics* panel of [track] (#728), MusicBrainz's state read from it ([MusicBrainzAnswer.of]),
 * disabled under *Sources* unless [musicBrainzOn]; [search] is *Search Genius when there is no link*.
 */
fun lyricsLinks(
    track: TrackState,
    search: Boolean,
    accentFolder: AccentFolder,
    musicBrainzOn: Boolean = true,
): LyricsLinks =
    lyricsLinks(
        MusicBrainzAnswer.of(track.sources[SourceId.MUSICBRAINZ], musicBrainzOn),
        track.normalized,
        search,
        accentFolder,
    )

/**
 * The *Lyrics* panel (#728), in this order:
 *
 * 1. [LyricsLinks.Looking] while MusicBrainz has not answered.
 * 2. [LyricsLinks.Instrumental] when MusicBrainz found the song and marks the recording's performance
 *    `instrumental`, or gives languages for its works, every one `zxx`.
 * 3. [LyricsLinks.Links] when MusicBrainz found the song and links lyrics pages on its whitelist
 *    ([LyricsSite]): `https` only, one per site, in the sites' order; of several on one site, the
 *    first whose path names one of the [track]'s artists (compared as slugs, [slug]), else the first.
 * 4. Else [LyricsLinks.NoLink], with its reason ([NoLinkReason.Unreachable] too when MusicBrainz
 *    found the song but a lookup failed, so its links are not known), and the Genius search for the
 *    [track]'s title and primary artist ([searchUrl]) while [search] is on.
 */
fun lyricsLinks(
    musicBrainz: MusicBrainzAnswer,
    track: NormalizedTrack,
    search: Boolean,
    accentFolder: AccentFolder,
): LyricsLinks =
    when (musicBrainz) {
        MusicBrainzAnswer.Looking -> LyricsLinks.Looking
        MusicBrainzAnswer.Off -> noLink(NoLinkReason.MusicBrainzOff, track, search)
        MusicBrainzAnswer.Unreachable -> noLink(NoLinkReason.Unreachable, track, search)
        MusicBrainzAnswer.NotFound -> noLink(NoLinkReason.NotFound, track, search)
        is MusicBrainzAnswer.Found -> {
            val lyrics = musicBrainz.lyrics ?: WorkLyrics.NONE
            val known = lyrics.links
            val links = pages(known.orEmpty(), track.artists, accentFolder)
            when {
                instrumental(lyrics) -> LyricsLinks.Instrumental
                links.isNotEmpty() -> LyricsLinks.Links(links)
                // A lookup failed: the links are not known, so not said to be none.
                known == null -> noLink(NoLinkReason.Unreachable, track, search)
                else -> noLink(NoLinkReason.NoLyricsPage, track, search)
            }
        }
    }

// No link for [reason], with the Genius search while [search] is on.
private fun noLink(
    reason: NoLinkReason,
    track: NormalizedTrack,
    search: Boolean,
): LyricsLinks.NoLink =
    LyricsLinks.NoLink(reason, if (search) LyricsLink(LyricsSite.GENIUS, searchUrl(track)) else null)

/** Whether [lyrics] says there are none: the performance marked instrumental, or only `zxx` works. */
internal fun instrumental(lyrics: WorkLyrics): Boolean =
    lyrics.instrumental || (lyrics.languages.isNotEmpty() && lyrics.languages.all { it == NO_LINGUISTIC_CONTENT })

/**
 * Of [urls], the lyrics pages to link: `https` on a whitelisted host, one per site, in [LyricsSite]'s
 * order; of a site's several, the first whose path names one of [artists] ([namesArtist]), else the first.
 */
internal fun pages(
    urls: List<String>,
    artists: List<String>,
    accentFolder: AccentFolder,
): List<LyricsLink> {
    val bySite =
        urls
            .mapNotNull { url ->
                parse(url)?.let { (host, path) -> LyricsSite.of(host)?.let { Triple(it, url, path) } }
            }.groupBy({ it.first }, { it.second to it.third })
    val artistSlugs = artists.flatMap { artistSlugs(it, accentFolder) }.distinct()
    return LyricsSite.entries.filter { it in bySite }.map { site ->
        val pages = bySite.getValue(site)
        val chosen = pages.firstOrNull { (_, path) -> namesArtist(path, artistSlugs, accentFolder) } ?: pages.first()
        LyricsLink(site, chosen.first)
    }
}

/** ISO 639-3's code for no linguistic content, which MusicBrainz gives an instrumental work. */
internal const val NO_LINGUISTIC_CONTENT = "zxx"
