package app.sleevesight.app.secrets

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.KeyExchange
import app.sleevesight.core.ai.KeySignIn
import app.sleevesight.core.ai.SignInStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** A sign-in with OpenRouter (#1157), from the tap to the key saved, against a fake OpenRouter. */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenRouterLoginTest {
    private var time: Instant = Instant.parse("2026-11-17T12:00:00Z")
    private val clock =
        object : Clock {
            override fun now(): Instant = time
        }

    /** OpenRouter's sign-in, answering an exchange with [answer], and keeping what it was asked. */
    private class FakeSignIn(
        var answer: KeyExchange = KeyExchange.Key(Secret("sk-or-v1-new")),
    ) : KeySignIn {
        val exchanged = mutableListOf<Pair<String, String>>()
        private var made = 0

        override val callback = "https://sleevesight.app/auth/openrouter/"

        override fun begin(): SignInStart {
            made++
            return SignInStart("verifier-$made", "state-$made", "https://openrouter.ai/auth?n=$made")
        }

        override suspend fun exchange(
            code: String,
            verifier: String,
        ): KeyExchange {
            exchanged += code to verifier
            return answer
        }
    }

    private val saved = mutableListOf<String>()

    private fun TestScope.login(
        signIn: KeySignIn,
        saves: Boolean = true,
    ) = OpenRouterLogin(
        signIn,
        save = { key ->
            saved += key.reveal()
            saves
        },
        scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
        clock = clock,
    )

    private fun later(by: Duration) {
        time += by
    }

    @Test
    fun aCodeAtTheCallbackIsExchangedWithTheSignInSVerifierAndTheKeySaved() =
        runTest {
            val signIn = FakeSignIn()
            val login = login(signIn)
            assertEquals("https://openrouter.ai/auth?n=1", login.start())
            assertEquals(SignInStep.InBrowser, login.step.value)
            login.callback("the-code", "state-1")
            assertEquals(listOf("the-code" to "verifier-1"), signIn.exchanged)
            assertEquals(listOf("sk-or-v1-new"), saved)
            assertEquals(SignInStep.Idle, login.step.value)
            assertNull(login.pending)
        }

    @Test
    fun backInFrontWithNoCodeMeansTheBrowserWasClosed() =
        runTest {
            val login = login(FakeSignIn())
            login.start()
            login.resumed()
            assertEquals(SignInStep.Failed(SignInFailure.CLOSED), login.step.value)
            // A code after that belongs to no sign-in under way: ignored.
            login.callback("late", "state-1")
            assertEquals(SignInStep.Failed(SignInFailure.CLOSED), login.step.value)
            assertTrue(saved.isEmpty())
        }

    @Test
    fun resumingWithNoSignInUnderWaySaysNothing() =
        runTest {
            val login = login(FakeSignIn())
            login.resumed()
            assertEquals(SignInStep.Idle, login.step.value)
        }

    @Test
    fun anAnswerWithNoCodeIsASignInNotApproved() =
        runTest {
            val signIn = FakeSignIn()
            val login = login(signIn)
            login.start()
            login.callback(null, "state-1")
            assertEquals(SignInStep.Failed(SignInFailure.DECLINED), login.step.value)
            assertTrue(signIn.exchanged.isEmpty())
        }

    @Test
    fun aCodeRefusedIsToldApartByTheTimeTheSignInTook() =
        runTest {
            val signIn = FakeSignIn(KeyExchange.Refused)
            val login = login(signIn)
            login.start()
            later(9.minutes)
            login.callback("code", "state-1")
            assertEquals(SignInStep.Failed(SignInFailure.REFUSED), login.step.value)

            login.start()
            later(10.minutes + 1.seconds)
            login.callback("code", "state-2")
            assertEquals(SignInStep.Failed(SignInFailure.EXPIRED), login.step.value)
        }

    @Test
    fun noConnectionOrAKeyThatCouldNotBeSavedDoesNotFinish() =
        runTest {
            val offline = login(FakeSignIn(KeyExchange.Offline))
            offline.start()
            offline.callback("code", "state-1")
            assertEquals(SignInStep.Failed(SignInFailure.OFFLINE), offline.step.value)

            val unsaved = login(FakeSignIn(), saves = false)
            unsaved.start()
            unsaved.callback("code", "state-1")
            assertEquals(SignInStep.Failed(SignInFailure.REFUSED), unsaved.step.value)
        }

    @Test
    fun aNewSignInClearsTheLastOneSLineAndEachHasItsOwnVerifier() =
        runTest {
            val signIn = FakeSignIn()
            val login = login(signIn)
            login.start()
            login.resumed()
            login.dismiss()
            assertEquals(SignInStep.Idle, login.step.value)
            login.start()
            login.callback("code", "state-2")
            assertEquals(listOf("code" to "verifier-2"), signIn.exchanged)
        }

    @Test
    fun aSignInKeptInTheSavedStateFinishesAfterTheProcessEnded() =
        runTest {
            val signIn = FakeSignIn()
            val started = time
            val login = login(signIn)
            login.restore(OpenRouterLogin.Pending("saved-verifier", "saved-state", started))
            assertEquals(SignInStep.InBrowser, login.step.value)
            login.callback("code", "saved-state")
            assertEquals(listOf("code" to "saved-verifier"), signIn.exchanged)
            assertEquals(listOf("sk-or-v1-new"), saved)
        }

    @Test
    fun anAnswerWithNoStateIsTakenForTheSignInUnderWay() =
        runTest {
            // #1396: the redirect may drop the callback's own `?s=`; the code needs the verifier anyway.
            val signIn = FakeSignIn()
            val login = login(signIn)
            login.start()
            assertEquals(StateMatch.MISSING, login.callback("code", null))
            assertEquals(listOf("code" to "verifier-1"), signIn.exchanged)
            assertEquals(SignInStep.Idle, login.step.value)
            // Taken once: the same answer again belongs to no sign-in under way.
            assertEquals(StateMatch.NO_SIGN_IN, login.callback("code", null))
            assertEquals(1, signIn.exchanged.size)

            login.start()
            assertEquals(StateMatch.MISSING, login.callback("code", ""))
            assertEquals(2, signIn.exchanged.size)
        }

    @Test
    fun anAnswerWithNoStateAndNoCodeIsASignInNotApproved() =
        runTest {
            val signIn = FakeSignIn()
            val login = login(signIn)
            login.start()
            assertEquals(StateMatch.MISSING, login.callback(null, null))
            assertEquals(SignInStep.Failed(SignInFailure.DECLINED), login.step.value)
            assertTrue(signIn.exchanged.isEmpty())
        }

    @Test
    fun anAnswerWithAnotherStateIsNotTakenAndEndsTheSignInAsNotFinishedNotAsClosed() =
        runTest {
            val signIn = FakeSignIn()
            val login = login(signIn)
            login.start()
            assertEquals(StateMatch.DIFFERED, login.callback("forged", "another"))
            assertTrue(signIn.exchanged.isEmpty())
            assertEquals(SignInStep.Failed(SignInFailure.REFUSED), login.step.value)
            // The app comes to the front with that answer: an answer came, so the browser was not closed.
            login.resumed()
            assertEquals(SignInStep.Failed(SignInFailure.REFUSED), login.step.value)
            // The sign-in has ended: its own answer after that is not taken either.
            assertEquals(StateMatch.NO_SIGN_IN, login.callback("code", "state-1"))
            assertTrue(signIn.exchanged.isEmpty())
            assertTrue(saved.isEmpty())
        }

    @Test
    fun anAnswerWithTheSignInsStateIsMatched() =
        runTest {
            val login = login(FakeSignIn())
            assertEquals(StateMatch.NO_SIGN_IN, login.callback("code", "state-1"))
            login.start()
            assertEquals(StateMatch.MATCHED, login.callback("code", "state-1"))
        }

    @Test
    fun aFailureWhileSavingEndsTheSignInAsNotFinished() =
        runTest {
            val login =
                OpenRouterLogin(
                    FakeSignIn(),
                    save = { throw IllegalStateException("no store") },
                    scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
                    clock = clock,
                )
            login.start()
            login.callback("code", "state-1")
            assertEquals(SignInStep.Failed(SignInFailure.REFUSED), login.step.value)
        }

    @Test
    fun theAnswersParametersAreReadWhetherOpenRouterAddsThemWithAmpersandOrQuestionMark() {
        assertEquals(mapOf("s" to "abc", "code" to "xyz"), OpenRouterLogin.answerOf("s=abc&code=xyz"))
        assertEquals(mapOf("s" to "abc", "code" to "xyz"), OpenRouterLogin.answerOf("s=abc?code=xyz"))
        assertEquals(emptyMap<String, String>(), OpenRouterLogin.answerOf(null))
    }

    @Test
    fun theAnswersParametersArePercentDecoded() {
        assertEquals(mapOf("s" to "a-b_c", "code" to "x y+z"), OpenRouterLogin.answerOf("%73=a%2Db_c&code=x%20y+z"))
        // The query encoded once more, as a whole.
        assertEquals(mapOf("s" to "abc", "code" to "xyz"), OpenRouterLogin.answerOf("s%3Dabc%26code%3Dxyz"))
        // Not well formed: kept as given.
        assertEquals(mapOf("code" to "x%zz"), OpenRouterLogin.answerOf("code=x%zz"))
        // The first of a name counts.
        assertEquals(mapOf("s" to "first"), OpenRouterLogin.answerOf("s=first&s=second"))
    }

    @Test
    fun theAnswerIsReadFromTheQueryOrFailingThatTheFragment() {
        val callback = "https://sleevesight.app/auth/openrouter/"
        val kept = OpenRouterLogin.answerAt("$callback?s=abc&code=xyz")
        assertEquals("abc", kept[OpenRouterLogin.STATE])
        assertEquals("xyz", kept["code"])
        val dropped = OpenRouterLogin.answerAt("$callback?code=xyz")
        assertNull(dropped[OpenRouterLogin.STATE])
        assertEquals("xyz", dropped["code"])
        val naive = OpenRouterLogin.answerAt("$callback?s=abc?code=xyz")
        assertEquals("abc", naive[OpenRouterLogin.STATE])
        assertEquals("xyz", naive["code"])
        val fragment = OpenRouterLogin.answerAt("$callback?s=abc#code=xyz")
        assertEquals("abc", fragment[OpenRouterLogin.STATE])
        assertEquals("xyz", fragment["code"])
        val both = OpenRouterLogin.answerAt("$callback?code=query#code=fragment&s=abc")
        assertEquals("query", both["code"])
        assertEquals("abc", both[OpenRouterLogin.STATE])
        val none = OpenRouterLogin.answerAt(callback)
        assertNull(none["code"])
        assertNull(none[OpenRouterLogin.STATE])
    }

    @Test
    fun theAnswerShowsItsParametersNamesNeverTheirValues() {
        val answer = OpenRouterLogin.answerAt("https://sleevesight.app/auth/openrouter/?s=the-state#code=the-code")
        assertEquals("query [s], fragment [code]", answer.toString())
        val line = SignInCallbacks.logLine(answer, StateMatch.MATCHED)
        assertEquals("OpenRouter sign-in answer: query [s], fragment [code]; state matched", line)
        assertTrue(line, "the-state" !in line && "the-code" !in line)
    }

    @Test
    fun theCallbackIsRecognisedByItsWholeAddressBeforeTheQuery() {
        // `OpenRouterSignIn.CALLBACK` (`:ai`, which `:app` reaches only through `:wiring`).
        val callback = "https://sleevesight.app/auth/openrouter/"
        assertTrue(OpenRouterLogin.isCallback("https://sleevesight.app/auth/openrouter/?s=st&code=abc", callback))
        assertTrue(OpenRouterLogin.isCallback("https://sleevesight.app/auth/openrouter/", callback))
        // Another page of the site, a longer path, the same path elsewhere, or the old custom scheme.
        assertTrue(!OpenRouterLogin.isCallback("https://sleevesight.app/?code=abc", callback))
        assertTrue(!OpenRouterLogin.isCallback("https://sleevesight.app/auth/openrouter/x?code=abc", callback))
        assertTrue(!OpenRouterLogin.isCallback("https://sleevesight.app.example/auth/openrouter/?code=abc", callback))
        assertTrue(!OpenRouterLogin.isCallback("http://sleevesight.app/auth/openrouter/?code=abc", callback))
        assertTrue(!OpenRouterLogin.isCallback("app.sleevesight://openrouter?code=abc", callback))
        assertTrue(!OpenRouterLogin.isCallback(null, callback))
    }

    @Test
    fun thePendingSignInNeverShowsItsVerifier() =
        runTest {
            val login = login(FakeSignIn())
            login.start()
            assertTrue("verifier" !in login.pending.toString())
        }
}
