package com.zahah.tunnelview.network

import com.zahah.tunnelview.storage.CredentialsStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AuthHeaderInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private val header = CredentialsStore.HeaderConfig("X-Access-Key", "super-secret")
    private val jsonPayload = """{"hello":"world"}"""
    private val jsonBody = jsonPayload.toRequestBody("application/json".toMediaType())

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = OkHttpClient.Builder()
            .addInterceptor(AuthHeaderInterceptor { header })
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `adds header to GET`() {
        val recorded = execute("GET", null)
        assertEquals("GET", recorded.method)
        assertEquals(header.value, recorded.getHeader(header.name))
        assertEquals("", recorded.body.readUtf8())
    }

    @Test
    fun `preserves POST body`() {
        val recorded = execute("POST", jsonBody)
        assertEquals("POST", recorded.method)
        assertEquals(header.value, recorded.getHeader(header.name))
        assertTrue((recorded.getHeader("Content-Type") ?: "").startsWith("application/json"))
        assertEquals(jsonPayload, recorded.body.readUtf8())
    }

    @Test
    fun `preserves PUT body`() {
        val recorded = execute("PUT", jsonBody)
        assertEquals("PUT", recorded.method)
        assertEquals(header.value, recorded.getHeader(header.name))
        assertEquals(jsonPayload, recorded.body.readUtf8())
    }

    @Test
    fun `preserves PATCH body`() {
        val recorded = execute("PATCH", jsonBody)
        assertEquals("PATCH", recorded.method)
        assertEquals(header.value, recorded.getHeader(header.name))
        assertEquals(jsonPayload, recorded.body.readUtf8())
    }

    @Test
    fun `allows DELETE with body`() {
        val recorded = execute("DELETE", jsonBody)
        assertEquals("DELETE", recorded.method)
        assertEquals(header.value, recorded.getHeader(header.name))
        assertEquals(jsonPayload, recorded.body.readUtf8())
    }

    @Test
    fun `adds header to OPTIONS`() {
        val recorded = execute("OPTIONS", ByteArray(0).toRequestBody(null))
        assertEquals("OPTIONS", recorded.method)
        assertEquals(header.value, recorded.getHeader(header.name))
    }

    @Test
    fun `overrides existing header`() {
        server.enqueue(MockResponse().setBody("ok"))
        val request = Request.Builder()
            .url(server.url("/override"))
            .header(header.name, "old")
            .build()
        client.newCall(request).execute().close()
        val recorded = server.takeRequest()
        assertEquals(header.value, recorded.getHeader(header.name))
    }

    private fun execute(method: String, body: RequestBody?): RecordedRequest {
        server.enqueue(MockResponse().setBody("ok"))
        val request = Request.Builder()
            .url(server.url("/test"))
            .method(method, body)
            .build()
        client.newCall(request).execute().close()
        val recorded = server.takeRequest()
        assertNotNull("Server did not receive request", recorded)
        return recorded
    }

}
