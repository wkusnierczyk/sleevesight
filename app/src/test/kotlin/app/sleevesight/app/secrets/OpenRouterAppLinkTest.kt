package app.sleevesight.app.secrets

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * OpenRouter's sign-in returns through an App Link on the app's site (#1396): the manifest's intent
 * filter, which Android verifies against the site's `/.well-known/assetlinks.json`, and that file,
 * which names the app's packages and their signing certificates.
 */
@RunWith(RobolectricTestRunner::class)
class OpenRouterAppLinkTest {
    @Test
    fun theCallbackOpensTheMainActivityAndNoOtherPageOfTheSiteDoes() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        fun handlers(url: String) =
            context.packageManager
                .queryIntentActivities(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                        .setPackage(context.packageName),
                    0,
                ).map { it.activityInfo.name }

        assertEquals(listOf(MainActivity::class.java.name), handlers("$CALLBACK?s=state&code=code"))
        assertEquals(listOf(MainActivity::class.java.name), handlers(CALLBACK))
        assertEquals(emptyList<String>(), handlers("https://sleevesight.app/"))
        assertEquals(emptyList<String>(), handlers("https://sleevesight.app/auth/openrouter/other"))
        assertEquals(emptyList<String>(), handlers("https://example.com/auth/openrouter/"))
        // The custom scheme OpenRouter never returned to (#1397) is gone.
        assertEquals(emptyList<String>(), handlers("app.sleevesight://openrouter?code=code"))
    }

    @Test
    fun theCallbacksFilterAsksAndroidToVerifyTheSite() {
        val manifest =
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val filters = manifest.getElementsByTagName("intent-filter")
        val appLinks =
            (0 until filters.length)
                .map { filters.item(it) as Element }
                .filter { filter -> filter.data("android:scheme").any { it == "https" } }
        val filter = appLinks.single()
        assertEquals("true", filter.getAttribute("android:autoVerify"))
        assertEquals(".MainActivity", (filter.parentNode as Element).getAttribute("android:name"))
        assertEquals(listOf("sleevesight.app"), filter.data("android:host"))
        assertEquals(listOf("/auth/openrouter/"), filter.data("android:path"))
        assertEquals(listOf("https"), filter.data("android:scheme"))
    }

    @Test
    fun theSiteVouchesForTheReleaseAndDebugBuildsByTheirSigningCertificates() {
        val statements = JSONArray(File("../.well-known/assetlinks.json").readText())
        val byPackage =
            (0 until statements.length()).associate { index ->
                val statement = statements.getJSONObject(index)
                assertEquals(
                    listOf("delegate_permission/common.handle_all_urls"),
                    statement.getJSONArray("relation").strings(),
                )
                val target = statement.getJSONObject("target")
                assertEquals("android_app", target.getString("namespace"))
                target.getString("package_name") to target.getJSONArray("sha256_cert_fingerprints").strings()
            }
        assertEquals(
            mapOf(
                // Play's app signing keys (the one in use first), then the upload key.
                "app.sleevesight" to
                    listOf(
                        colons("936F0BD83BE044F2FC47515590AAF1E991386BEBE8A9BABBE4428228BE11D47B"),
                        colons("05E9A33E9819EF5CEC8B9E61074AC89C65C22B19BF9F0177F16668B69896C820"),
                        colons("1CC6CEEFD1754869D0EA659B69777AF635D47E9142D48712EC8DBA6F237E3774"),
                        colons("B0417AF4795210820144DC9AE0D99ECD95419B615B612D4AE7C258A9D1A2318B"),
                    ),
                // The development debug key.
                "app.sleevesight.debug" to
                    listOf(
                        colons("D9323643AEB4B49993DF855FD194736F2826C1DED7288487351E0F4625293005"),
                    ),
            ),
            byPackage,
        )
        assertEquals(2, statements.length())
        // Each fingerprint as Android compares it: 32 bytes, upper-case hex, colon-separated.
        byPackage.values.flatten().forEach { assertTrue(it, FINGERPRINT.matches(it)) }
    }

    @Test
    fun theSiteBuildPublishesTheWellKnownFolderAndHasAPageAtTheCallback() {
        val config = File("../_config.yml").readLines()
        val include = config.indexOf("include:")
        assertTrue("_config.yml has no include:", include >= 0)
        assertEquals("  - .well-known", config[include + 1])
        // The page a browser shows when the link opens there, at the callback's path.
        val page = File("../auth/openrouter/index.html").readText()
        assertTrue("Return to the Sleevesight app on your phone." in page)
        assertTrue("<meta name=\"referrer\" content=\"no-referrer\">" in page)
    }

    private fun Element.data(attribute: String): List<String> =
        getElementsByTagName("data").let { nodes ->
            (0 until nodes.length)
                .map { (nodes.item(it) as Element).getAttribute(attribute) }
                .filter { it.isNotEmpty() }
        }

    // A SHA-256 fingerprint written as hex, in the file's form: byte by byte, colon-separated.
    private fun colons(hex: String): String = hex.chunked(2).joinToString(":")

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    private companion object {
        // `OpenRouterSignIn.CALLBACK` (`:ai`, which `:app` reaches only through `:wiring`).
        const val CALLBACK = "https://sleevesight.app/auth/openrouter/"
        val FINGERPRINT = Regex("[0-9A-F]{2}(:[0-9A-F]{2}){31}")
    }
}
