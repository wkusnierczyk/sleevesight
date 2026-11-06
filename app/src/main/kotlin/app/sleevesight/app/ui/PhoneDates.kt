package app.sleevesight.app.ui

import android.text.format.DateFormat
import app.sleevesight.core.platform.JvmDateWords
import app.sleevesight.core.text.DateForm
import app.sleevesight.core.text.DateWords
import java.util.Locale

/** Dates in words with the phone's own pattern for each locale and skeleton (#957). */
val PHONE_DATE_WORDS: DateWords =
    JvmDateWords { skeleton, locale -> DateFormat.getBestDateTimePattern(locale, skeleton) }

/**
 * The locale the app writes a date in on a phone set to [phone] (#957): English, as the app's text
 * is, in the order and form the phone's setting gives, as other apps write it.
 *
 * - **The phone in English:** its own variety, "October 17, 2026" in US or Canadian English, "17
 *   October 2026" in British, Australian, Indian or Irish.
 * - **The phone in another language:** English in the phone's region, where the platform has it
 *   ([englishRegions]: Switzerland's, Germany's), "17 October 2026" in both.
 * - **Otherwise** (no region, or one with no English of its own): international English, `en-001`,
 *   "17 October 2026". Never plain `en`, whose patterns are the US's.
 *
 * [englishRegions] are the regions the platform has English for (`Locale.getAvailableLocales`).
 */
fun dateLocale(
    phone: Locale,
    englishRegions: Set<String> = ENGLISH_REGIONS,
): Locale {
    val region = phone.country.uppercase(Locale.ROOT)
    return when {
        region.isEmpty() || region !in englishRegions -> INTERNATIONAL_ENGLISH
        phone.language == ENGLISH -> phone
        else -> Locale.forLanguageTag("$ENGLISH-$region")
    }
}

/**
 * The form the app writes dates in on a phone set to [phone] (#957): the phone's patterns
 * ([PHONE_DATE_WORDS]) in [dateLocale]. *Dates*, the line under an AI answer kept from before, and
 * the prompts' rule on dates (`InsightPrompt.dateRule`) are in it.
 */
fun phoneDateForm(phone: Locale): DateForm = DateForm(PHONE_DATE_WORDS, dateLocale(phone).toLanguageTag())

private const val ENGLISH = "en"

/** International English, the form "17 October 2026", where the phone's region has none of its own. */
private val INTERNATIONAL_ENGLISH: Locale = Locale.forLanguageTag("en-001")

/** The regions the platform has English for: `GB`, `US`, `CH`, `DE` … (two letters or three digits). */
private val ENGLISH_REGIONS: Set<String> by lazy {
    Locale
        .getAvailableLocales()
        .filter { it.language == ENGLISH && it.script.isEmpty() && it.variant.isEmpty() }
        .mapTo(mutableSetOf()) { it.country.uppercase(Locale.ROOT) }
        .apply { remove("") }
}
