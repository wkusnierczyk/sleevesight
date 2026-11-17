package app.sleevesight.app.settings

import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.test.platform.app.InstrumentationRegistry
import app.sleevesight.app.ui.settings.crossfadeLabel
import app.sleevesight.app.ui.settings.crossfadeSpoken
import app.sleevesight.core.settings.Crossfade
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

/**
 * *Crossfade* stored in a fraction of a second, as shown and read on Android (#1365): the number is
 * formatted by ART's locale data, not the JVM's, so the decimal separator follows the language (a
 * dot, a comma), the precision is kept down to the millisecond, the label and the spoken words
 * carry the same number, and the stored duration is left as it was.
 */
class CrossfadeLabelsTest {
    private fun resources(locale: Locale): Resources {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Configuration(context.resources.configuration)
        config.setLocales(LocaleList(locale))
        return context.createConfigurationContext(config).resources
    }

    private fun check(
        locale: Locale,
        millis: Long,
        number: String,
    ) {
        val crossfade = Crossfade.Fixed(millis.milliseconds)
        val resources = resources(locale)
        assertEquals("$number s", crossfadeLabel(crossfade, resources))
        assertEquals("$number seconds", crossfadeSpoken(crossfade, resources))
        assertEquals(millis.milliseconds, crossfade.value)
    }

    @Test
    fun aDotLanguageShowsAndReadsTheFractionWithADot() {
        check(Locale.US, 7600, "7.6")
        check(Locale.US, 12_345, "12.345")
    }

    @Test
    fun aCommaLanguageShowsAndReadsTheFractionWithAComma() {
        check(Locale.GERMANY, 7600, "7,6")
        check(Locale.GERMANY, 12_345, "12,345")
    }

    @Test
    fun aWholeSecondHasNoFraction() {
        val resources = resources(Locale.GERMANY)
        assertEquals("7 s", crossfadeLabel(Crossfade.Fixed(7000.milliseconds), resources))
        assertEquals("7 seconds", crossfadeSpoken(Crossfade.Fixed(7000.milliseconds), resources))
    }
}
