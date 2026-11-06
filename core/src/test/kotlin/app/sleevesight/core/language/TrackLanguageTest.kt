package app.sleevesight.core.language

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The track's language (#902): named by a script other than Latin, or by what MusicBrainz and
 * Wikidata record, strongest first; English, or nothing, is no language to search in.
 */
class TrackLanguageTest {
    private val asked = mutableListOf<String>()

    // A recorded hint named [name], giving [codes] (ISO 639-3) when asked, and noting that it was.
    private fun hint(
        name: String,
        vararg codes: String,
    ): suspend () -> List<Language> =
        {
            asked += name
            TrackLanguage.ofIso3(codes.toList())
        }

    private suspend fun find(
        vararg names: String,
        lyrics: List<String> = emptyList(),
        wikidata: List<String> = emptyList(),
        releases: List<String> = emptyList(),
    ): String? =
        TrackLanguage
            .find(
                names.toList(),
                listOf(
                    hint("lyrics", *lyrics.toTypedArray()),
                    hint("wikidata", *wikidata.toTypedArray()),
                    {
                        asked += "releases"
                        TrackLanguage.ofReleases(releases, TrackLanguage.scriptOf(names.toList()))
                    },
                ),
            )?.code

    @Test
    fun `scripts that name their language decide alone, asking nothing`() =
        runTest {
            assertEquals("ta", find("கண்ணான கண்ணே", "Sid Sriram"))
            assertEquals("ko", find("봄날", "BTS"))
            assertEquals("ja", find("夜に駆ける", "YOASOBI"))
            assertEquals("th", find("ขอใจเธอแลกเบอร์โทร", "Golf"))
            assertEquals("el", find("Σ' αγαπώ", "Άννα Βίσση"))
            assertEquals("he", find("שיר", "Artist"))
            assertEquals("ka", find("სიმღერა", "Artist"))
            assertEquals("hy", find("Երգ", "Artist"))
            assertEquals("bn", find("আমার সোনার বাংলা", "Artist"))
            assertEquals("pa", find("ਗੀਤ", "Artist"))
            assertEquals("gu", find("ગીત", "Artist"))
            assertEquals("te", find("పాట", "Artist"))
            assertEquals("kn", find("ಹಾಡು", "Artist"))
            assertEquals("ml", find("ഗാനം", "Artist"))
            assertEquals(emptyList(), asked)
        }

    @Test
    fun `an artist's script counts when the title is in Latin letters`() =
        runTest {
            assertEquals("ko", find("Dynamite", "방탄소년단"))
        }

    @Test
    fun `kana makes Han Japanese, and Hangul Korean`() =
        runTest {
            assertEquals("ja", find("東京ラブストーリー", "Artist"))
            assertEquals("ko", find("사랑 愛", "Artist"))
        }

    @Test
    fun `Devanagari is Hindi unless a recorded language written in it says otherwise`() =
        runTest {
            assertEquals("hi", find("तुम ही हो", "अरिजीत सिंह"))
            assertEquals("mr", find("झिंगाट", "Ajay-Atul", wikidata = listOf("mar")))
            assertEquals("ne", find("रेशम फिरिरी", "Artist", lyrics = listOf("nep")))
            // A recorded language in another script does not move it.
            assertEquals("hi", find("तुम ही हो", "Artist", lyrics = listOf("eng")))
        }

    @Test
    fun `a shared script only confirms a recorded language written in it`() =
        runTest {
            assertNull(find("Кино", "Группа крови"))
            assertEquals("ru", find("Группа крови", "Кино", releases = listOf("rus")))
            assertEquals("uk", find("Стефанія", "Kalush", lyrics = listOf("ukr")))
            assertNull(find("月亮代表我的心", "邓丽君"))
            assertEquals("zh", find("月亮代表我的心", "邓丽君", wikidata = listOf("cmn")))
            assertEquals("ar", find("أغنية", "Artist", lyrics = listOf("ara")))
            // A recorded language the script does not write is passed over for one it does.
            assertEquals("fa", find("آهنگ", "Artist", lyrics = listOf("eng"), wikidata = listOf("fas")))
        }

