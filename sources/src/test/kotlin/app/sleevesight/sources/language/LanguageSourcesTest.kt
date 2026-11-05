package app.sleevesight.sources.language

import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.language.Languages
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.resolution.IdentityCheck
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.resolution.Wd
import app.sleevesight.core.resolution.WdEntity
import app.sleevesight.core.sources.LanguageQuery
import app.sleevesight.core.sources.LocalName
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.ResolvedArtist
import app.sleevesight.sources.musicbrainz.MusicBrainzClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What Wikidata and MusicBrainz record of a track's language, and its names in it (#902), asked by
 * identifiers only. Synthetic items after Wikidata's for "Tum Hi Ho" (Q15322556), its film "Aashiqui 2"
 * (Q4662712) and Arijit Singh (Q4790608), as they stood on 2026-11-05.
 */
class LanguageSourcesTest {
    private val song =
        WdEntity(
            "Q15322556",
            "Tum Hi Ho",
            "Tum Hi Ho",
            mapOf(
                Wd.INSTANCE_OF to listOf("Q105543609"),
                Wd.PERFORMER to listOf("Q4790608"),
                Wd.LANGUAGE to listOf("Q1568"),
                "P9899" to listOf("Q4662712"),
            ),
            labels = mapOf("en" to "Tum Hi Ho", "hi" to "तुम ही हो"),
            sitelinks = mapOf("enwiki" to "Tum Hi Ho", "hiwiki" to "तुम ही हो"),
        )
    private val film =
        WdEntity(
            "Q4662712",
            "Aashiqui 2",
            "Aashiqui 2",
            mapOf(Wd.ORIGINAL_LANGUAGE to listOf("Q1568", "Q1617")),
            labels = mapOf("hi" to "आशिक़ी 2"),
            sitelinks = mapOf("hiwiki" to "आशिक़ी 2"),
        )
    private val singer =
        WdEntity(
            "Q4790608",
            "Arijit Singh",
            "Arijit Singh",
            mapOf(Wd.MUSICBRAINZ_ARTIST to listOf("mb-arijit")),
            labels = mapOf("hi" to "अरिजीत सिंह"),
            sitelinks = mapOf("hiwiki" to "अरिजीत सिंह"),
        )
    private val wikidata =
        FakeWikidata(listOf(song, film, singer).associateBy { it.id }, byMbid = mapOf("mb-arijit" to "Q4790608"))

    // MusicBrainz's lookup of the recording's releases, three with a tracklist language recorded.
    private val musicBrainz =
        RoutedNet { request ->
            if ("/recording/rec-tum" in request.url) {
                """{"id":"rec-tum","title":"Tum Hi Ho","releases":[
                  {"id":"r1","title":"Aashiqui 2","text-representation":{"language":"hin","script":"Latn"}},
                  {"id":"r2","title":"Best Of","text-representation":{"language":null,"script":null}},
                  {"id":"r3","title":"Romance","text-representation":{"language":"eng","script":"Latn"}},
                  {"id":"r4","title":"Aashiqui 2","text-representation":{"language":"hin","script":"Latn"}}]}"""
            } else {
                null
            }
        }

    private val names = NameMatcher(JvmAccentFolder)
    private val sources =
        LanguageSources(wikidata, IdentityCheck(wikidata, names), MusicBrainzClient(musicBrainz), null, null)

    private val resolution =
        Resolution(
            outcome = Outcome.SongArticle,
            recordingMbid = "rec-tum",
            artists = listOf(ResolvedArtist("Arijit Singh", "mb-arijit")),
            wikidataIds = listOf("Q15322556"),
        )

    @Test
    fun `the work's languages are the song's, then the film's it was made for`() =
        runTest {
            assertEquals(Lookup.Found(listOf("Q1568", "Q1617")), sources.workLanguages(resolution))
            assertEquals(listOf(listOf("Q15322556"), listOf("Q4662712")), wikidata.asked)
            assertEquals(Lookup.Found(emptyList()), sources.workLanguages(Resolution.NONE))
        }

    @Test
    fun `the releases' tracklist languages, most releases first`() =
        runTest {
            assertEquals(Lookup.Found(listOf("hin", "eng")), sources.releaseLanguages(resolution))
            assertEquals(Lookup.Found(emptyList()), sources.releaseLanguages(Resolution.NONE))
        }

    @Test
    fun `the names in the language are the song the check confirmed, its film and its artist, with their articles`() =
        runTest {
            val track = Normalizer(JvmAccentFolder).normalize("Tum Hi Ho", "Arijit Singh")
            val items = sources.items(LanguageQuery(track, resolution, Languages.ofCode("hi")!!))
            assertEquals(LocalName("Q15322556", "तुम ही हो", "तुम ही हो", "Tum Hi Ho"), items.song)
            assertEquals(Scope.SONG, items.songScope)
            assertEquals(listOf(LocalName("Q4662712", "आशिक़ी 2", "आशिक़ी 2", "Aashiqui 2")), items.comes)
            assertEquals(
                mapOf("Arijit Singh" to LocalName("Q4790608", "अरिजीत सिंह", "अरिजीत सिंह", "Arijit Singh")),
                items.artists,
            )
            assertEquals(false, items.unreachable)
            assertEquals(listOf(listOf("Q15322556") to "hi", listOf("Q4662712", "Q4790608") to "hi"), wikidata.askedIn)
        }
}
