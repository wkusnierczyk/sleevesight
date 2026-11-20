package app.sleevesight.app.secrets

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.BuildConfig
import app.sleevesight.app.MainActivity
import app.sleevesight.core.Secret
import app.sleevesight.core.ai.KeyExchange
import app.sleevesight.core.ai.KeySignIn
import app.sleevesight.core.ai.SignInStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * OpenRouter's sign-in returns through an App Link on the app's site (#1396): the manifest's intent
 * filter, which Android verifies against the site's `/.well-known/assetlinks.json`, and that file,
 * which names the app's packages and their signing certificates.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OpenRouterAppLinkTest {
    /**
     * On the debug build's merged manifest, the build the unit tests run (there is no release unit
     * test task): it takes its own callback and not the release's, so with both installed a sign-in
     * returns to the build that began it, which holds its verifier. The release build's values are
     * checked in the build script below ([theReleaseBuildHasTheReleaseCallback]).
     */
    @Test
    fun theBuildsOwnCallbackOpensTheMainActivityAndNoOtherPageOfTheSiteDoes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val own = if (BuildConfig.DEBUG) DEBUG_CALLBACK else CALLBACK
        val other = if (BuildConfig.DEBUG) CALLBACK else DEBUG_CALLBACK
        assertEquals(own, BuildConfig.OPENROUTER_CALLBACK)

        fun handlers(url: String) =
            context.packageManager
                .queryIntentActivities(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                        .setPackage(context.packageName),
                    0,
                ).map { it.activityInfo.name }

        assertEquals(listOf(MainActivity::class.java.name), handlers("$own?s=state&code=code"))
        assertEquals(listOf(MainActivity::class.java.name), handlers(own))
        assertEquals(emptyList<String>(), handlers("$other?s=state&code=code"))
        assertEquals(emptyList<String>(), handlers("https://sleevesight.app/"))
        assertEquals(emptyList<String>(), handlers("https://sleevesight.app/auth/openrouter/other"))
        assertEquals(emptyList<String>(), handlers("https://example.com/auth/openrouter/"))
        // The custom scheme OpenRouter never returned to (#1397) is gone.
        assertEquals(emptyList<String>(), handlers("app.sleevesight://openrouter?code=code"))
    }

    @Test
    fun theReleaseBuildHasTheReleaseCallback() {
        val script = File("build.gradle.kts").readText()
        val release = script.substringAfter("        release {").substringBefore("        }")
        val debug = script.substringAfter("        debug {").substringBefore("        }")
        assertTrue(release, "\"OPENROUTER_CALLBACK\", \"\\\"$CALLBACK\\\"\")" in release)
        assertTrue(release, "manifestPlaceholders[\"openRouterCallbackPath\"] = \"/auth/openrouter/\"" in release)
        assertTrue(debug, "\"OPENROUTER_CALLBACK\", \"\\\"$DEBUG_CALLBACK\\\"\")" in debug)
        assertTrue(debug, "manifestPlaceholders[\"openRouterCallbackPath\"] = \"/auth/openrouter/debug/\"" in debug)
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
        // Each build type's own path (`app/build.gradle.kts`); the test above checks what it becomes.
        assertEquals(listOf("\${openRouterCallbackPath}"), filter.data("android:path"))
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
        // The page a browser shows when the link opens there, at each build's callback path.
        listOf("../auth/openrouter/index.html", "../auth/openrouter/debug/index.html").forEach { path ->
            assertTrue(path, "{% include openrouter-return.html " in File(path).readText())
        }
        val page = File("../_includes/openrouter-return.html").readText()
        assertTrue("Return to the Sleevesight app on your phone." in page)
        assertTrue("<meta name=\"referrer\" content=\"no-referrer\">" in page)
        // No script at all, not even the site header's.
        assertTrue("script-src 'none'" in page)
        assertTrue("<script" !in page)
    }

    /**
     * The answer as Android hands it to the activity (#1396): taken with the sign-in's state, with
     * none (the redirect dropped the callback's own query), or from the fragment; not with another
     * state, which ends the sign-in as not finished rather than as closed. Each time the log says
     * which parameters came and how the state stood, never the code or the state.
     */
    @Test
    fun theAnswerReachingTheActivityIsTakenWithTheStateOrNoneAndLoggedByItsParametersNamesOnly() =
        runTest {
            fun answered(
                url: String,
                beforeTheAnswer: (OpenRouterLogin) -> Unit = { it.start() },
            ): Pair<OpenRouterLogin, String> {
                ShadowLog.clear()
                val login =
                    OpenRouterLogin(
                        signIn,
                        save = { true },
                        scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
                    )
                val callbacks = SignInCallbacks(handles = { true }, login = { login })
                val controller =
                    Robolectric.buildActivity(ComponentActivity::class.java, Intent(Intent.ACTION_VIEW)).create()
                val activity = controller.get()
                callbacks.onActivityCreated(activity, null)
                beforeTheAnswer(login)
                // The running activity gets the App Link as a new intent, then comes to the front.
                controller.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                callbacks.onActivityResumed(activity)
                val lines = ShadowLog.getLogsForTag(SignInCallbacks.TAG).map { it.msg }
                lines.forEach { assertTrue(it, "the-code" !in it && "state-" !in it) }
                return login to lines.joinToString("\n")
            }

            val (matched, matchedLog) = answered("$CALLBACK?s=state-1&code=the-code")
            assertEquals(SignInStep.Idle, matched.step.value)
            assertEquals("OpenRouter sign-in answer: query [s, code], fragment []; state matched", matchedLog)

            val (missing, missingLog) = answered("$CALLBACK?code=the-code")
            assertEquals(SignInStep.Idle, missing.step.value)
            assertEquals("OpenRouter sign-in answer: query [code], fragment []; state missing", missingLog)

            val (fragment, fragmentLog) = answered("$CALLBACK#code=the-code&s=state-3")
            assertEquals(SignInStep.Idle, fragment.step.value)
            assertEquals("OpenRouter sign-in answer: query [], fragment [code, s]; state matched", fragmentLog)

            val (differed, differedLog) = answered("$CALLBACK?s=state-other&code=the-code")
            assertEquals(SignInStep.Failed(SignInFailure.REFUSED), differed.step.value)
            assertEquals(
                "OpenRouter sign-in answer: query [s, code], fragment []; state differed",
                differedLog,
            )

            val (none, noneLog) = answered("$CALLBACK?code=the-code") { }
            assertEquals(SignInStep.Idle, none.step.value)
            assertEquals("OpenRouter sign-in answer: query [code], fragment []; state no_sign_in", noneLog)

            assertEquals(
                listOf("the-code" to "verifier-1", "the-code" to "verifier-2", "the-code" to "verifier-3"),
                signIn.exchanged,
            )
        }

    // OpenRouter's sign-in, numbering each sign-in's verifier and state, and keeping each exchange.
    private val signIn =
        object : KeySignIn {
            val exchanged = mutableListOf<Pair<String, String>>()
            private var made = 0
            override val callback = CALLBACK

            override fun begin(): SignInStart {
                made++
                return SignInStart("verifier-$made", "state-$made", "https://openrouter.ai/auth")
            }

            override suspend fun exchange(
                code: String,
                verifier: String,
            ): KeyExchange {
                exchanged += code to verifier
                return KeyExchange.Key(Secret("sk-or-v1-new"))
            }
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

        // `OpenRouterSignIn.DEBUG_CALLBACK`.
        const val DEBUG_CALLBACK = "https://sleevesight.app/auth/openrouter/debug/"
        val FINGERPRINT = Regex("[0-9A-F]{2}(:[0-9A-F]{2}){31}")
    }
}
