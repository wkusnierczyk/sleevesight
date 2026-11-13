package app.sleevesight.app.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.ui.theme.LinkLabel
import app.sleevesight.app.ui.theme.LinkedText
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.app.ui.theme.TextPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Every link opens through [openWebLink] (#1040), which opens only `http` and `https` addresses: a
 * link from a source with another scheme opens nothing, and never another app; where no app opens
 * web pages, a tap does nothing and nothing crashes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
class WebLinkOpenersTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val opened = mutableListOf<String>()

    private val browser =
        object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
            }
        }

    @Test
    fun `a web address is opened, any other scheme is not`() {
        assertTrue(openWebLink(browser, HTTPS))
        assertTrue(openWebLink(browser, "http://example.com"))
        for (url in OTHERS) assertFalse(url, openWebLink(browser, url))
        assertFalse(openWebLink(browser, null))
        assertEquals(listOf(HTTPS, "http://example.com"), opened)
    }

    @Test
    fun `with no app that opens web pages, nothing happens`() {
        val none =
            object : UriHandler {
                override fun openUri(uri: String): Unit = throw IllegalArgumentException("no browser")
            }
        val gone =
            object : UriHandler {
                override fun openUri(uri: String): Unit = throw ActivityNotFoundException("no browser")
            }
        assertFalse(openWebLink(none, HTTPS))
        assertFalse(openWebLink(gone, HTTPS))
    }

    @Test
    fun `a link in a text opens only a web address`() {
        show({
            LinkedText(listOf(TextPart.Link("intent", OTHERS.first())), MaterialTheme.typography.bodyLarge, tagged(0))
            LinkedText(listOf(TextPart.Link("web", HTTPS)), MaterialTheme.typography.bodyLarge, tagged(1))
        }) {
            tap(0)
            assertEquals(emptyList<String>(), opened)
            tap(1)
            assertEquals(listOf(HTTPS), opened)
        }
    }

    @Test
    fun `a link of its own opens only a web address`() {
        show({
            LinkLabel(TextPart.Link("js", OTHERS[1]), "Open", MaterialTheme.typography.bodyLarge, tagged(0))
            LinkLabel(TextPart.Link("web", HTTPS), "Open", MaterialTheme.typography.bodyLarge, tagged(1))
        }) {
            tap(0)
            assertEquals(emptyList<String>(), opened)
            tap(1)
            assertEquals(listOf(HTTPS), opened)
        }
    }

    @Test
    fun `no other place in the app opens an address`() {
        val sources = File("src/main/kotlin").walk().filter { it.extension == "kt" }.toList()
        assertTrue("sources found", sources.any { it.name == "WebLinks.kt" })
        val elsewhere =
            sources.filter { it.name != "WebLinks.kt" }.flatMap { file ->
                file.readLines().withIndex().mapNotNull { (i, line) ->
                    "${file.name}:${i + 1}: ${line.trim()}".takeIf { OPENS.containsMatchIn(line) }
                }
            }
        assertEquals(emptyList<String>(), elsewhere)
    }

    private fun tagged(i: Int) = Modifier.testTag("$TAG$i")

    private fun tap(i: Int) {
        compose.onNodeWithTag("$TAG$i").performClick()
        compose.waitForIdle()
    }

    private fun show(
        content: @Composable () -> Unit,
        check: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    CompositionLocalProvider(LocalUriHandler provides browser) {
                        SleevesightTheme { Column { content() } }
                    }
                }
            }
            compose.waitForIdle()
            check()
        }
    }

    private companion object {
        const val TAG = "web-link:"
        const val HTTPS = "https://www.last.fm/music/Cher"
        val OTHERS =
            listOf(
                "intent://scan/#Intent;scheme=zxing;package=com.example;end",
                "javascript:alert(1)",
                "file:///sdcard/x",
                "market://details?id=com.example",
            )

        /** A call that hands an address to the system: only [openWebLink] may. */
        val OPENS = Regex("""\.openUri\s*\(|Intent\.ACTION_VIEW\b""")
    }
}
