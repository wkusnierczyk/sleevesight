package io.github.wkusnierczyk.sleevesight.ai

import kotlin.test.Test
import kotlin.test.assertEquals

class ServerSentEventsTest {
    private fun events(vararg lines: String): List<ServerSentEvent> {
        val parser = ServerSentEvents()
        return lines.mapNotNull { parser.line(it) }
    }

    @Test
    fun `an event is its type and data, ended by a blank line`() {
        assertEquals(
            listOf(ServerSentEvent("ping", """{"type": "ping"}""")),
            events("event: ping", """data: {"type": "ping"}""", ""),
        )
    }

    @Test
    fun `data lines join with a newline, one leading space is dropped, comments and other fields are skipped`() {
        assertEquals(
            listOf(ServerSentEvent("message", "a\n b\n")),
            events(": comment", "id: 7", "retry: 10", "data:a", "data:  b", "data", ""),
        )
    }

    @Test
    fun `an event without data is dropped and its type does not carry over`() {
        assertEquals(listOf(ServerSentEvent("message", "x")), events("event: lonely", "", "data: x", ""))
    }

    @Test
    fun `an unfinished event at the end is not dispatched`() {
        assertEquals(emptyList(), events("event: a", "data: x"))
    }

    @Test
    fun `a byte order mark at the start is ignored`() {
        assertEquals(listOf(ServerSentEvent("a", "x")), events("\uFEFFevent: a", "data: x", ""))
    }
}
