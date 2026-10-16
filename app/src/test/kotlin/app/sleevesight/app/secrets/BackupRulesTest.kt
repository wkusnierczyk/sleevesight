package app.sleevesight.app.secrets

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * The stored keys are sealed with a Keystore key that never leaves the phone, so a backup of them
 * would restore as unreadable entries, and a backup of the settings would carry the players and
 * sources the user chose. Neither is backed up or transferred: the DataStore files are under the
 * app's `files/`, which the rules exclude with everything else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BackupRulesTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun backupIsOffBeforeAndroid12() {
        assertEquals(0, app.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    @Test
    fun theStoresAreUnderTheFilesDomain() {
        assertTrue(app.preferencesDataStoreFile("secrets").canonicalPath.startsWith(app.filesDir.canonicalPath))
    }

    @Test
    fun theFilesDomainIsExcludedFromCloudBackupAndDeviceTransfer() {
        val excluded = mutableMapOf<String, MutableSet<String>>()
        val parser = app.resources.getXml(R.xml.data_extraction_rules)
        var section: String? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "cloud-backup", "device-transfer" -> section = parser.name
                "exclude" -> {
                    val domain = parser.getAttributeValue(null, "domain")
                    val path = parser.getAttributeValue(null, "path")
                    if (path == ".") excluded.getOrPut(section!!) { mutableSetOf() } += domain
                }
            }
        }
        for (s in listOf("cloud-backup", "device-transfer")) {
            assertTrue("$s excludes the files", "file" in excluded[s].orEmpty())
            assertTrue("$s excludes the device-protected files", "device_file" in excluded[s].orEmpty())
        }
    }
}
