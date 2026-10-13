package io.github.wkusnierczyk.sleevesight.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RetryPolicyTest {
    @Test
    fun `the backoff doubles up to its cap`() {
        val policy = RetryPolicy(maxAttempts = 10, initialDelay = 500.milliseconds, maxDelay = 3.seconds)

        assertEquals(
            listOf(500.milliseconds, 1.seconds, 2.seconds, 3.seconds, 3.seconds, 3.seconds),
            (1..6).map { policy.delayAfter(it) },
        )
    }

    @Test
    fun `nonsensical policies are refused`() {
        assertFailsWith<IllegalArgumentException> { RetryPolicy(maxAttempts = 0) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(initialDelay = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(initialDelay = 2.seconds, maxDelay = 1.seconds) }
    }
}
