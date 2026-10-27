package app.sleevesight.core.pipeline

import app.sleevesight.core.evidence.EvidenceLine
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.genres.GenreVocabulary

/**
 * Whether the sources say anything about how the track sounds (#664): when they say nothing, a
 * genre's reason opens with the app's own line saying so, and where the label came from, so the
 * model need not write about the evidence or its gaps (`GenreReasonPrompt`), a sentence nothing can
 * back. Decided in code, the same for the same evidence, from the evidence the reason is written
 * from.
 *
 * A line speaks of the sound when it is about the track itself ([PromptEvidence.TRACK_SCOPES]: the
 * song, its original, a web result; not the artist, whose other work is another matter, nor the
 * player's release line), is not a count or list of what listeners did ([EvidenceLine.aggregate]:
 * Last.fm's tags, where the label itself comes from), and, outside quotation marks (a title,
 * "“Rock and Roll” was written by …", says nothing of the sound), names a genre of the vocabulary
 * or one of [SOUND_WORDS]: instruments, voice, melody, rhythm, arrangement, style. A word list
 * misses some ways of describing a sound, and then the line is not shown; it never shows for
 * evidence that names an instrument or a genre, as a MusicBrainz performer's credit does ("…
 * (guitar, vocals)").
 */
object SoundEvidence {
    /** Whether no line of [evidence] speaks of the sound, a genre's name taken from [vocabulary]. */
    fun silent(
        evidence: EvidenceSet,
        vocabulary: GenreVocabulary,
    ): Boolean {
        val genres = vocabulary.tags.map(::fold).filter(String::isNotEmpty)
        return evidence.lines.none { speaks(it, genres) }
    }

    // Whether [line] speaks of the sound, [genres] folded.
    private fun speaks(
        line: EvidenceLine,
        genres: List<String>,
    ): Boolean {
        if (line.aggregate || line.scope !in PromptEvidence.TRACK_SCOPES) return false
        val words = " ${fold(QUOTED.replace(line.text, " "))} "
        return SOUND_WORDS.any { " $it " in words } || genres.any { " $it " in words }
    }

    // Lower case, each run of anything but letters and digits one space: "Hip-hop" is "hip hop".
    private fun fold(text: String): String =
        buildString(text.length) {
            var space = true
            for (c in text.lowercase()) {
                if (c.isLetterOrDigit()) {
                    append(c)
                    space = false
                } else if (!space) {
                    append(' ')
                    space = true
                }
            }
        }.trimEnd()

    // A quotation in curly or straight double quotation marks, as titles are quoted.
    private val QUOTED = Regex("“[^”]*”|\"[^\"]*\"")

    /** The words that speak of a sound: instruments, voice, melody, rhythm, arrangement, style. */
    val SOUND_WORDS: Set<String> =
        listOf(
            "sound sounds sonic sonically instrument instruments instrumental instrumentation",
            "arrangement arrangements arranged orchestra orchestral orchestration",
            "melody melodies melodic harmony harmonies",
            "rhythm rhythms rhythmic tempo beat beats groove riff riffs chord chords",
            "vocal vocals vocalist voice singing",
            "guitar guitars guitarist bass bassist drum drums drummer percussion",
            "piano pianist keyboard keyboards synth synths synthesizer synthesizers",
            "organ violin violins cello strings horn horns brass",
            "saxophone sax trumpet trombone flute accordion harmonica banjo mandolin",
            "sample samples sampled sampling acoustic electric ballad",
            "genre style styles influenced influences",
        ).flatMap { it.split(' ') }.toSet()
}
