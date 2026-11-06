package app.sleevesight.core.genres

import app.sleevesight.core.evidence.EvidenceLine
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.GenreReasons
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.pipeline.nowPlaying
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmResourceReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The genres an artist's description names (#1000): a plain match of the list's names against the
 * artist's own lines, on word boundaries, a name of several words whole, spelling variants folded;
 * one per genre per artist, citing the first line, quoted around the genre; a row of their own
 * beside the artist's tags, their chips after the tags', a tag winning; Instrumental from them the
 * artist's hint (#998): weak, dropped with lyrics found.
 */
class DescribedGenresTest {
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)

    private val at = Instant.parse("2026-11-06T00:00:00Z")

    private fun line(
        id: String,
        text: String,
        source: SourceId = SourceId.WEB,
        scope: Scope = Scope.ARTIST,
        about: String? = null,
    ) = EvidenceLine(id, source, scope, text, "https://example.org/$id", at, about = about)

    private fun named(
        text: String,
        artists: List<String> = listOf(ISLANDMAN),
    ) = describedGenres(vocabulary, listOf(line("S1", text)), artists).flatMap { row ->
        row.labels.map { it.genre.label }
    }

    private fun artist(
        name: String,
        vararg tags: String,
    ) = vocabulary.labelsFor(tags.toList(), level = GenreLevel.ARTIST, artist = name)

    // *Eros* by islandman (#1000): the artist's tags Ambient, Electronic, House, and the web line
    // that calls the act instrumental.
    private val erosLine =
        line(
            "S8",
            "Islandman is described as an instrumental electro-acoustic, neo-psychedelic band from Turkey " +
                "that reimagines the deep-rooted sounds of Anatolia. Their sound combines Turkish psychedelia " +
                "and African roots music with electronic structures and psychedelic guitar.",
        )

    private fun eros(singing: Singing = Singing.NONE): TrackGenres =
        combineGenres(
            mapOf(LabelSource.LASTFM to artist(ISLANDMAN, "ambient", "electronic", "house")),
            listOf(ISLANDMAN),
            singing,
        ).withDescribed(describedGenres(vocabulary, listOf(erosLine), listOf(ISLANDMAN)))

    @Test
    fun `Eros's line gives a weak Instrumental after the tags, quoted around the genre and citing its line`() {
        val genres = eros()
        val row = genres.described.single()
        assertEquals(ISLANDMAN, row.artist)
        assertTrue(row.described)
        // Electronic is one of the artist's tags already: not on the described row. "Turkish
        // psychedelia" is Anatolian Rock's synonym (#1011): that genre, as the line spells it;
        // "neo-psychedelic" is no Neo-Psychedelia, nor "psychedelic guitar" Psychedelic.
        assertEquals(listOf("Instrumental", "Turkish Psychedelia"), row.labels.map { it.genre.label })
        val turkish = row.labels.last()
        assertEquals(vocabulary.identity("anatolian rock"), turkish.genre.identity)
        val chips = genres.chips()
        assertEquals(
            listOf("Ambient", "Electronic", "House", "Instrumental", "Turkish Psychedelia"),
            chips.map { it.label.genre.label },
        )
        assertEquals(listOf(false, false, false, true, false), chips.map { it.weak })
        val hint = assertIs<WeakEvidence.ArtistHint>(genres.weakEvidence(TrackGenres.INSTRUMENTAL))
        val quote = "Islandman is described as an instrumental electro-acoustic, neo-psychedelic band from Turkey…"
        assertEquals(DescribedIn("S8", quote), hint.where)
        assertEquals(DescribedIn("S8", quote), genres.describedIn(TrackGenres.INSTRUMENTAL))
        // In the table, right after the artist's tags.
        assertEquals(listOf(false, true), genres.table.map { it.described })
        val every = genres.everyLabel.map { it.genre.identity }
        assertEquals(listOf(TrackGenres.INSTRUMENTAL, "anatolianrock"), every.takeLast(2))
    }

    @Test
    fun `the recording marked instrumental lifts the hint, a vocalist keeps it weak, and lyrics found drop it`() {
        assertEquals(
            listOf(false),
            eros(Singing(instrumental = true))
                .chips()
                .filter {
                    it.label.genre.identity == TrackGenres.INSTRUMENTAL
                }.map { it.weak },
        )
        assertNull(eros(Singing(instrumental = true)).weakEvidence(TrackGenres.INSTRUMENTAL))
        assertIs<WeakEvidence.Vocalist>(
            eros(Singing(vocalists = listOf("Someone"))).weakEvidence(TrackGenres.INSTRUMENTAL),
        )
        val instrumental = { genres: TrackGenres -> genres.described.flatMap { it.labels }.map { it.genre.identity } }
        assertEquals(listOf("anatolianrock"), instrumental(eros(Singing(lyrics = true))))
        val found = eros().withLyricsFound()
        assertEquals(listOf("anatolianrock"), instrumental(found))
        assertEquals(
            listOf("Ambient", "Electronic", "House", "Turkish Psychedelia"),
            found.chips().map { it.label.genre.label },
        )
    }

    @Test
    fun `a name of several words is matched whole, the longest first, and spelling variants fold`() {
        assertEquals(listOf("Drum And Bass"), named("They make drum and bass records."))
        assertEquals(listOf("Indie Rock"), named("An indie rock band."))
        assertEquals(listOf("Hip-Hop"), named("Known for hip hop."))
        assertEquals(listOf("Hip-Hop"), named("Known for HIP-HOP."))
        assertEquals(listOf("Hip-Hop"), named("Known for hiphop."))
        assertEquals(listOf("Post-Punk"), named("A post punk revival act."))
        // Synonyms are one genre: the first named, once.
        assertEquals(listOf("R&B"), named("Their R&B and RnB songs."))
        assertEquals(listOf("R&B"), named("Their R & B songs."))
    }

    @Test
    fun `names are matched on word boundaries, a hyphenated word whole`() {
        assertEquals(emptyList(), named("A folklore collective."))
        assertEquals(emptyList(), named("An electro-acoustic, neo-psychedelic outfit."))
        assertEquals(emptyList(), named("Popular in Rockford."))
        assertEquals(listOf("Folk"), named("A folk act, not a folklore one."))
        // Not across a comma.
        assertEquals(emptyList(), named("Drum, and bass."))
    }

    @Test
    fun `only the artist's own lines in words name genres, and not the artist's own name`() {
        val lines =
            listOf(
                line("W1", "The song is a jazz standard.", SourceId.WIKIPEDIA, Scope.SONG),
                line("W2", "The album is a soul record.", SourceId.WIKIPEDIA, Scope.RELEASE),
                line("S1", "A disco page about the track.", scope = Scope.WEB),
                line("WD1", "Genre: country.", SourceId.WIKIDATA),
                line("L1", "Tags: funk, blues.", SourceId.LASTFM).copy(aggregate = true),
                line(
                    "L2",
                    "The Last.fm biography of Soul Collective says: \"Soul Collective play blues.\"",
                    SourceId.LASTFM,
                ),
            )
        val rows = describedGenres(vocabulary, lines, listOf("Soul Collective"))
        assertEquals(listOf("Blues"), rows.single().labels.map { it.genre.label })
        // Within the line's quotation marks.
        assertEquals(
            "Soul Collective play blues.",
            rows
                .single()
                .labels
                .single()
                .describedIn
                ?.quote,
        )
    }

    @Test
    fun `one match per genre per artist, citing the first line it is found in`() {
        val lines =
            listOf(
                line("W1", "They are a jazz trio.", SourceId.WIKIPEDIA),
                line("S2", "A jazz and blues trio."),
            )
        val labels = describedGenres(vocabulary, lines, listOf("Trio")).single().labels
        assertEquals(listOf("jazz" to "W1", "blues" to "S2"), labels.map { it.genre.identity to it.describedIn?.line })
        assertEquals("They are a jazz trio.", labels.first().describedIn?.quote)
    }

    @Test
    fun `with several artists each line is its artist's, each artist's row after their tags'`() {
        val lines =
            listOf(
                line("L1", "A jazz pianist.", SourceId.LASTFM, about = BOB),
                line("L2", "A blues singer.", SourceId.LASTFM, about = ANN),
                line("W1", "Bob is a soul singer.", SourceId.WIKIPEDIA),
                line("S1", "A funk band."),
            )
        val rows = describedGenres(vocabulary, lines, listOf(ANN, BOB))
        assertEquals(
            listOf(ANN to listOf("blues", "funk"), BOB to listOf("jazz", "soul")),
            rows.map { row -> row.artist to row.labels.map { it.genre.identity } },
        )
        val tags = mapOf(LabelSource.LASTFM to artist(ANN, "rock") + artist(BOB, "pop"))
        val genres = combineGenres(tags, listOf(ANN, BOB)).withDescribed(rows)
        assertEquals(
            listOf(ANN to false, ANN to true, BOB to false, BOB to true),
            genres.table.map { it.artist to it.described },
        )
        // An artist with no tags: their described row after the artists'.
        val alone = combineGenres(mapOf(LabelSource.LASTFM to artist(BOB, "pop")), listOf(ANN, BOB)).withDescribed(rows)
        assertEquals(listOf(BOB to false, BOB to true, ANN to true), alone.table.map { it.artist to it.described })
    }

    @Test
    fun `a genre a tag gives keeps the tag's chip, the AI's genre too, and the chips keep the described row's cap`() {
        val line = line("S1", "A rock, jazz, blues, funk, disco, reggae and ska band.")
        val found = describedGenres(vocabulary, listOf(line), listOf(ANN))
        val tags = mapOf(LabelSource.LASTFM to vocabulary.labelsFor(listOf("jazz"), level = GenreLevel.ALBUM))
        val genres = combineGenres(tags, listOf(ANN)).withDescribed(found)
        // The album's Jazz stays on the described row, at most five there.
        assertEquals(
            listOf("rock", "jazz", "blues", "funk", "disco"),
            genres.described.single().labels.map {
                it.genre.identity
            },
        )
        val chips = genres.chips()
        assertEquals(listOf("Jazz", "Blues", "Disco", "Funk", "Rock"), chips.map { it.label.genre.label })
        assertEquals(GenreLevel.ALBUM, chips.first().row.level)
        assertTrue(chips.drop(1).all { it.row.described })
        assertNull(genres.describedIn("jazz"))
        // The AI's genre list naming one: its chip, marked AI, last.
        val listed = genres.withAi(vocabulary.labelsFor(listOf("funk"), level = GenreLevel.TRACK), "Model")
        val withAi = listed.chips()
        assertEquals(listOf("Jazz", "Blues", "Disco", "Rock", "Funk"), withAi.map { it.label.genre.label })
        assertTrue(withAi.last().label.aiAdded)
        assertEquals(
            GenreVocabulary.MAX_LABELS,
            listed.described
                .single()
                .labels.size,
        )
    }

    @Test
    fun `the described genres reach no genre list the prompts carry, only their own question`() {
        val genres = eros()
        // Not among the tags' rows, which the prompts' genres and the AI's genre list read.
        assertFalse(genres.rows.flatMap { it.labels }.any { it.genre.identity == TrackGenres.INSTRUMENTAL })
        assertFalse(genres.all.any { it.genre.identity == TrackGenres.INSTRUMENTAL })
        val playing = nowPlaying("Eros", ISLANDMAN, "Island5")
        val track = TrackState(playing, Normalizer(JvmAccentFolder).normalize(playing), emptyMap(), genres = genres)
        // Its own question may name it: the lines it is from are in every prompt anyway.
        assertTrue(GenreReasons.prompted(track, TrackGenres.INSTRUMENTAL))
        assertTrue(track.genres.everyLabel.any { it.genre.identity == TrackGenres.INSTRUMENTAL })
    }

    @Test
    fun `a name that is an everyday word names a genre only in a music phrase`() {
        assertEquals(emptyList(), named("Their set filled the dance floor."))
        assertEquals(emptyList(), named("They recorded it in a house in Berlin."))
        assertEquals(emptyList(), named("Turkey is their country of origin."))
        assertEquals(emptyList(), named("They call it the soul of the city."))
        assertEquals(emptyList(), named("He was instrumental in founding the label."))
        assertEquals(listOf("House", "Soul"), named("A house producer and a soul singer."))
        assertEquals(listOf("Country"), named("Two country musicians."))
        // A name of several words, and a word that is only a genre, as they are.
        assertEquals(listOf("Indie Rock", "Jazz"), named("Indie rock, then jazz."))
        // Instrumental, but for "instrumental in" or "to".
        assertEquals(listOf("Instrumental"), named("An instrumental electro-acoustic, neo-psychedelic band."))
    }

    @Test
    fun `the everyday words of the wider list name a genre only in a music phrase`() {
        val phrases =
            listOf(
                "the dance floor" to "a dance act",
                "the noise of the city" to "a noise band",
                "a drone over the stage" to "a drone musician",
                "in full swing" to "a swing band",
                "Latin lyrics" to "a Latin singer",
                "the Sydney Opera House" to "an opera singer",
                "an industrial town" to "an industrial band",
                "psychedelic posters" to "a psychedelic band",
                "the gospel truth" to "a gospel singer",
                "an electro-acoustic guitar" to "an electro producer",
                "they fell into a trance" to "a trance DJ",
            )
        for ((prose, music) in phrases) {
            assertEquals(emptyList(), named("$prose."), prose)
            assertEquals(1, named("$music.").size, music)
        }
        // Only a spelling the list has: "neo-psychedelic" is no Neo-Psychedelia, "neo-psychedelia" is.
        assertEquals(emptyList(), named("A neo-psychedelic act."))
        assertEquals(1, named("Their neo-psychedelia.").size)
    }

    @Test
    fun `genres that chain before a music noun, and the nouns of musicians, make a music phrase`() {
        assertEquals(listOf("Ambient", "Electronic"), named("An ambient electronic duo."))
        assertEquals(listOf("Punk", "Soul"), named("A punk guitarist and soul vocalist."))
        assertEquals(listOf("Jazz", "Soul"), named("A jazz and soul singer."))
        assertEquals(listOf("Folk", "Singer-Songwriter"), named("A folk singer-songwriter."))
        assertEquals(listOf("House"), named("Two house DJs."))
        // Still not before anything else.
        assertEquals(listOf("Electronic"), named("An ambient electronic set in a house in Berlin."))
    }

    @Test
    fun `a Last fm line is quoted inside its attribution, other quotation marks kept`() {
        val wrapped = "The Last.fm biography of Trio says: \"They call their style \"jazz\" music.\""
        val at = wrapped.indexOf("jazz")
        assertEquals("They call their style \"jazz\" music.", quoteAround(wrapped, at..at + 3))
        val plain = "They call their style \"jazz\" music."
        val there = plain.indexOf("jazz")
        assertEquals(plain, quoteAround(plain, there..there + 3))
    }

    @Test
    fun `lyrics found let the next genre into a full described row, and a genre a rebuilt row gains leaves it`() {
        val line = line("S1", "An instrumental jazz, blues, funk, disco, reggae and ska band.")
        val found = describedGenres(vocabulary, listOf(line), listOf(ANN))
        val tags = combineGenres(mapOf(LabelSource.LASTFM to artist(ANN, "rock")), listOf(ANN)).withDescribed(found)
        assertEquals(listOf("instrumental", "jazz", "blues", "funk", "disco"), tags.described.single().identities())
        assertEquals(
            listOf("jazz", "blues", "funk", "disco", "reggae"),
            tags
                .withLyricsFound()
                .described
                .single()
                .identities(),
        )
        // The artist's five Last.fm tags with Instrumental leave no room for MusicBrainz's Soul; with
        // lyrics found, Soul takes it, and leaves the described row.
        val soul = describedGenres(vocabulary, listOf(line("S2", "A soul band.")), listOf(ANN))
        val full =
            combineGenres(
                mapOf(
                    LabelSource.LASTFM to artist(ANN, "instrumental", "rock", "pop", "punk", "metal"),
                    LabelSource.MUSICBRAINZ to artist(ANN, "soul"),
                ),
                listOf(ANN),
            ).withDescribed(soul)
        assertEquals(listOf("soul"), full.described.single().identities())
        val sung = full.withLyricsFound()
        assertTrue(
            sung.rows
                .single()
                .labels
                .any { it.genre.identity == "soul" },
        )
        assertTrue(sung.described.isEmpty())
    }

    private fun GenreRow.identities() = labels.map { it.genre.identity }

    @Test
    fun `a quote is the sentence around the genre, cut where long`() {
        assertEquals("They are a jazz trio.", quoteAround("They are a jazz trio.", 11..14))
        val long = "Formed in 1999. One two three four five six jazz seven eight nine ten eleven twelve. Later."
        val at = long.indexOf("jazz")
        assertEquals("…two three four five six jazz seven eight nine ten eleven…", quoteAround(long, at..at + 3))
    }

    private companion object {
        const val ISLANDMAN = "islandman"
        const val ANN = "Ann"
        const val BOB = "Bob"
    }
}
