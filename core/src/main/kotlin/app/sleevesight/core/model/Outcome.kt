package app.sleevesight.core.model

/**
 * What one source found for a track (`design/design.md`, *Entity resolution*): a typed outcome,
 * never a silent miss. [None] and [SourceOff] are deliberately different: one is a gap in the
 * world's documentation, the other the user's choice.
 */
sealed interface Outcome {
    /** The source describes this recording. */
    data object SongArticle : Outcome

    /** The source describes the performer only. */
    data object ArtistArticle : Outcome

    /** The source describes the song as first recorded by someone else: this track is a cover or version. */
    data object OriginalSong : Outcome

    /** A candidate was found and failed the entity check, for example a different artist with the same name. */
    data class Rejected(
        val reason: String,
    ) : Outcome

    /** Nothing found. */
    data object None : Outcome

    /** The user switched the source off. */
    data object SourceOff : Outcome

    /** A network or source error. */
    data object Unreachable : Outcome
}

/**
 * An outcome as the design names it (`song_article`, `rejected`, …), for logs and reports. Not a
 * storage format: the retrieval cache keeps its own names, which must never change.
 */
fun outcomeKey(outcome: Outcome): String =
    when (outcome) {
        Outcome.SongArticle -> "song_article"
        Outcome.ArtistArticle -> "artist_article"
        Outcome.OriginalSong -> "original_song"
        is Outcome.Rejected -> "rejected"
        Outcome.None -> "none"
        Outcome.SourceOff -> "source_off"
        Outcome.Unreachable -> "unreachable"
    }
