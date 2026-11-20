package app.sleevesight.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/**
 * *Voice*'s list (#787), from the engine's voices: English only, grouped by country, the phone's
 * first, then by name; numbered within each; a voice's phone and server versions one entry; a
 * version not downloaded left out, its server version kept as needing the internet; Google's
 * per-language aliases left out; each voice marked by its quality; and which version reads a
 * chosen voice, online or offline, and none when it is gone.
 */
class VoicesTest {
    private val us =
        Locale
            .Builder()
            .setLanguage("en")
            .setRegion("US")
            .build()
    private val gb =
        Locale
            .Builder()
            .setLanguage("en")
            .setRegion("GB")
            .build()
    private val au =
        Locale
            .Builder()
            .setLanguage("en")
            .setRegion("AU")
            .build()
    private val de =
        Locale
            .Builder()
            .setLanguage("de")
            .setRegion("DE")
            .build()

    private fun local(
        name: String,
        locale: Locale,
        quality: Int = 300,
        installed: Boolean = true,
    ) = EngineVoice("$name-local", locale, quality, network = false, installed = installed)

    private fun network(
        name: String,
        locale: Locale,
        quality: Int = 400,
    ) = EngineVoice("$name-network", locale, quality, network = true)

    private val names = mapOf("US" to "United States", "GB" to "United Kingdom", "AU" to "Australia")

    private fun list(
        voices: List<EngineVoice>,
        phone: String = "GB",
    ) = englishVoices(voices, phone) { names[it] ?: it }

    @Test
    fun englishVoicesAreGroupedByCountryThePhonesFirstThenTheOthersByName() {
        val voices =
            listOf(
                local("en-us-x-iob", us),
                local("en-au-x-aua", au),
                local("en-gb-x-gbb", gb),
                local("de-de-x-dea", de),
            )
        assertEquals(listOf("GB", "AU", "US"), list(voices).map { it.country })
        assertEquals(listOf("US", "AU", "GB"), list(voices, phone = "US").map { it.country })
        // A phone whose country has no English voice: by name.
        assertEquals(listOf("AU", "GB", "US"), list(voices, phone = "PL").map { it.country })
    }

    @Test
    fun voicesAreOrderedByIdAndNumberedFromOneWithinTheirCountry() {
        val voices = listOf(local("en-gb-x-gbc", gb), local("en-gb-x-gba", gb), local("en-us-x-iob", us))
        val groups = list(voices)
        assertEquals(listOf("en-gb-x-gba" to 1, "en-gb-x-gbc" to 2), groups[0].voices.map { it.id to it.number })
        assertEquals(listOf("en-us-x-iob" to 1), groups[1].voices.map { it.id to it.number })
    }

    @Test
    fun aVoicesPhoneAndServerVersionsAreOneEntry() {
        val entry = list(listOf(local("en-gb-x-gba", gb), network("en-gb-x-gba", gb))).single().voices.single()
        assertEquals("en-gb-x-gba", entry.id)
        assertEquals("en-gb-x-gba-local", entry.local)
        assertEquals("en-gb-x-gba-network", entry.network)
        assertEquals(false, entry.needsTheInternet)
    }

    @Test
    fun aVersionNotDownloadedIsLeftOutAndItsServerVersionStaysAsNeedingTheInternet() {
        val voices =
            listOf(
                local("en-gb-x-gba", gb, installed = false),
                network("en-gb-x-gba", gb),
                local("en-gb-x-gbb", gb, installed = false),
            )
        val entry = list(voices).single().voices.single()
        assertEquals("en-gb-x-gba", entry.id)
        assertNull(entry.local)
        assertEquals(true, entry.needsTheInternet)
        assertEquals(listOf(VoiceMark.HIGHER_QUALITY, VoiceMark.NEEDS_THE_INTERNET), entry.marks)
    }

    @Test
    fun googlesPerLanguageAliasIsLeftOut() {
        val voices = listOf(EngineVoice("en-GB-language", gb, 400, network = false), local("en-gb-x-gba", gb))
        assertEquals(listOf("en-gb-x-gba"), list(voices).single().voices.map { it.id })
    }

    @Test
    fun noEnglishVoiceIsNoGroup() {
        assertEquals(emptyList<VoiceGroup>(), list(listOf(local("de-de-x-dea", de))))
    }

    @Test
    fun eachVoiceIsMarkedByItsQuality() {
        fun marks(vararg versions: EngineVoice) =
            list(versions.toList())
                .single()
                .voices
                .single()
                .marks
        assertEquals(listOf(VoiceMark.HIGHER_QUALITY_ONLINE), marks(local("v", gb, 300), network("v", gb, 400)))
        assertEquals(listOf(VoiceMark.HIGHER_QUALITY), marks(local("v", gb, 400), network("v", gb, 400)))
        assertEquals(listOf(VoiceMark.HIGHER_QUALITY), marks(local("v", gb, 400)))
        assertEquals(emptyList<VoiceMark>(), marks(local("v", gb, 300)))
        assertEquals(listOf(VoiceMark.NEEDS_THE_INTERNET), marks(network("v", gb, 300)))
    }

    @Test
    fun aChosenVoiceReadsByItsServerVersionOnlineAndItsPhoneVersionOffline() {
        val voices = listOf(local("en-gb-x-gba", gb), network("en-gb-x-gba", gb))
        assertEquals("en-gb-x-gba-network", chosenVoice("en-gb-x-gba", voices, online = true))
        assertEquals("en-gb-x-gba-local", chosenVoice("en-gb-x-gba", voices, online = false))
    }

    @Test
    fun aVoiceGoneOrOneNeedingTheInternetWhileOfflineReadsAsThePhonesChoice() {
        val voices = listOf(network("en-gb-x-gbd", gb), local("en-gb-x-gba", gb))
        assertNull(chosenVoice("en-gb-x-gbd", voices, online = false))
        assertEquals("en-gb-x-gbd-network", chosenVoice("en-gb-x-gbd", voices, online = true))
        assertNull(chosenVoice("en-gb-x-gone", voices, online = true))
        assertNull(chosenVoice(null, voices, online = true))
    }

    @Test
    fun theEnginesDefaultVoiceIsFoundByEitherVersion() {
        val groups = list(listOf(local("en-gb-x-gba", gb), network("en-gb-x-gba", gb), local("en-gb-x-gbb", gb)))
        assertEquals("en-gb-x-gba", groups.entryOf("en-gb-x-gba-network")?.id)
        assertEquals("en-gb-x-gbb", groups.entryOf("en-gb-x-gbb-local")?.id)
        assertNull(groups.entryOf("en-GB-language"))
    }
}
