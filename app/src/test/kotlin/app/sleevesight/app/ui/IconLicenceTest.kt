package app.sleevesight.app.ui

import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.SleevesightApplication
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The icons' licence, the Apache License 2.0, ships with the app, as the font's does (#445). */
@RunWith(RobolectricTestRunner::class)
class IconLicenceTest {
    @Test
    fun theIconsLicenceShipsWithTheApp() {
        val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
        val licence =
            app.assets
                .open("licences/apache-2.0.txt")
                .bufferedReader()
                .use { it.readText() }
        assertTrue(licence.startsWith("Material Symbols, by Google"))
        assertTrue(licence.contains("Apache License\n                           Version 2.0, January 2004"))
        assertTrue(licence.contains("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION"))
        assertTrue(licence.contains("END OF TERMS AND CONDITIONS"))
    }
}
