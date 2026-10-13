package io.github.wkusnierczyk.sleevesight.net

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandler
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

/**
 * A client over `MockEngine`, so no test touches the network. The engine runs on the test's
 * scheduler, so the rate limits, backoffs and the times the handler records are all virtual.
 */
internal fun TestScope.mockClient(
    config: NetConfig = NetConfig(appVersion = "0.0.0-test"),
    log: NetLog = NetLog.NONE,
    handler: MockRequestHandler,
): KtorNetClient {
    val engine =
        MockEngine(
            MockEngineConfig().apply {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler(handler)
            },
        )
    return KtorNetClient(config, engine, log, testScheduler.timeSource)
}

/** Collects the log lines, for the redaction tests. */
internal class RecordingLog : NetLog {
    val lines = mutableListOf<String>()

    override fun log(line: String) {
        lines += line
    }
}
