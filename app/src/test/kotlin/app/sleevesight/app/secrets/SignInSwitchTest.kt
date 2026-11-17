package app.sleevesight.app.secrets

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.sleevesight.app.MemoryStore
import app.sleevesight.app.memorySettings
import app.sleevesight.app.testCipher
import app.sleevesight.core.Secret
import app.sleevesight.core.ai.KeyExchange
import app.sleevesight.core.ai.KeySignIn
import app.sleevesight.core.ai.SignInStart
import app.sleevesight.core.model.ProviderId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * A sign-in with OpenRouter finishes only once its key is saved and OpenRouter is switched on
 * (#1157): when the switch cannot be saved, the key goes again, the service on before stays on, and
 * the sign-in says OpenRouter did not hand over the key.
 */
class SignInSwitchTest {
    /** The settings' file, whose writes fail once [failing] is set. */
    private class FailingStore(
        private val inner: MemoryStore = MemoryStore(),
    ) : DataStore<Preferences> {
        var failing = false

        override val data: Flow<Preferences> = inner.data

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            if (failing) throw IOException("disk full")
            return inner.updateData(transform)
        }
    }

    private val file = FailingStore()
    private val settings = memorySettings(file)
    private val secrets =
        SecretStore(MemoryStore(), testCipher(), legacyKeystore = false, dispatcher = Dispatchers.Unconfined)

    private val signIn =
        object : KeySignIn {
            override val callback = "app.sleevesight://openrouter"

            override fun begin() = SignInStart("verifier", "https://openrouter.ai/auth")

            override suspend fun exchange(
                code: String,
                verifier: String,
            ): KeyExchange = KeyExchange.Key(Secret("sk-or-v1-new"))
        }

    private fun TestScope.login() =
        OpenRouterLogin(
            signIn,
            save = { key ->
                saveAndSwitchOn(secrets, key) { settings.update { it.copy(aiProvider = ProviderId.OPENROUTER) } }
            },
            scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
        )

    @Test
    fun theKeySavedSwitchesOpenRouterOnAndFinishes() =
        runTest {
            settings.update { it.copy(aiProvider = ProviderId.ANTHROPIC) }
            val login = login()
            login.start()
            login.callback("code")
            assertEquals(SignInStep.Idle, login.step.value)
            assertEquals(ProviderId.OPENROUTER, settings.settings.first().aiProvider)
            assertEquals(SecretState.SAVED, secrets.states.first()[SecretId.OPENROUTER])
        }

    @Test
    fun aSwitchThatCannotBeSavedDoesNotFinishAndLeavesNoKey() =
        runTest {
            settings.update { it.copy(aiProvider = ProviderId.ANTHROPIC) }
            file.failing = true
            val login = login()
            login.start()
            login.callback("code")
            assertEquals(SignInStep.Failed(SignInFailure.REFUSED), login.step.value)
            assertEquals(ProviderId.ANTHROPIC, settings.settings.first().aiProvider)
            assertEquals(SecretState.ABSENT, secrets.states.first()[SecretId.OPENROUTER])
        }
}
