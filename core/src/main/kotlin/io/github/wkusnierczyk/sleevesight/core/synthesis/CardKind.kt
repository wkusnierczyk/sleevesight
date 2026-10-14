package io.github.wkusnierczyk.sleevesight.core.synthesis

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
}
