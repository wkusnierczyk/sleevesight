package app.sleevesight.core.sources

import app.sleevesight.core.evidence.EvidenceDraft

/**
 * The lines about an artist or an album the search in the track's language found (#902, #939;
 * `design/architecture.md`, *Search in the track's language*), kept per artist and per album
 * ([LanguageSubject]), so the artist's and the album's insights take them on each of their tracks,
 * whether that track was searched in the language or not: under *If needed* only the thin tracks
 * are, and their fingerprints would otherwise change from one track to the next. An interface here,
 * implemented in `:sources` (`LanguageLinesSource`), which `:core` cannot see.
 *
 * The first lines found in a language are the ones kept ([keep]), so the fingerprint of the
 * artist's or the album's evidence changes once, when they are found, not with each track searched.
 * An implementation does not throw, but for cancellation.
 */
interface ItemLanguageLines {
    /**
     * Every language's lines kept for [subject]: by the language's code, then as found; none when
     * none is kept.
     */
    suspend fun kept(subject: LanguageSubject): List<EvidenceDraft>

    /**
     * Keeps [lines], found in [language] (its code, `EvidenceDraft.language`) about [subject], unless
     * lines in [language] are kept for it already: the first found are kept. None, nothing is kept.
     */
    suspend fun keep(
        subject: LanguageSubject,
        language: String,
        lines: List<EvidenceDraft>,
    )

    /**
     * [subject]'s lines kept again, from now, when a card of its insight was written: they last as
     * long as the last card written from them, as the web's do (`ArtistWebLookup.renew`,
     * `AlbumLookup.renewWeb`). Nothing when none is kept.
     */
    suspend fun renew(subject: LanguageSubject)
}

/** What lines in the track's language are kept for ([ItemLanguageLines]): an artist or an album. */
sealed interface LanguageSubject {
    /**
     * An artist, as its card names it (`CardEntity.Artist`): kept by its MusicBrainz id, [mbid], when
     * the resolution gave one, else by its [name] folded.
     */
    data class Artist(
        val name: String,
        val mbid: String?,
    ) : LanguageSubject

    /**
     * An album, as its card names it (`CardEntity.Album`): kept by its MusicBrainz release group,
     * [group], when its name names one, else by its [title] and the track's primary [artist], folded,
     * and its [performers] when it has them (#1130: a classical album's, `CardEntity.Album.performers`),
     * folded, without repeats and sorted, so two recordings of one title stay apart and the order a
     * track credits them in does not matter.
     */
    data class Album(
        val title: String,
        val artist: String,
        val group: String?,
        val performers: List<String> = emptyList(),
    ) : LanguageSubject
}
