package io.github.wkusnierczyk.sleevesight.fixtures

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BelowSoundtrackTest {
    @Test
    fun `the playlist loads, with the counts the design names`() {
        assertEquals("Below Soundtrack Netflix", BelowSoundtrack.playlist.name)
        assertEquals(80, BelowSoundtrack.tracks.size)
        assertEquals((1..80).toList(), BelowSoundtrack.tracks.map { it.n })
        val unique = BelowSoundtrack.tracks.filter { BelowSoundtrack.firstOccurrence(it.n) == it.n }
        assertEquals(78, unique.size)
    }

    @Test
    fun `facts and albums cover exactly the first occurrence of each track`() {
        val unique = BelowSoundtrack.tracks.map { it.n }.filter { BelowSoundtrack.firstOccurrence(it) == it }
        assertEquals(unique, BelowSoundtrack.facts.map { it.n })
        assertEquals(unique, BelowSoundtrack.albums.map { it.n })
        for (facts in BelowSoundtrack.facts) {
            val track = BelowSoundtrack.track(facts.n)
            assertEquals(track.title to track.artist, facts.title to facts.artist, "track ${facts.n}")
        }
    }

    @Test
    fun `duplicates map to their first occurrence`() {
        assertEquals(8, BelowSoundtrack.firstOccurrence(22))
        assertEquals(4, BelowSoundtrack.firstOccurrence(24))
        assertEquals(BelowSoundtrack.factsFor(8), BelowSoundtrack.factsFor(22))
        assertEquals(BelowSoundtrack.albumFor(4), BelowSoundtrack.albumFor(24))
    }

    @Test
    fun `every regression case names tracks in the playlist`() {
        val positions = BelowSoundtrack.tracks.map { it.n }.toSet()
        for (case in RegressionCase.entries) {
            assertTrue(case.tracks.isNotEmpty(), case.name)
            assertTrue(positions.containsAll(case.tracks), case.name)
        }
        assertEquals("Sundown", BelowSoundtrack.track(1).title)
        assertEquals("Fisherman's Blues - 2006 Remaster", BelowSoundtrack.track(7).title)
        assertEquals(
            listOf("Reem", "Reem"),
            RegressionCase.SAME_NAME_ARTIST.tracks.map { BelowSoundtrack.track(it).artist },
        )
        assertEquals("Peleboy, Tony K", BelowSoundtrack.track(14).artist)
        assertEquals("The Ode To Newfoundland", BelowSoundtrack.track(37).title)
        assertEquals(
            listOf("original_song"),
            RegressionCase.COVERS.tracks
                .map { BelowSoundtrack.factsFor(it).wikipedia.status }
                .distinct(),
        )
        assertEquals("Jessiah", BelowSoundtrack.track(61).artist)
        assertEquals("Baby, Please Don't Go (feat. Van Morrison)", BelowSoundtrack.track(63).title)
        assertEquals(
            listOf("New York Groove", "Snowbird"),
            RegressionCase.COMPILATION_RELEASE_YEAR.tracks.map { BelowSoundtrack.track(it).title },
        )
        assertEquals(
            listOf("none"),
            RegressionCase.NOTHING_DOCUMENTED.tracks
                .map { BelowSoundtrack.factsFor(it).wikipedia.status }
                .distinct(),
        )
    }

    @Test
    fun `the simulator embeds the same data as the fixtures`() {
        // `design/simulator.html`, copied into the test resources by the build.
        val simulator =
            checkNotNull(javaClass.getResourceAsStream("/simulator/simulator.html")) { "simulator.html not copied" }
                .use { it.readBytes().decodeToString() }
        val blocks = listOf("playlist-data" to "playlist", "facts-data" to "facts", "albums-data" to "albums")
        for ((block, file) in blocks) {
            val start = "<script type=\"application/json\" id=\"$block\">"
            val embedded = simulator.substringAfter(start, "").substringBefore("</script>")
            assertTrue(embedded.isNotEmpty(), "no $block block in the simulator")
            assertEquals(
                Json.parseToJsonElement(Fixtures.text("${BelowSoundtrack.DIRECTORY}/$file.json")),
                Json.parseToJsonElement(embedded),
                "$block differs from $file.json",
            )
        }
    }
}
