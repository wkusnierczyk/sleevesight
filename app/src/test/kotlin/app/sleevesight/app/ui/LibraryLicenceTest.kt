package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
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

    private fun waitForText(
        text: String,
        substring: Boolean = false,
    ) = compose.waitUntil(conditionDescription = text, timeoutMillis = 5_000) {
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun theListOfLibrariesShipsWithEachNotice() {
        val notices = libraryNotices(asset(LIBRARIES)).associateBy { it.notice }
        assertEquals(
            setOf(
                "apache-2.0.txt",
                "apache-notice-kotlinx-coroutines.txt",
                "apache-notice-kotlinx-io.txt",
                "apache-notice-kotlinx-serialization.txt",
                "apache-notice-zxing.txt",
                "bsd-3-clause-protobuf.txt",
                "bsd-3-clause-threetenbp.txt",
                "mit-materialkolor.txt",
                "mit-slf4j.txt",
                "mpl-2.0-publicsuffix.txt",
            ),
            notices.keys,
        )
        assertEquals(listOf("org.slf4j:slf4j-api"), notices.getValue("mit-slf4j.txt").libraries)
        assertEquals("BSD-3-Clause", notices.getValue("bsd-3-clause-protobuf.txt").licence)
        assertEquals(
            listOf("com.squareup.okhttp3:okhttp-android"),
            notices.getValue("mpl-2.0-publicsuffix.txt").libraries,
        )
        assertTrue("androidx.compose.ui" in notices.getValue("apache-2.0.txt").libraries)
        for (notice in notices.keys - "mpl-2.0-publicsuffix.txt") {
            assertTrue(notice, asset("licences/$notice").contains("Copyright"))
        }
        assertTrue(asset("licences/mpl-2.0-publicsuffix.txt").contains("Mozilla Public License Version 2.0"))
        assertTrue(asset("licences/mit-slf4j.txt").contains("Copyright (c) 2004-2023 QOS.ch"))
        assertTrue(asset("licences/bsd-3-clause-protobuf.txt").contains("Copyright 2008 Google Inc."))
        assertTrue(asset("licences/mit-materialkolor.txt").contains("Copyright (c) 2025 Jordon de Hoog"))
    }

    @Test
    fun theCreditsShowEachLibraryWithItsLicence() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { CreditsScreen() } }
            // The list is read off the main thread.
            waitForText("SLF4J API")
            compose.onNodeWithText(text(R.string.credits_libraries_apache)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("SLF4J API").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("org.slf4j:slf4j-api").performScrollTo().assertIsDisplayed()
            compose
                .onAllNodesWithText("BSD 3-Clause License")
                .onFirst()
                .performScrollTo()
                .assertIsDisplayed()

            // The SLF4J API's link: the Apache License's comes first, then the others by name.
            val slf4j =
                libraryNotices(asset(LIBRARIES)).indexOfFirst { it.notice == "mit-slf4j.txt" }
            compose
                .onAllNodesWithText(text(R.string.credits_show_licence))[slf4j]
                .performScrollTo()
                .performFirstLinkClick()
            waitForText("Copyright (c) 2004-2023 QOS.ch", substring = true)
            compose
                .onNodeWithText("Copyright (c) 2004-2023 QOS.ch", substring = true)
                .performScrollTo()
                .assertIsDisplayed()
        }
    }

    // The screen's saved state, as an activity keeps it: [restore] saves it, drops the content and
    // composes it afresh from what was saved, as Android does for a recreated activity
    // (`SourcesFoldScreenTest` does the same).
    private var emitted by mutableStateOf(true)
    private var registry by mutableStateOf(SaveableStateRegistry(null) { true })

    private fun restore() {
        val saved = registry.performSave()
        emitted = false
        compose.waitForIdle()
        registry = SaveableStateRegistry(saved) { true }
        emitted = true
        compose.waitForIdle()
    }

    private fun scrolled() =
        compose
            .onNode(hasScrollAction())
            .fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
            .value()

    @Test
    fun aRecreatedScreenKeepsTheOpenLicenceAndTheReadersPlace() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    if (emitted) {
                        CompositionLocalProvider(
                            LocalSaveableStateRegistry provides registry,
                        ) { CreditsScreen() }
                    }
                }
            }
            val slf4j = libraryNotices(asset(LIBRARIES)).indexOfFirst { it.notice == "mit-slf4j.txt" }
            compose
                .onAllNodesWithText(text(R.string.credits_show_licence))[slf4j]
                .performScrollTo()
                .performFirstLinkClick()
            compose.onNodeWithText("NONINFRINGEMENT", substring = true).performScrollTo()
            val place = scrolled()
            assertTrue(place > 0f)

            restore()
            assertEquals(place, scrolled())
            compose.onNodeWithText("NONINFRINGEMENT", substring = true).assertIsDisplayed()
        }
    }

    @Test
    fun theLicencesAreReflowedForANarrowScreen() {
        val text =
            listOf(
                "SLF4J API, by QOS.ch",
                "",
                "   Permission is hereby granted, free  of charge, to any person",
                "   obtaining a copy",
                "",
                "   1. Definitions.",
                "      \"License\" shall mean the terms",
                "      (a) You must give",
                "      any other recipients",
                "    * Redistributions of source code must retain",
                "copyright notice.",
                "=====",
                "==  NOTICE file  ==",
                "=====",
                "kotlinx.coroutines library.",
            ).joinToString("\n")
        assertEquals(
            listOf(
                "SLF4J API, by QOS.ch",
                "",
                "Permission is hereby granted, free of charge, to any person obtaining a copy",
                "",
                "1. Definitions. \"License\" shall mean the terms",
                "(a) You must give any other recipients",
                "* Redistributions of source code must retain copyright notice.",
                "=====",
                "== NOTICE file ==",
                "=====",
                "kotlinx.coroutines library.",
            ).joinToString("\n"),
            reflow(text),
        )
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
        // Each licence the build knows has its name.
        assertEquals(
            listOf("Apache License 2.0", "MIT License", "BSD 3-Clause License", "Mozilla Public License 2.0"),
            listOf("Apache-2.0", "MIT", "BSD-3-Clause", "MPL-2.0").map(::licenceName),
        )
    }
}
