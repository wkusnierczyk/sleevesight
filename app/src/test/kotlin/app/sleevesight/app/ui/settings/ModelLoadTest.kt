package app.sleevesight.app.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.testCipher
import app.sleevesight.core.model.ProviderId
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class ModelLoadTest {
    /** A secrets file whose read never ends, as a key store or a disk that hangs would. */
    private val hanging =
        object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { awaitCancellation() }

            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                awaitCancellation()
        }

    @Test
    fun withoutAClientTheKeyIsNotRead() =
        runBlocking {
            val secrets = SecretStore(hanging, testCipher(), legacyKeystore = false)
            // *Unavailable* at once: the key is read only for a client to send it to.
            val loaded = withTimeout(5.seconds) { loadModels(ProviderId.ANTHROPIC, secrets, emptyMap()) }
            assertEquals(ModelLoad.Unavailable, loaded)
        }
}
