package io.github.wkusnierczyk.sleevesight.net

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

/**
 * The HTTP engine: the one per-platform part of `:net` (architecture, Multiplatform readiness). An
 * iOS target would supply Darwin's here. Timeouts are set through Ktor, not OkHttp.
 */
internal fun okHttpEngine(): HttpClientEngine = OkHttp.create()
