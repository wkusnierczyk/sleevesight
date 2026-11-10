package app.sleevesight.core.normalizer

import app.sleevesight.core.model.TitleComposer
import app.sleevesight.core.text.JS_WHITESPACE_CLASS

/**
 * Finds the composer a title names first (#1129): players file classical music under its composer,
 * "Mozart: Piano Sonata No. 16 in C Major, K. 545 …", and credit them first among the artists
 * ("Wolfgang Amadeus Mozart, Khatia Buniatishvili, …"), where MusicBrainz's title has no such lead
 * and its recording credits the performers.
 *
 * The lead is everything before the title's first colon, followed by whitespace and more title. It
 * names a credited artist when, as [fold] spells them (`Normalizer.foldForKey`: case, accents and
 * punctuation aside), its words are the artist's last words: all of them ("Wolfgang Amadeus Mozart:"),
 * the surname ("Mozart:"), or more ("Vaughan Williams:" for Ralph Vaughan Williams). A word before
 * the last may be an initial ("J.S. Bach:", "C.P.E. Bach:"); the last is whole. A lead that names
 * no credited artist ("Interlude: …", "Star Wars: Main Title") is no composer, and the title is
 * searched as it is. Of several credited artists it could name, the first credited.
 *
 * Only with another artist credited besides: a lead that names the only credited artist ("Chopin:
 * Nocturne …" by Frédéric Chopin alone) is no composer, since with no performer named, any recording
 * credited to them with that title would do; the title is searched whole, as before.
 *
 * Not recognised: a catalogue's surname-first form ("MOZART, W.A.: Piano Sonata …", as Naxos files
 * it), whose words are not the artist's last ones; its title is searched whole.
 */
object ComposerPrefix {
    /** The credited artist of [artists] that [title]'s lead names, with the lead; null for none. */
    fun find(
        title: String,
        artists: List<String>,
        fold: (String) -> String,
    ): TitleComposer? {
        val lead = LEAD.find(title)?.takeIf { it.value.length < title.length }
        val words = lead?.let { words(fold(it.groupValues[1])) }.orEmpty()
        val artist =
            artists.takeIf { words.isNotEmpty() && it.distinct().size > 1 }?.firstOrNull {
                names(
                    words,
                    words(fold(it)),
                )
            }
        return if (lead == null || artist == null) null else TitleComposer(artist, lead.value)
    }

    // Whether [lead]'s words are [artist]'s last ones, each before the last whole or its initial.
    private fun names(
        lead: List<String>,
        artist: List<String>,
    ): Boolean {
        if (lead.size > artist.size) return false
        val tail = artist.takeLast(lead.size)
        return lead.indices.all { i ->
            val word = lead[i]
            word == tail[i] || (i < lead.lastIndex && word.length == 1 && tail[i].startsWith(word))
        }
    }

    private fun words(folded: String): List<String> = folded.split(' ').filter { it.isNotEmpty() }

    // The text before the first colon, the colon and the whitespace after it. No braces: ICU reads
    // the pattern as the JVM does.
    private val LEAD = Regex("^([^:]+):$JS_WHITESPACE_CLASS+")
}
