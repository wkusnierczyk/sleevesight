package app.sleevesight.sources.musicbrainz

import app.sleevesight.core.model.Outcome
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.resolution.MbRecordingDetail
import app.sleevesight.core.resolution.MbWork
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.WorkLyrics
import app.sleevesight.fixtures.Fixtures
import app.sleevesight.sources.web.ReplayNetClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the MusicBrainz client keeps for the lyrics links (#728): a work's `lyrics` URL relationships
 * and languages, and whether a recording's performance of its work is marked `instrumental`; and the
 * adapter carrying them on its result. MusicBrainz's answers, recorded on 2026-10-31 (tags and
 * genres removed, as `fixtures/http/README.md` says), are in this module's test resources,
 * `musicbrainz/lyrics/`: *Right Down the Line*'s work, with two Genius pages, Gerry Rafferty's
 * recording of it, and Ossie Scott's instrumental *Snowbird*. Misirlou's work (`zxx`) and
 * *Snowbird*'s are the shared fixtures'.
 */
class MusicBrainzLyricsTest {
    private fun resource(file: String) = checkNotNull(javaClass.getResource("/musicbrainz/lyrics/$file")).readText()

    private suspend fun work(body: String) =
        assertIs<Lookup.Found<MbWork>>(MusicBrainzClient(ReplayNetClient.ok(body)).work("w")).value

    private suspend fun recording(body: String) =
        assertIs<Lookup.Found<MbRecordingDetail>>(MusicBrainzClient(ReplayNetClient.ok(body)).recording("r")).value

    @Test
    fun `a work keeps its lyrics pages and its languages, beside its Wikidata item`() =
        runTest {
            val work = work(resource("work-$RIGHT_DOWN_THE_LINE.json"))
            assertEquals(
                listOf(
                    "https://genius.com/Bonnie-raitt-right-down-the-line-lyrics",
                    "https://genius.com/Gerry-rafferty-right-down-the-line-lyrics",
                ),
                work.lyricsUrls,
            )
            assertEquals(listOf("eng"), work.languages)
            assertEquals(listOf("Q28444210"), work.wikidataIds)

            val snowbird = work(Fixtures.text("http/musicbrainz/work-$SNOWBIRD.json"))
            assertEquals(listOf("https://genius.com/Anne-murray-snowbird-lyrics"), snowbird.lyricsUrls)

            val misirlou = work(Fixtures.text("http/musicbrainz/work-$MISIRLOU.json"))
            assertEquals(emptyList(), misirlou.lyricsUrls)
            assertEquals(listOf("zxx"), misirlou.languages)
        }

    @Test
    fun `a work's languages from the list, else from the single language, else none`() =
        runTest {
            val listed = """{"id":"w","title":"Song","language":"mul","languages":["eng","fra"],"relations":[]}"""
            assertEquals(listOf("eng", "fra"), work(listed).languages)
            val single = """{"id":"w","title":"Song","language":"fra","relations":[]}"""
            assertEquals(listOf("fra"), work(single).languages)
            val none = """{"id":"w","title":"Song","language":null,"languages":[],"relations":[]}"""
            assertEquals(emptyList(), work(none).languages)
        }

    @Test
    fun `a recording's work link keeps the work's languages and the instrumental mark`() =
        runTest {
            val gerry = recording(resource("recording-$RAFFERTY.json")).works.single()
            assertEquals(RIGHT_DOWN_THE_LINE, gerry.mbid)
            assertEquals(listOf("eng"), gerry.languages)
            assertFalse(gerry.instrumental)
            assertFalse(gerry.cover)

            val ossie = recording(resource("recording-$OSSIE_SCOTT.json")).works.single()
            assertEquals(SNOWBIRD, ossie.mbid)
            assertTrue(ossie.instrumental)
            assertTrue(ossie.cover)

            val dickDale = recording(Fixtures.text("http/musicbrainz/recording-$DICK_DALE.json")).works.single()
            assertEquals(listOf("zxx"), dickDale.languages)
            assertFalse(dickDale.instrumental)
        }

    @Test
    fun `the adapter carries the lyrics for an accepted recording only`() =
        runTest {
            val track = Normalizer(JvmAccentFolder).normalize("Right Down the Line", "Gerry Rafferty")
            val lyrics =
                WorkLyrics(listOf("https://genius.com/Gerry-rafferty-right-down-the-line-lyrics"), listOf("eng"))
            val accepted = Resolution(outcome = Outcome.SongArticle, recordingMbid = RAFFERTY, lyrics = lyrics)
            assertEquals(lyrics, MusicBrainzSource().lookup(track, CompletableDeferred(accepted)).lyrics)
            assertNull(MusicBrainzSource().lookup(track, CompletableDeferred(Resolution.NONE)).lyrics)
            assertNull(MusicBrainzSource().lookup(track, CompletableDeferred(Resolution.unreachable("Offline"))).lyrics)
        }

    private companion object {
        const val RIGHT_DOWN_THE_LINE = "5d0e65d7-e029-47b4-9461-428f36ed7a1e"
        const val RAFFERTY = "bd129c44-6286-4f62-8d11-8d1c8437ecc0"
        const val OSSIE_SCOTT = "5e7e97b1-5339-45f5-a17d-915c7990d34b"
        const val SNOWBIRD = "f17eefd3-54ad-371a-90da-488571c94f99"
        const val MISIRLOU = "98cd98b6-897c-3c8c-9718-98b1206d8927"
        const val DICK_DALE = "254c0284-4af7-4dd2-bdf4-d0d6d0e83447"
    }
}
