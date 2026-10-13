package io.github.wkusnierczyk.sleevesight.net

import kotlinx.io.IOException

/**
 * The engine refused the server's answer as a breach of HTTP (OkHttp's `ProtocolException`, for a
 * 407 from a server that is not a proxy). An [IOException], so it travels through the engine like
 * one, but `:net` reports it as [NetError.Protocol], never retried, rather than as `Offline`.
 * [reason] is the original exception's type; its message is not kept.
 */
internal class EngineProtocolException(
    val reason: String,
) : IOException("protocol violation")
