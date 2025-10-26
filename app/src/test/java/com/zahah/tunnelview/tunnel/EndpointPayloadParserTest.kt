package com.zahah.tunnelview.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.json.JSONObject

class EndpointPayloadParserTest {

    @Test
    fun `parse flexible payload from host and port`() {
        val result = EndpointPayloadParser.parseFlexiblePayload("example.com:8080")
        assertEquals("example.com" to 8080, result)
    }

    @Test
    fun `parse flexible payload from json`() {
        val json = """{"host":"api.example.com","port":9000}"""
        val result = EndpointPayloadParser.parseFlexiblePayload(json)
        assertEquals("api.example.com" to 9000, result)
    }

    @Test
    fun `parse flexible payload from tcp url`() {
        val result = EndpointPayloadParser.parseFlexiblePayload("tcp://edge.example.io:443")
        assertEquals("edge.example.io" to 443, result)
    }

    @Test
    fun `parse ntfy envelope`() {
        val payload = JSONObject()
            .put("message", """{"host":"edge.example.io","port":8443}""")
            .toString()
        val result = EndpointPayloadParser.parseNtfyEvent(payload)
        assertEquals("edge.example.io" to 8443, result)
    }

    @Test
    fun `parse ntfy envelope with plain message`() {
        val payload = JSONObject()
            .put("message", "example.com:1234")
            .toString()
        val result = EndpointPayloadParser.parseNtfyEvent(payload)
        assertEquals("example.com" to 1234, result)
    }

    @Test
    fun `return null when payload invalid`() {
        val result = EndpointPayloadParser.parseFlexiblePayload("invalid")
        assertNull(result)
    }
}
