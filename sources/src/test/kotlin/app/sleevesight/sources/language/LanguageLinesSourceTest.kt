package app.sleevesight.sources.language

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.ClearEpoch
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.sources.ArtistResults
import app.sleevesight.core.sources.LanguageSubject
import app.sleevesight.core.testing.MemoryArtistResults
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** The lines in the track's language kept per artist and per album (#939): the first found in each language. */
class LanguageLinesSourceTest {
    private val names = Normalizer(JvmAccentFolder)
    private val bts = LanguageSubject.Artist("BTS", "mb-bts")
    private val be = LanguageSubject.Album("BE", "BTS", "rg-be")

    private fun line(
        text: String,
        language: String,
        scope: Scope = Scope.ARTIST,
    ) = EvidenceDraft(
        SourceId.WIKIPEDIA,
        scope,
        text,
        "https://$language.wikipedia.org/wiki/BTS",
        AT,
        about = "BTS",
        language = language,
    )

    @Test
    fun `the first lines found in each language are kept, another language beside them, none not kept`() =
        runTest {
            val table = MemoryArtistResults()
            val source = LanguageLinesSource(names, table)
            source.keep(bts, "ko", emptyList())
            assertTrue(source.kept(bts).isEmpty())

            val korean = listOf(line("방탄소년단은 대한민국의 그룹이다.", "ko"))
            source.keep(bts, "ko", korean)
            source.keep(bts, "ko", listOf(line("방탄소년단은 7인조 그룹이다.", "ko")))
            val japanese = listOf(line("BTSは韓国のグループである。", "ja"))
            source.keep(bts, "ja", japanese)
            // By the language's code, then as found; what it is about and its language kept with each.
            assertEquals(japanese + korean, source.kept(bts))
            assertEquals(setOf("mb:mb-bts"), table.keys(LanguageLinesSource.ARTIST_PART))
            assertTrue(table.keys(LanguageLinesSource.ALBUM_PART).isEmpty())
        }

    @Test
    fun `an artist and an album are kept apart, each as its card is keyed`() =
        runTest {
            val table = MemoryArtistResults()
            val source = LanguageLinesSource(names, table)
            val album = listOf(line("《BE》는 방탄소년단의 앨범이다.", "ko", Scope.RELEASE))
            source.keep(be, "ko", album)
            source.keep(LanguageSubject.Album("BE", "BTS", null), "ko", album)
            source.keep(LanguageSubject.Artist("Björk", null), "is", listOf(line("Björk er söngkona.", "is")))
            assertTrue(source.kept(bts).isEmpty())
            assertEquals(album, source.kept(be))
            assertEquals(setOf("mb:rg-be", "name:3:bts2:be"), table.keys(LanguageLinesSource.ALBUM_PART))
            assertEquals(setOf("name:5:bjork"), table.keys(LanguageLinesSource.ARTIST_PART))
        }

    @Test
    fun `two albums whose cards are keyed apart by name keep their lines apart`() =
        runTest {
            val source = LanguageLinesSource(names, MemoryArtistResults())
            val plus = listOf(line("C++는 앨범이다.", "ko", Scope.RELEASE))
            source.keep(LanguageSubject.Album("C++", "BTS", null), "ko", plus)
            assertTrue(source.kept(LanguageSubject.Album("C", "BTS", null)).isEmpty())
            assertEquals(plus, source.kept(LanguageSubject.Album("C++", "BTS", null)))
        }

    @Test
    fun `a classical album's lines are kept by its performers too, whatever order a track credits them in`() =
        runTest {
            // #1130: two recordings of one title by one composer, by name.
            val table = MemoryArtistResults()
            val source = LanguageLinesSource(names, table)
            val title = "Mozart: Piano Concertos Nos. 20 & 23"
            val lines = listOf(line("Mozart: Piano Concertos est un album.", "fr", Scope.RELEASE))
            val sony = LanguageSubject.Album(title, "Mozart", null, listOf("Khatia Buniatishvili", "ASMF"))
            source.keep(sony, "fr", lines)
            assertEquals(lines, source.kept(sony.copy(performers = listOf("ASMF", "Khatia Buniatishvili", "ASMF"))))
            assertTrue(source.kept(sony.copy(performers = listOf("Howard Shelley"))).isEmpty())
            assertTrue(source.kept(sony.copy(performers = emptyList())).isEmpty())
        }

