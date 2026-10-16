package app.sleevesight.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SecretTest {
    private val key = "sk-test-0123456789abcdef"

    @Test
    fun `toString and string templates show no part of the value`() {
        val secret = Secret(key)
        assertEquals(Secret.REDACTED, secret.toString())
        assertFalse("key=$secret".contains(key))
        assertFalse(listOf(secret).toString().contains(key))
    }

    @Test
    fun `a data class holding a secret prints it redacted`() {
        data class Holder(
            val name: String,
            val key: Secret,
        )
        assertFalse(Holder("anthropic", Secret(key)).toString().contains(key))
    }

    @Test
    fun `reveal gives the value`() {
        assertEquals(key, Secret(key).reveal())
    }
}
