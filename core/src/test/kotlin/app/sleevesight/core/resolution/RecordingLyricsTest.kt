package app.sleevesight.core.resolution

import app.sleevesight.core.sources.WorkLyrics
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the resolution keeps of the lyrics (#728): the looked-up work's lyrics pages, the languages
 * of every work the recording performs, and whether its performance of any is marked instrumental.
 */
class RecordingLyricsTest {
    private val search = listOf(recording("ours", "Song", "Act", 200, "1978"))

    private fun detail(vararg works: MbWorkLink) =
        MbRecordingDetail("ours", "Song", listOf(credit("Act")), "1978", works.toList(), emptyList(), emptyList())

    private fun work(
        mbid: String,
        lyricsUrls: List<String> = emptyList(),
        languages: List<String> = emptyList(),
    ) = MbWork(mbid, "Song", emptyList(), emptyList(), emptyList(), lyricsUrls, languages)

    @Test
    fun `the work's lyrics pages and languages, deduplicated, and not instrumental`() =
        runTest {
            val pages = listOf("https://genius.com/A-song-lyrics", "https://genius.com/B-song-lyrics")
            val musicBrainz =
                FakeMusicBrainz(
                    Lookup.Found(search),
                    mapOf("ours" to detail(MbWorkLink("w", "Song", cover = false, languages = listOf("eng")))),
                    mapOf("w" to work("w", pages + pages.first(), listOf("eng"))),
                )
            assertEquals(
                WorkLyrics(pages, listOf("eng"), instrumental = false),
                resolve(musicBrainz, track("Song", "Act")).lyrics,
            )
        }

    @Test
    fun `several works give every language, and instrumental when any performance says so`() =
        runTest {
            val musicBrainz =
                FakeMusicBrainz(
                    Lookup.Found(search),
                    mapOf(
                        "ours" to
                            detail(
                                MbWorkLink("w1", "Song", cover = false, languages = listOf("eng")),
                                MbWorkLink(
                                    "w2",
                                    "Other",
                                    cover = false,
                                    languages = listOf("zxx"),
                                    instrumental = true,
                                ),
                            ),
                    ),
                    mapOf("w1" to work("w1", listOf("https://genius.com/A-song-lyrics"), listOf("eng"))),
                )
            assertEquals(
                WorkLyrics(listOf("https://genius.com/A-song-lyrics"), listOf("eng", "zxx"), instrumental = true),
                resolve(musicBrainz, track("Song", "Act")).lyrics,
            )
        }

    @Test
    fun `a work that cannot be looked up still gives the languages and the instrumental mark`() =
        runTest {
            val musicBrainz =
                FakeMusicBrainz(
                    Lookup.Found(search),
                    mapOf(
                        "ours" to
                            detail(
                                MbWorkLink("w", "Song", cover = true, languages = listOf("zxx"), instrumental = true),
                            ),
                    ),
                    emptyMap(),
                )
            assertEquals(
                WorkLyrics(emptyList(), listOf("zxx"), instrumental = true),
                resolve(musicBrainz, track("Song", "Act")).lyrics,
            )
        }

    @Test
    fun `no work, or no recording accepted, knows nothing of the lyrics`() =
        runTest {
            val noWork = FakeMusicBrainz(Lookup.Found(search), mapOf("ours" to detail()), emptyMap())
            assertEquals(WorkLyrics.NONE, resolve(noWork, track("Song", "Act")).lyrics)
            val none = FakeMusicBrainz(Lookup.Found(emptyList()), emptyMap(), emptyMap())
            assertEquals(WorkLyrics.NONE, resolve(none, track("Song", "Act")).lyrics)
        }
}
