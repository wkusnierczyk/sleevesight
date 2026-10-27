package app.sleevesight.core.resolution

/**
 * The artists' ids of a run of two or more consecutive [credits] that, written as MusicBrainz writes
 * the credit (the names, as credited or as MusicBrainz names the artists, with the join phrases between
 * them), is [artist]'s name by [NameMatcher.sameName]; null when no run is. A joint credit (#661):
 * Spotify's "Derya Yıldırım & Grup Şimşek" is MusicBrainz's "Derya Yıldırım" and "Grup Şimşek" joined
 * by " & ", and "A & B" is also the first two of "A and B feat. C".
 */
internal fun NameMatcher.jointCredit(
    credits: List<MbCredit>,
    artist: String,
): List<String>? {
    for (start in credits.indices) {
        for (end in start + 2..credits.size) {
            val run = credits.subList(start, end)
            val written =
                listOf(MbCredit::name, MbCredit::artistName).map { name ->
                    run.dropLast(1).joinToString("") { name(it) + it.joinPhrase } + name(run.last())
                }
            if (written.any { sameName(it, artist) }) return run.map { it.artistMbid }
        }
    }
    return null
}