    @Test
    fun `with the switch off they are kept in memory, never in the table, and dropped when it is on`() =
        runTest {
            val table = MemoryArtistResults()
            var keeps = false
            val source = LanguageLinesSource(names, table, keeps = { keeps })
            val korean = listOf(line("방탄소년단은 대한민국의 그룹이다.", "ko"))
            source.keep(bts, "ko", korean)
            assertEquals(korean, source.kept(bts))
            assertTrue(table.keys(LanguageLinesSource.ARTIST_PART).isEmpty())

            // On: the table's, and the memory dropped, so the value is in one place only.
            keeps = true
            assertTrue(source.kept(bts).isEmpty())
            keeps = false
            assertTrue(source.kept(bts).isEmpty())
        }

    @Test
    fun `the switch turned on during a keep or a renew moves nothing from memory to the table`() =
        runTest {
            val table = MemoryArtistResults()
            // The switch reads off until it has been read [offUntil] times, then on.
            var reads = 0
            var offUntil = Int.MAX_VALUE
            val source = LanguageLinesSource(names, table, keeps = { reads++ >= offUntil })
            source.keep(bts, "ko", listOf(line("방탄소년단은 대한민국의 그룹이다.", "ko")))

            // A keep that read the switch off keeps in memory, whatever the switch says after.
            offUntil = reads + 1
            source.keep(bts, "ja", listOf(line("BTSは韓国のグループである。", "ja")))
            assertTrue(table.keys(LanguageLinesSource.ARTIST_PART).isEmpty())

            // A renew likewise.
            offUntil = Int.MAX_VALUE
            assertEquals(2, source.kept(bts).size)
            offUntil = reads + 1
            source.renew(bts)
            assertTrue(table.keys(LanguageLinesSource.ARTIST_PART).isEmpty())
        }

    @Test
    fun `an entry the table holds but cannot read is left as it is, not replaced`() =
        runTest {
            val table = MemoryArtistResults()
            table.keep(LanguageLinesSource.ARTIST_PART, "mb:mb-bts", "not lines")
            val source = LanguageLinesSource(names, table)
            assertTrue(source.kept(bts).isEmpty())
            source.keep(bts, "ko", listOf(line("방탄소년단은 대한민국의 그룹이다.", "ko")))
            source.renew(bts)
            assertEquals("not lines", table.get(LanguageLinesSource.ARTIST_PART, "mb:mb-bts")?.value)
        }

    @Test
    fun `with a table that keeps nothing they are kept in memory, which Clear history forgets`() =
        runTest {
            val source = LanguageLinesSource(names, ArtistResults.None)
            val korean = listOf(line("방탄소년단은 대한민국의 그룹이다.", "ko"))
            source.keep(bts, "ko", korean)
            source.keep(be, "ko", listOf(line("《BE》는 방탄소년단의 앨범이다.", "ko", Scope.RELEASE)))
            assertEquals(korean, source.kept(bts))
            source.forget()
            assertTrue(source.kept(bts).isEmpty())
            assertTrue(source.kept(be).isEmpty())
        }

    @Test
    fun `lines found by work started before Clear history are not kept in memory after it`() =
        runTest {
            val epoch = ClearEpoch()
            val source = LanguageLinesSource(names, ArtistResults.None, keeps = { false }, epoch = epoch)
            withContext(epoch.mark()) {
                epoch.advance()
                source.keep(bts, "ko", listOf(line("방탄소년단은 대한민국의 그룹이다.", "ko")))
            }
            assertTrue(source.kept(bts).isEmpty())
        }

    @Test
    fun `the lines kept again with each card written from them last as long as the last`() =
        runTest {
            val clock = Moving()
            // As *Keep history for* keeps them: 90 days from when they were last kept.
            val source = LanguageLinesSource(names, MemoryArtistResults(clock, 90.days))
            source.keep(bts, "ko", listOf(line("방탄소년단은 대한민국의 그룹이다.", "ko")))
            clock.now += 60.days
            source.renew(bts)
            clock.now += 40.days
            assertEquals(1, source.kept(bts).size)
            clock.now += 91.days
            assertTrue(source.kept(bts).isEmpty())
            source.renew(bts)
            assertTrue(source.kept(bts).isEmpty())
        }

    private class Moving(
        var now: Instant = Instant.fromEpochMilliseconds(0),
    ) : Clock {
        override fun now(): Instant = now
    }

    private companion object {
        val AT: Instant = Instant.parse("2026-11-05T12:00:00Z")
    }
}
