package app.sleevesight.core.genres

/**
 * What the check of a track's vocals answered (#998; `design/design.md`, *Genre labels*): one web
 * search for the track with its vocals in view, the AI service reading the pages. By its fixed [key],
 * as the reply names it and the synthesis cache keeps it, never renamed.
 */
enum class VocalsAnswer(
    val key: String,
) {
    /** A page describes the track as instrumental: what left Instrumental weak is lifted. */
    INSTRUMENTAL("instrumental"),

    /** A page says the track has vocals: Instrumental is dropped, as lyrics found drop it. */
    VOCALS("vocals"),

    /** No page says either: Instrumental stays as it was, weak with its line. */
    UNKNOWN("unknown"),
    ;

    companion object {
        /** The answer named [key], or null for none of them. */
        fun of(key: String?): VocalsAnswer? = entries.firstOrNull { it.key == key?.trim()?.lowercase() }
    }
}

/**
 * Whether these genres call for the check of the track's vocals (#998; `design/design.md`, *Genre
 * labels*): Instrumental in doubt, and no free sign settling it. Its chip weak with a sign of vocals
 * ([WeakEvidence.SignOfVocals]: a vocalist, a lyricist of the work, its language, a featured artist),
 * or weak as the artist's hint (#1000, [WeakEvidence.ArtistHint]); or no row with Instrumental while
 * the song's work credits composers alone ([Singing.composersOnly]). Not with the track marked
 * instrumental, lyrics found, the check applied already ([Singing.checked]), or Instrumental weak only
 * for its few votes. With nothing that
 * suggests Instrumental, never: not every track is checked.
 */
fun TrackGenres.wantsVocalsCheck(): Boolean {
    if (singing.lifted || singing.dropsInstrumental || singing.checked != null) return false
    val tagged = everyLabel.any { it.genre.identity == TrackGenres.INSTRUMENTAL }
    return if (tagged) {
        when (weakEvidence(TrackGenres.INSTRUMENTAL)) {
            is WeakEvidence.SignOfVocals, is WeakEvidence.ArtistHint -> true
            else -> false
        }
    } else {
        singing.composersOnly
    }
}

/**
 * These genres with the check of the track's vocals answered [answer] by [model] (#998, its name as
 * the AI settings show it), as [Singing.checked]:
 *
 * - [VocalsAnswer.INSTRUMENTAL]: what left Instrumental weak is lifted ([Singing.lifted]); where no
 *   row has it (the song's work crediting composers alone), [instrumental], the vocabulary's label of
 *   it, joins the track's row marked AI, credited to the model, as the AI's genre list adds its genres
 *   (#762), while the row has room for it.
 * - [VocalsAnswer.VOCALS]: Instrumental dropped from every row, combined again without it, as lyrics
 *   found drop it, when [drops]; else (an answer to a tap on this play, the chip left weak with the
 *   answer's line) the genres as they are.
 * - [VocalsAnswer.UNKNOWN]: as they are, the answer kept, so the check is not wanted again.
 *
 * Itself once the check is applied, or with the track marked instrumental, where Instrumental stands.
 */
fun TrackGenres.withVocals(
    answer: VocalsAnswer,
    model: String,
    instrumental: GenreLabel? = null,
    drops: Boolean = true,
): TrackGenres {
    if (singing.checked != null || singing.marked) return this
    val weighed = singing.copy(checked = answer)
    return when (answer) {
        VocalsAnswer.UNKNOWN -> copy(singing = weighed)
        VocalsAnswer.VOCALS -> if (drops) withoutInstrumental(weighed) else this
        VocalsAnswer.INSTRUMENTAL -> {
            val tagged = everyLabel.any { it.genre.identity == TrackGenres.INSTRUMENTAL }
            if (tagged || instrumental == null) copy(singing = weighed) else withChecked(instrumental, model, weighed)
        }
    }
}

/**
 * These genres without Instrumental, as [weighed] says ([Singing.dropsInstrumental]): combined again
 * from the sources' labels without it, so another label takes its room, the artists' descriptions and
 * the AI's genre list after them; genres built without the sources' labels (previews, tests) lose it
 * from their rows alone. What LRCLIB's lyrics (#997) and the check's vocals (#998) do.
 */
fun TrackGenres.withoutInstrumental(weighed: Singing): TrackGenres {
    val combined =
        if (given.isEmpty()) {
            copy(rows = TrackGenres.withoutInstrumental(rows))
        } else {
            combineGenres(given, credited, weighed)
        }
    val found = combined.copy(singing = weighed, descriptions = descriptions)
    return aiModel?.let { found.withAi(aiListed, it, aiSources) } ?: found
}

// [instrumental] on the track's row, marked AI and credited to [model] (#998), after its labels, while
// the row has room; a track row made for it where there was none.
private fun TrackGenres.withChecked(
    instrumental: GenreLabel,
    model: String,
    weighed: Singing,
): TrackGenres {
    val label =
        TrackLabel(
            instrumental.copy(url = null, level = GenreLevel.TRACK, artist = null, votes = null),
            emptyList(),
            votes = null,
            aiModel = model,
        )
    val track = rows.firstOrNull { it.level == GenreLevel.TRACK }
    if (track != null && track.labels.size >= GenreVocabulary.MAX_LABELS) return copy(singing = weighed)
    val row = GenreRow(GenreLevel.TRACK, null, track?.labels.orEmpty() + label)
    val shown = if (track == null) listOf(row) + rows else rows.map { if (it === track) row else it }
    return copy(rows = shown, singing = weighed)
}