    @Test
    fun `a title in Latin letters takes the first recorded language that is not English`() =
        runTest {
            // A Bollywood title transliterated: the tracklist's recorded language names it.
            assertEquals("hi", find("Tum Hi Ho", "Arijit Singh", releases = listOf("hin")))
            assertEquals("pt", find("Evidências", "Chitãozinho & Xororó", lyrics = listOf("por")))
            // Several languages in one hint: its first that is not English.
            assertEquals("hi", find("Tum Hi Ho", "Arijit Singh", lyrics = listOf("eng", "hin")))
        }

    @Test
    fun `the releases' hint is their majority, so one pressing abroad does not name the language`() =
        runTest {
            assertEquals("en", find("Sundown", "X", releases = listOf("eng", "eng", "spa")))
            assertEquals("hi", find("Tum Hi Ho", "Arijit Singh", releases = listOf("eng", "hin", "hin")))
            // A tie in MusicBrainz's order.
            assertEquals("hi", find("Tum Hi Ho", "Arijit Singh", releases = listOf("hin", "eng")))
            assertEquals(listOf("en"), TrackLanguage.ofReleases(listOf("xyz", "eng")).map { it.code })
            assertEquals(emptyList(), TrackLanguage.ofReleases(listOf("zxx")))
            // In a script other than Latin, the majority among the languages written in it.
            assertEquals("mr", find("झिंगाट", "Ajay-Atul", releases = listOf("eng", "eng", "mar")))
        }

    @Test
    fun `a stronger hint recording English alone settles it, whatever a weaker hint records`() =
        runTest {
            // English lyrics on a film's soundtrack whose tracklist MusicBrainz records as Hindi.
            assertEquals("en", find("Main Hoon Na", "Artist", lyrics = listOf("eng"), releases = listOf("hin")))
            assertEquals(listOf("lyrics"), asked)
            asked.clear()
            assertEquals("en", find("Main Hoon Na", "Artist", wikidata = listOf("eng"), releases = listOf("hin")))
            assertEquals(listOf("lyrics", "wikidata"), asked)
            // A hint that records nothing, or no known language, settles nothing.
            assertEquals("hi", find("Main Hoon Na", "Artist", lyrics = listOf("zxx"), releases = listOf("hin")))
        }

    @Test
    fun `the hints are asked strongest first, each only while none has decided`() =
        runTest {
            assertEquals("hi", find("Tum Hi Ho", "Arijit Singh", lyrics = listOf("hin"), releases = listOf("pan")))
            assertEquals(listOf("lyrics"), asked)
            asked.clear()
            assertEquals("pa", find("Tum Hi Ho", "Arijit Singh", wikidata = listOf("pan"), releases = listOf("hin")))
            assertEquals(listOf("lyrics", "wikidata"), asked)
        }

    @Test
    fun `English is told apart from no language, and no linguistic content or nothing recorded is none`() =
        runTest {
            // English, told apart from none: no search either way.
            assertEquals("en", find("Sundown", "Gordon Lightfoot", lyrics = listOf("eng")))
            assertNull(find("Sundown", "Gordon Lightfoot", lyrics = listOf("zxx"), wikidata = listOf("mul", "und")))
            // A Polish track with no recorded language: its letters are Latin, so nothing names it.
            assertNull(find("Kocham cię, kochanie moje", "Maanam"))
            assertEquals(listOf("lyrics", "wikidata", "releases"), asked.takeLast(3))
        }

    @Test
    fun `an unknown code or item is no hint`() {
        assertEquals(emptyList(), TrackLanguage.ofIso3(listOf("xyz", "zxx")))
        assertEquals(listOf("hi"), TrackLanguage.ofItems(listOf("Q99999999", "Q1568", "Q1568")).map { it.code })
        assertEquals(listOf("el"), TrackLanguage.ofItems(listOf("Q9129", "Q36510")).map { it.code })
    }

    @Test
    fun `a script is told by its letters, digits and punctuation aside`() {
        assertNull(Script.of("Sundown 1974!"))
        assertEquals(Script.DEVANAGARI, Script.of("Aashiqui 2: तुम ही हो"))
        assertEquals(Script.HAN, Script.of("𠀀 中"))
        assertEquals("hiwiki", Languages.ofCode("hi")?.sitelink)
        assertEquals("zh_yuewiki", Languages.ofCode("yue")?.sitelink)
        assertEquals("zh-yue.wikipedia.org", Languages.ofCode("yue")?.host)
    }
}
