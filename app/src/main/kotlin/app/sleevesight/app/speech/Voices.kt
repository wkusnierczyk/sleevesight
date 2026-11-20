package app.sleevesight.app.speech

import java.util.Locale

/**
 * A voice of the phone's text-to-speech engine, as *Voice* (#787) reads it from Android's `Voice`:
 * its [name] (Google's engine: `en-gb-x-gba-local`, `en-gb-x-gba-network`), its [locale], its
 * [quality] (`Voice.QUALITY_*`: 300 normal, 400 high, 500 very high), whether it is [network],
 * spoken by the engine's server (`isNetworkConnectionRequired`), and whether it is [installed] on
 * the phone (no `KEY_FEATURE_NOT_INSTALLED` among its features).
 */
data class EngineVoice(
    val name: String,
    val locale: Locale,
    val quality: Int,
    val network: Boolean,
    val installed: Boolean = true,
)

/** What a voice of *Voice*'s list is marked with, after its name (#787). */
enum class VoiceMark {
    /** It has a phone version, and a server version of a higher quality, used while online. */
    HIGHER_QUALITY_ONLINE,

    /** It is of a high quality ([HIGH_QUALITY] or more) as it is used. */
    HIGHER_QUALITY,

    /** It has a server version only: it is spoken only while the phone is online. */
    NEEDS_THE_INTERNET,
}

/**
 * One voice of *Voice*'s list (#787): its [id], the engine's name for it without `-local` or
 * `-network`; its [country] (an ISO code, empty where the voice has none); its [number] among its
 * country's voices, from 1, which names it ("Voice 2"); the engine's names of its phone version,
 * [local], and of its server version, [network], at least one of them; and its [marks].
 */
data class VoiceEntry(
    val id: String,
    val country: String,
    val number: Int,
    val local: String?,
    val network: String?,
    val marks: List<VoiceMark>,
) {
    /** Whether it has only a server version, which needs the internet. */
    val needsTheInternet: Boolean get() = local == null

    /**
     * The engine's name of the version that speaks it: the server's while [online], else the
     * phone's; null offline for a voice with a server version only, which then cannot speak.
     */
    fun spokenBy(online: Boolean): String? = if (online && network != null) network else local
}

/** The voices of [country] (an ISO code; empty for those with none), in *Voice*'s list (#787). */
data class VoiceGroup(
    val country: String,
    val voices: List<VoiceEntry>,
)

/** A voice's quality from which it is marked as higher quality: `Voice.QUALITY_HIGH`. */
const val HIGH_QUALITY: Int = 400

private const val LOCAL_SUFFIX = "-local"
private const val NETWORK_SUFFIX = "-network"

// Google's engine also lists, for each language, a voice named after it (`en-US-language`), which
// stands for that language's default voice, listed in its own right as well: left out, so a voice
// is not offered twice.
private const val LANGUAGE_SUFFIX = "-language"

/**
 * *Voice*'s list (#787): the English voices of [voices], grouped by country, [phoneCountry]'s group
 * first, then the others by [countryName]; within a group by id, and numbered from 1. A voice's
 * phone and server versions (`…-local` and `…-network`, one name otherwise) are one entry. A
 * version not installed on the phone is left out, and so is a voice with nothing else; a server
 * version stays, marked as needing the internet.
 */
fun englishVoices(
    voices: Collection<EngineVoice>,
    phoneCountry: String,
    countryName: (String) -> String,
): List<VoiceGroup> {
    val byCountry = voiceEntries(voices).groupBy { it.country }
    val order =
        byCountry.keys.sortedWith(
            compareBy<String> { !it.equals(phoneCountry, ignoreCase = true) }.thenBy { countryName(it) },
        )
    return order.map { country ->
        val entries = byCountry.getValue(country).sortedBy { it.id }
        VoiceGroup(country, entries.mapIndexed { i, entry -> entry.copy(number = i + 1) })
    }
}

/**
 * The engine's name of the voice that speaks [id] (#787), as [voices] have it now, [online] or not;
 * null when they do not have it (it is gone), or it has only a server version and the phone is
 * offline: the phone's choice reads then.
 */
fun chosenVoice(
    id: String?,
    voices: Collection<EngineVoice>,
    online: Boolean,
): String? = id?.let { wanted -> voiceEntries(voices).firstOrNull { it.id == wanted }?.spokenBy(online) }

/** The entry of [voices] that is the engine's [name]'s voice (its phone or server version), if listed. */
fun List<VoiceGroup>.entryOf(name: String?): VoiceEntry? =
    name?.let { wanted -> flatMap { it.voices }.firstOrNull { wanted == it.local || wanted == it.network } }

/** The entry of [id], if listed. */
fun List<VoiceGroup>.entry(id: String?): VoiceEntry? =
    id?.let { wanted ->
        flatMap { it.voices }.firstOrNull {
            it.id ==
                wanted
        }
    }

/** Whether [name] is Google's engine's alias for a language's default voice (`en-US-language`). */
internal fun isLanguageAlias(name: String): Boolean = name.endsWith(LANGUAGE_SUFFIX, ignoreCase = true)

// The English voices of [voices] as entries, each numbered 0 (unordered), its versions merged.
private fun voiceEntries(voices: Collection<EngineVoice>): List<VoiceEntry> =
    voices
        .filter { it.locale.language == Locale.ENGLISH.language && it.installed }
        .filterNot { isLanguageAlias(it.name) }
        .groupBy { voiceId(it.name) }
        .map { (id, versions) ->
            val local = versions.filter { !it.network }.minByOrNull { it.name }
            val network = versions.filter { it.network }.minByOrNull { it.name }
            VoiceEntry(
                id = id,
                country = (local ?: versions.first()).locale.country.uppercase(Locale.ROOT),
                number = 0,
                local = local?.name,
                network = network?.name,
                marks = marks(local, network),
            )
        }

// A version's name without what tells the phone's and the server's apart.
private fun voiceId(name: String): String =
    when {
        name.endsWith(LOCAL_SUFFIX) -> name.removeSuffix(LOCAL_SUFFIX)
        name.endsWith(NETWORK_SUFFIX) -> name.removeSuffix(NETWORK_SUFFIX)
        else -> name
    }

// A server version only: needs the internet (and higher quality, if it is); both, with the server's
// better: higher quality online; else higher quality where the version used offline is.
private fun marks(
    local: EngineVoice?,
    network: EngineVoice?,
): List<VoiceMark> =
    when {
        local == null ->
            listOfNotNull(
                VoiceMark.HIGHER_QUALITY.takeIf { network != null && network.quality >= HIGH_QUALITY },
                VoiceMark.NEEDS_THE_INTERNET,
            )
        network != null && network.quality > local.quality && network.quality >= HIGH_QUALITY ->
            listOf(VoiceMark.HIGHER_QUALITY_ONLINE)
        local.quality >= HIGH_QUALITY -> listOf(VoiceMark.HIGHER_QUALITY)
        else -> emptyList()
    }
