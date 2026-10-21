package app.sleevesight.cli

import app.sleevesight.core.keys.KeyName
import app.sleevesight.core.keys.KeySetup
import app.sleevesight.core.keys.SetupRead
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KeysQrTest {
    private val anthropic = "sk-ant-api03-" + "a1B2_c3-D4".repeat(9)
    private val exa = "0c6d1e2f-3a4b-5c6d-7e8f-9a0b1c2d3e4f"
    private val lastFm = "0123456789abcdef0123456789abcdef"
    private val dir = createTempDirectory("keys-qr").toFile()

    private class Run(
        val status: Int,
        val out: String,
        val err: String,
    )

    private fun run(
        vararg args: String,
        environment: Map<String, String> = emptyMap(),
        stdin: String = "",
    ): Run {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val status =
            Cli(PrintStream(out, true), PrintStream(err, true), environment, ByteArrayInputStream(stdin.toByteArray()))
                .run(listOf("keys-qr") + args)
        return Run(status, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    /** The text the drawn code holds, read back from its half blocks. */
    private fun decode(drawn: String): String {
        val rows =
            drawn
                .lines()
                .filter { it.isNotEmpty() }
                .map { it.removePrefix("\u001b[30;107m").removeSuffix("\u001b[0m") }
        val width = rows.first().length
        val modules = BitMatrix(width, rows.size * 2)
        rows.forEachIndexed { line, row ->
            row.forEachIndexed { x, c ->
                if (c == '█' || c == '▀') modules.set(x, line * 2)
                if (c == '█' || c == '▄') modules.set(x, line * 2 + 1)
            }
        }
        // Each module as a 4×4 block of pixels, black on white, as a camera would see it.
        val scale = 4
        val pixels = ByteArray(modules.width * scale * modules.height * scale) { -1 }
        for (y in 0 until modules.height * scale) {
            for (x in 0 until modules.width * scale) {
                if (modules[x / scale, y / scale]) pixels[y * modules.width * scale + x] = 0
            }
        }
        val source =
            PlanarYUVLuminanceSource(
                pixels,
                modules.width * scale,
                modules.height * scale,
                0,
                0,
                modules.width * scale,
                modules.height * scale,
                false,
            )
        return QRCodeReader()
            .decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.PURE_BARCODE to true))
            .text
    }

    @Test
    fun `the keys file's keys are drawn as a setup code, and no key is printed`() {
        val keys = File(dir, "keys.env").apply { writeText("SLEEVESIGHT_ANTHROPIC_API_KEY=$anthropic\n") }
        val run = run("--keys", keys.path, environment = mapOf(Keys.LASTFM to lastFm))
        assertEquals(0, run.status, run.err)
        val read = assertIs<SetupRead.Setup>(KeySetup.read(decode(run.out)))
        assertEquals(
            mapOf(KeyName.ANTHROPIC to anthropic, KeyName.LASTFM to lastFm),
            read.keys.mapValues { it.value.reveal() },
        )
        for (key in listOf(anthropic, lastFm)) {
            assertFalse(key in run.out)
            assertFalse(key in run.err)
        }
        assertTrue("Anthropic, Last.fm" in run.err)
    }

    @Test
    fun `with --name-from, only the keys given are drawn, one of them from standard input`() {
        val exaFile = File(dir, "exa").apply { writeText("\n  $exa  \n") }
        val run =
            run(
                "--anthropic-from",
                "-",
                "--exa-from",
                exaFile.path,
                environment = mapOf(Keys.LASTFM to lastFm),
                stdin = "$anthropic\n",
            )
        assertEquals(0, run.status, run.err)
        val read = assertIs<SetupRead.Setup>(KeySetup.read(decode(run.out)))
        assertEquals(
            mapOf(KeyName.ANTHROPIC to anthropic, KeyName.EXA to exa),
            read.keys.mapValues { it.value.reveal() },
        )
    }

    @Test
    fun `a key of the wrong shape is refused, and nothing is drawn`() {
        val run = run("--lastfm-from", "-", stdin = "not-a-lastfm-key")
        assertEquals(Cli.EXIT_USAGE, run.status)
        assertEquals("", run.out)
        assertTrue("Last.fm key does not look like one" in run.err)
    }

    @Test
    fun `no keys, two from standard input, an unknown option or a missing file is a usage error`() {
        val empty = File(dir, "none.env")
        assertEquals(Cli.EXIT_USAGE, run("--keys", empty.path).status)
        assertEquals(Cli.EXIT_USAGE, run("--anthropic-from", "-", "--exa-from", "-").status)
        assertEquals(Cli.EXIT_USAGE, run("--openai-from", "-").status)
        assertEquals(Cli.EXIT_USAGE, run("--exa-from", File(dir, "missing").path).status)
    }
}
