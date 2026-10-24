package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The libraries' licences and notices ship with the app, and the credits show them (#488). */
@RunWith(RobolectricTestRunner::class)
class LibraryLicenceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun asset(name: String) =
        app.assets
            .open(name)
            .bufferedReader()
            .use { it.readText() }

    private fun text(id: Int) = app.getString(id)

    @Test
    fun theListOfLibrariesShipsWithEachNotice() {
        val notices = libraryNotices(asset(LIBRARIES)).associateBy { it.notice }
        assertEquals(
            setOf("apache-2.0.txt", "mit-slf4j.txt", "mit-materialkolor.txt", "bsd-3-clause-protobuf.txt"),
            notices.keys,
        )
        assertEquals(listOf("org.slf4j:slf4j-api"), notices.getValue("mit-slf4j.txt").libraries)
        assertEquals("BSD-3-Clause", notices.getValue("bsd-3-clause-protobuf.txt").licence)
        assertTrue("androidx.compose.ui" in notices.getValue("apache-2.0.txt").libraries)
        for (notice in notices.keys) assertTrue(notice, asset("licences/$notice").contains("Copyright"))
        assertTrue(asset("licences/mit-slf4j.txt").contains("Copyright (c) 2004-2023 QOS.ch"))
        assertTrue(asset("licences/bsd-3-clause-protobuf.txt").contains("Copyright 2008 Google Inc."))
        assertTrue(asset("licences/mit-materialkolor.txt").contains("Copyright (c) 2025 Jordon de Hoog"))
    }

    @Test
    fun theCreditsShowEachLibraryWithItsLicence() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { CreditsScreen() } }
            compose.onNodeWithText(text(R.string.credits_libraries_apache)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("SLF4J API").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("org.slf4j:slf4j-api").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("BSD 3-Clause License").performScrollTo().assertIsDisplayed()

            // The SLF4J API's link, the last of four: the Apache License's, then by name.
            compose
                .onAllNodesWithText(text(R.string.credits_show_licence))[3]
                .performScrollTo()
                .performFirstLinkClick()
            compose
                .onNodeWithText("Copyright (c) 2004-2023 QOS.ch", substring = true)
                .performScrollTo()
                .assertIsDisplayed()
        }
    }

    @Test
    fun theLibrariesAreByNoticeTheApacheLicenseFirst() {
        val list =
            listOf(
                "# module\tlicence\tnotice\tname",
                "org.b:z:1\tApache-2.0\tapache-2.0.txt\t",
                "org.a:y:2\tApache-2.0\tapache-2.0.txt\t",
                "org.a:x:2\tApache-2.0\tapache-2.0.txt\t",
                "org.slf4j:slf4j-api:2\tMIT\tmit-slf4j.txt\tSLF4J API",
                "com.m:m:1\tMIT\tmit-m.txt\tmaterialkolor",
            ).joinToString("\n")
        assertEquals(
            listOf(
                LibraryNotice("apache-2.0.txt", null, "Apache-2.0", listOf("org.a", "org.b")),
                LibraryNotice("mit-m.txt", "materialkolor", "MIT", listOf("com.m:m")),
                LibraryNotice("mit-slf4j.txt", "SLF4J API", "MIT", listOf("org.slf4j:slf4j-api")),
            ),
            libraryNotices(list),
        )
        assertEquals(emptyList<LibraryNotice>(), libraryNotices(null))
    }
}
