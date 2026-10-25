package app.sleevesight.app.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.sleevesight.app.TestStores
import app.sleevesight.core.settings.ReportButton
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/**
 * The report button's *Hide* as the app saves it (#518, `SettingsStore.reportButtonHider`): stored
 * as *Hide* and read so after a restart, in either build; and after an I/O error, false, with
 * nothing thrown and the setting as it was.
 */
class ReportButtonHiderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val stores by lazy { TestStores(tmp.root) }
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun close() {
        saves.cancel()
        stores.close()
    }

    private val testBuild = Settings(developerMode = true, reportButton = ReportButton.AUTO)

    @Test
    fun hideIsSavedAndReadBackAfterARestartInEitherBuild() {
        for (defaults in listOf(testBuild, Settings(developerMode = true))) {
            val store = SettingsStore(stores.open("settings-${defaults.reportButton}"), defaults)
            runBlocking { store.update { it.copy(developerMode = true, reportButton = ReportButton.SHOW) } }

            val saved = runBlocking { store.reportButtonHider(saves)().await() }
            assertTrue(saved)
            stores.close()

            val read =
                runBlocking {
                    SettingsStore(
                        stores.open("settings-${defaults.reportButton}"),
                        defaults,
                    ).settings.first()
                }
            assertEquals(ReportButton.HIDE, read.reportButton)
            assertTrue("Developer mode stays", read.developerMode)
            stores.close()
        }
    }

    @Test
    fun aSaveThatFailsReturnsFalseAndLeavesTheSettingAsItWas() {
        val real = stores.open("settings")
        val store = SettingsStore(real, testBuild)
        runBlocking { store.update { it.copy(reportButton = ReportButton.SHOW) } }
        val failing =
            object : DataStore<Preferences> {
                override val data = real.data

                override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                    throw IOException("/data/user/0/app.sleevesight/files/datastore/settings")
            }

        val saved = runBlocking { SettingsStore(failing, testBuild).reportButtonHider(saves)().await() }
        assertFalse(saved)
        assertEquals(ReportButton.SHOW, runBlocking { store.settings.first() }.reportButton)
    }
}
