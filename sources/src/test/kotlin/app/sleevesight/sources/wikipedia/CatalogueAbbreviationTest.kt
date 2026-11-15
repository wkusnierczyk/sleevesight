package app.sleevesight.sources.wikipedia

import app.sleevesight.sources.lastfm.LastFmBio
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A sentence does not end at a catalogue abbreviation before its number (#1192). The leads are real
 * English Wikipedia leads (plain-text extracts, 2026-11-15), each named by its article.
 */
class CatalogueAbbreviationTest {
    @Test
    fun `an album's lead is not cut after Nos`() {
        // "Composition/Improvisation Nos. 1, 2 & 3".
        val lead =
            "Composition/Improvisation Nos. 1, 2 & 3 is a live album by jazz saxophonist and composer " +
                "Roscoe Mitchell recorded in Germany in September 2004 and released on ECM in 2007."
        assertEquals(listOf(lead), WikipediaSource.split(lead))
    }

    @Test
    fun `a work's lead is not cut after Op or No`() {
        // "Piano Sonata No. 14 (Beethoven)".
        val moonlight =
            "The Piano Sonata No. 14 in C♯ minor, marked Quasi una fantasia, Op. 27, No. 2, is a piano " +
                "sonata by Ludwig van Beethoven, completed in 1801 and dedicated in 1802 to his pupil " +
                "Countess Julie Guicciardi."
        // "Symphony No. 9 (Beethoven)".
        val ninth =
            "The Symphony No. 9 in D minor, Op. 125, is a choral symphony, the final complete symphony by " +
                "Ludwig van Beethoven, composed between 1822 and 1824."
        val performed = "It was first performed in Vienna on 7 May 1824."
        assertEquals(listOf(moonlight), WikipediaSource.split(moonlight))
        assertEquals(listOf(ninth, performed), WikipediaSource.split("$ninth $performed"))
    }

    @Test
    fun `Nos before a number mid-lead keeps its sentence whole`() {
        // "Piano Sonata No. 11 (Mozart)"; `K.` is an initial, as it was before.
        val published = "The sonata was published by Artaria in 1784, alongside Nos. 10 and 12 (K. 330 and K. 332)."
        val rondo = "The third movement of this sonata, the \"Rondo alla Turca\", is often heard on its own."
        assertEquals(listOf(published, rondo), WikipediaSource.split("$published $rondo"))
    }

    @Test
    fun `Hoboken's Roman group after Hob is a catalogue number`() {
        // "Trumpet Concerto (Haydn)".
        val composed =
            "Joseph Haydn composed the Concerto per il Clarino (Hob. VIIe/1) (Trumpet Concerto in E♭ major) " +
                "in 1796 for the trumpet virtuoso Anton Weidinger."
        val age = "Joseph Haydn was 64 years of age."
        assertEquals(listOf(composed, age), WikipediaSource.split("$composed $age"))
    }

    @Test
    fun `Bartok's Sz is a catalogue abbreviation`() {
        // "Music for Strings, Percussion and Celesta".
        val lead =
            "Music for Strings, Percussion and Celesta, Sz. 106, BB 114 is one of the best-known " +
                "compositions by the Hungarian composer Béla Bartók."
        assertEquals(listOf(lead), WikipediaSource.split(lead))
    }

    @Test
    fun `the other catalogue abbreviations hold before a number`() {
        // Made-up sentences in each abbreviation's usual form.
        for (sentence in listOf(
            "Beethoven's Piano Trios, Opp. 1 and 70, were published in Vienna.",
            "Mozart's Kyrie, K. Anh. 14, is lost.",
            "Scarlatti's Sonata in D minor, Kk. 9, is often played.",
            "C. P. E. Bach's Magnificat, Wq. 215, was first heard in 1749.",
            "The Sinfonie Nr. 1 was first performed in Leipzig.",
        )) {
            assertEquals(listOf(sentence, "It ends here."), LastFmBio.split("$sentence It ends here."), sentence)
        }
    }

    @Test
    fun `No without a number after it still ends a sentence`() {
        assertEquals(listOf("The answer was No.", "He left."), LastFmBio.split("The answer was No. He left."))
        // A Roman numeral alone may be the pronoun, so it is not taken for a catalogue number.
        assertEquals(listOf("They said No.", "I left."), LastFmBio.split("They said No. I left."))
        // Nor is a year that opens the next sentence (#1192's second review).
        assertEquals(
            listOf("The answer was No.", "1975 was the year."),
            LastFmBio.split("The answer was No. 1975 was the year."),
        )
        // Before a number that is not a year, the sentence goes on: a known limit.
        assertEquals(
            listOf("The answer was No. 42 was the reply."),
            LastFmBio.split("The answer was No. 42 was the reply."),
        )
    }

    @Test
    fun `an initial whose lowercase is longer, as İ, is still an initial`() {
        // "İlhan Mimaroğlu", as initialled; lowercased, `İ` is `i` and a combining dot.
        val sentence = "İ. Mimaroğlu was an electronic composer."
        assertEquals(
            listOf(sentence, "He was born in Istanbul."),
            LastFmBio.split("$sentence He was born in Istanbul."),
        )
    }

    @Test
    fun `the end of a hyphenated word is not a catalogue abbreviation`() {
        // A plain hyphen, and a non-breaking one (U+2011).
        val firsts = listOf("The musicians formed a co-op.", "Touring was a no-no.", "Encores were a no\u2011no.")
        for (first in firsts) {
            assertEquals(
                listOf(first, "1984 marked its first release."),
                LastFmBio.split("$first 1984 marked its first release."),
                first,
            )
        }
    }

    @Test
    fun `an ordinary op or no before a year ends its sentence`() {
        for (first in listOf("The band posed for a photo op.", "Their answer was a firm no.")) {
            assertEquals(
                listOf(first, "1984 saw their first release."),
                LastFmBio.split("$first 1984 saw their first release."),
                first,
            )
        }
        // Nor does a decade.
        assertEquals(
            listOf("Their answer was a firm no.", "1980s synth-pop then took over."),
            LastFmBio.split("Their answer was a firm no. 1980s synth-pop then took over."),
        )
        // A year-like number with more catalogue after it is still a catalogue number.
        val numbered = "The Sonata, Op. 1984, No. 2, is a made-up work."
        assertEquals(listOf(numbered), LastFmBio.split(numbered))
    }

    @Test
    fun `a posthumous opus is not cut after posth`() {
        // "Fantaisie-Impromptu".
        val lead =
            "Frédéric Chopin's Fantaisie-Impromptu (Polish: Fantazja-Impromptu) in C♯ minor, Op. posth. 66, " +
                "WN 46 is a solo piano composition."
        val composed =
            "It was composed in 1834 and published posthumously in 1855 despite Chopin's instruction that none " +
                "of his unpublished manuscripts be published."
        assertEquals(listOf(lead, composed), WikipediaSource.split("$lead $composed"))
        // Before a capital word, `posth.` ends its sentence.
        assertEquals(
            listOf("The opus was marked posth.", "Then it was printed."),
            LastFmBio.split("The opus was marked posth. Then it was printed."),
        )
    }

    @Test
    fun `a sentence ending in Op before a capital word still ends`() {
        assertEquals(
            listOf("They called it a pop op.", "Then came rock."),
            LastFmBio.split("They called it a pop op. Then came rock."),
        )
    }
}
