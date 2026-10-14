package io.github.wkusnierczyk.sleevesight.store

import io.github.wkusnierczyk.sleevesight.core.settings.Settings
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds

class ArtistCacheTest {
    private val fixture = CacheFixture()
    private val artists = fixture.caches.artists

    @AfterTest
    fun close() = fixture.close()

    @Test
    fun `each finding comes back as it was kept`() {
        val findings =
            mapOf(
                "gordon lightfoot" to ArtistResolution.Resolved("17b53d9f-5c63-4a09-a593-dde4608e0db9"),
                "jessiah" to ArtistResolution.None,
                "reem" to ArtistResolution.Ambiguous(listOf("a-1", "b-2")),
                "nobody" to ArtistResolution.Ambiguous(emptyList()),
            )
        findings.forEach { (name, finding) -> artists.put(name, finding) }

        findings.forEach { (name, finding) -> assertEquals(finding, artists.get(name), name) }
        assertNull(artists.get("someone else"))
    }

    @Test
    fun `a finding expires after the artist lifetime`() {
        artists.put("gordon lightfoot", ArtistResolution.Resolved("mbid"))

        fixture.clock.advance(Settings.DEFAULT_ARTIST_CACHE_LIFETIME - 1.milliseconds)
        assertNotNull(artists.get("gordon lightfoot"))
        fixture.clock.advance(1.milliseconds)
        assertNull(artists.get("gordon lightfoot"))
    }

    @Test
    fun `the default artist lifetime is long`() {
        assertEquals(90.days, Settings().artistCacheLifetime)
    }

    @Test
    fun `a newer finding replaces the kept one`() {
        artists.put("reem", ArtistResolution.None)
        artists.put("reem", ArtistResolution.Resolved("mbid"))

        assertEquals(ArtistResolution.Resolved("mbid"), artists.get("reem"))
        assertEquals(
            1,
            fixture.caches
                .stats()
                .artists.entries,
        )
    }

    @Test
    fun `a candidate id with a space is refused`() {
        assertFailsWith<IllegalArgumentException> { ArtistResolution.Ambiguous(listOf("a b")) }
    }
}
