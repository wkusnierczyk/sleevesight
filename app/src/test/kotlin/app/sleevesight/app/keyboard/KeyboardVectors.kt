package app.sleevesight.app.keyboard

import java.util.Properties

/**
 * The vectors the page's own code made (`assets/keyboard/make_vectors.mjs`): a session from a fixed
 * id and key, the messages it sealed, and the QR code of its code.
 */
object KeyboardVectors {
    private val properties =
        Properties().apply {
            KeyboardVectors::class.java.getResourceAsStream("/keyboard-vectors.properties")!!.use { load(it) }
        }

    val code: String = properties.getProperty("code")
    val service: String = properties.getProperty("service")

    /** Each message: its counter, its text, and the message the page sealed. */
    val messages: List<Triple<Long, String, ByteArray>> =
        (0 until properties.getProperty("messages").toInt()).map { i ->
            Triple(
                properties.getProperty("message.$i.counter").toLong(),
                bytes(properties.getProperty("message.$i.text")).toString(Charsets.UTF_8),
                bytes(properties.getProperty("message.$i.sealed")),
            )
        }

    /** The QR code's modules, rows of dark (true) and light, without the quiet zone. */
    val qr: List<List<Boolean>> = properties.getProperty("qr").split(",").map { row -> row.map { it == '1' } }

    private fun bytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
