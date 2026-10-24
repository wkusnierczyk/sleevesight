package app.sleevesight.app.keys

import app.sleevesight.app.ui.scan.QrDecoder
import app.sleevesight.core.Secret
import app.sleevesight.core.keys.KeyName
import app.sleevesight.core.keys.KeySetup
import app.sleevesight.core.keys.KeyShape
import app.sleevesight.core.keys.SetupRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Properties

/**
 * The API keys page (`api-keys.html`, #436) against the app, by the vectors its own code made
 * (`assets/api-keys/make_vectors.mjs`): its setup codes are what [KeySetup] writes and reads, its
 * QR encoder's codes are read by the app's decoder, and it checks a key's shape as [KeyShape] does.
 */
class ApiKeysPageTest {
    private val properties =
        Properties().apply {
            ApiKeysPageTest::class.java.getResourceAsStream("/api-keys-vectors.properties")!!.use { load(it) }
        }

    private fun count(name: String) = properties.getProperty(name).toInt()

    private fun text(name: String) = bytes(properties.getProperty(name)).toString(Charsets.UTF_8)

    private fun keysOf(i: Int): Map<KeyName, String> =
        properties.getProperty("setup.$i.keys").split(",").associate { entry ->
            KeyName.of(entry.substringBefore(':'))!! to bytes(entry.substringAfter(':')).toString(Charsets.UTF_8)
        }

    @Test
    fun `the page's setup codes are the app's, and read back as their keys`() {
        for (i in 0 until count("setups")) {
            val keys = keysOf(i)
            val text = text("setup.$i.text")
            assertEquals("setup $i", KeySetup.write(keys.mapValues { Secret(it.value) }), text)
            val read = KeySetup.read(text) as SetupRead.Setup
            assertEquals("setup $i", keys, read.keys.mapValues { it.value.reveal() })
            assertTrue("setup $i", read.refused.isEmpty() && read.unknown.isEmpty() && read.unreadable == 0)
        }
    }

    @Test
    fun `the page's QR codes of setup codes are read by the app's decoder`() {
        val versions = mutableListOf<Int>()
        for (i in 0 until count("setups")) {
            val modules = properties.getProperty("setup.$i.qr").split(",").map { row -> row.map { it == '1' } }
            val version = count("setup.$i.version")
            assertEquals("setup $i", 17 + 4 * version, modules.size)
            versions += version
            assertEquals("setup $i", text("setup.$i.text"), decode(modules))
        }
        // From version 7 a code holds its version, from 10 a longer count; both are covered.
        assertTrue(versions.toString(), versions.any { it in 7..9 } && versions.any { it >= 10 })
    }

    @Test
    fun `the page checks a key's shape as the app does`() {
        for (i in 0 until count("shapes")) {
            val name = KeyName.of(properties.getProperty("shape.$i.name"))!!
            val value = text("shape.$i.value")
            val expected = properties.getProperty("shape.$i.problem")
            assertEquals("shape $i ($name)", expected, KeyShape.check(name, value)?.name ?: "NONE")
        }
    }

    // The modules drawn at eight pixels each, dark on white with the quiet zone, as a camera sees them.
    private fun decode(modules: List<List<Boolean>>): String? {
        val quiet = 4
        val scale = 8
        val side = (modules.size + 2 * quiet) * scale
        val luminance =
            ByteArray(side * side) { i ->
                val x = i % side / scale - quiet
                val y = i / side / scale - quiet
                val dark = x in modules.indices && y in modules.indices && modules[y][x]
                if (dark) 0 else -1
            }
        return QrDecoder.decode(luminance, side, side, side)
    }

    private fun bytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
