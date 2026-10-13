package io.github.wkusnierczyk.sleevesight.net

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlin.time.Clock
import kotlin.time.Instant

/** The wall clock at virtual time zero, a whole second, so HTTP-dates (to the second) are exact. */
internal const val EPOCH_MILLIS = 1_790_000_000_000L

/** A wall clock that follows the test's virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.virtualClock(): Clock =
    object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(EPOCH_MILLIS + testScheduler.currentTime)
    }

/** A `MockEngine` on the test's scheduler, so rate limits, backoffs and recorded times are all virtual. */
internal fun TestScope.mockEngine(handler: MockRequestHandler): MockEngine =
    MockEngine(
        MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler(handler)
            reuseHandlers = true
        },
    )

/** A client over `MockEngine`, so no test touches the network. Close it, or use [withClient]. */
internal fun TestScope.mockClient(
    config: NetConfig = NetConfig(appVersion = "0.0.0-test"),
    log: NetLog = NetLog.NONE,
    handler: MockRequestHandler,
): KtorNetClient = KtorNetClient(config, mockEngine(handler), log, testScheduler.timeSource, virtualClock())

/** Runs [block] with a [mockClient], closed afterwards. */
internal suspend fun <T> TestScope.withClient(
    config: NetConfig = NetConfig(appVersion = "0.0.0-test"),
    log: NetLog = NetLog.NONE,
    handler: MockRequestHandler,
    block: suspend (KtorNetClient) -> T,
): T = mockClient(config, log, handler).use { block(it) }

/** Collects the log lines, for the redaction tests. */
internal class RecordingLog : NetLog {
    val lines = mutableListOf<String>()

    override fun log(line: String) {
        lines += line
    }
}
