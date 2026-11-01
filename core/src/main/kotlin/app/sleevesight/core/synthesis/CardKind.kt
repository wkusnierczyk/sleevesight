package app.sleevesight.core.synthesis

/**
 * The cards the model writes (`design/design.md`, *Detail content*, *Appendix. Prompt contracts*):
 * part of the synthesis cache key, so a card of one kind is never served as another.
 *
 * @property key the kind as the cache stores it. It is fixed, not the constant's name, so renaming
 *   a constant does not empty the cache.
 */
enum class CardKind(
    val key: String,
) {
    /** The home insight about the playing track. */
    INSIGHT("insight"),
    SONG("song"),
    ARTIST("artist"),
    ALBUM("album"),
    GENRE("genre"),
    SOUNDS_LIKE("sounds_like"),

    /**
     * An answer of *Search with AI* (#773) under an empty *Dates*, *Genres* or *Lyrics*, its kind's
     * key (`SearchKind.key`) as the card's subject: not a card the model writes for the reader, but
     * the model's reply, kept to be read and checked again (`AiSearches`).
     */
    SEARCH("search"),

    /**
     * The AI's genre list for a track (#762), asked before *Why is this …?* for a genre only
     * MusicBrainz's tags give, its mode's key (`GenreListMode.key`) as the card's subject: the
     * model's reply, kept to be read and checked again (`GenreLists`), as long as a reason is.
     */
    GENRE_LIST("genre_list"),
}
