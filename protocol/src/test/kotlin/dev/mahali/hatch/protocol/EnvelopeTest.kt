package dev.mahali.hatch.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class EnvelopeTest {
    @Test fun `an error response decodes`() {
        val text = """{"type":"response","id":"7","error":{"code":"not_found","message":"no such session"},"extra":1}"""
        assertEquals(Envelope.Response("7", error = ErrorInfo("not_found", "no such session")), decodeEnvelope(text))
    }

    @Test fun `the documented welcome decodes`() {
        val text = """{"type":"welcome","protocol":"rex.control.v1","limits":{"max_message_bytes":1048576},""" +
            """"client":{"id":"client:a","reconnect_token":"t"},"api_revision":11,"carriers":[]}"""
        val welcome = decodeEnvelope(text) as Envelope.Welcome
        assertEquals(Control.SUBPROTOCOL, welcome.protocol)
        assertEquals(1_048_576, welcome.limits.maxMessageBytes)
        assertEquals(11, welcome.apiRevision)
        assertEquals("client:a", welcome.client?.id)
    }

    @Test fun `the reconnect token never appears in logs`() {
        assertEquals("WelcomeClient(id=client:a, resumed=false)", WelcomeClient("client:a", "secret").toString())
        val hello = Envelope.Hello(client = ClientInfo(version = "0.1.0", label = "Pixel"), reconnectToken = "secret")
        assertEquals(false, hello.toString().contains("secret"))
    }

    @Test fun `hello leaves out an absent reconnect token`() {
        val hello = Envelope.Hello(client = ClientInfo(version = "0.1.0", label = "Pixel"))
        assertEquals(
            """{"type":"hello","api_revision":11,"api_min_revision":11,""" +
                """"client":{"kind":"app","platform":"other","name":"Hatch","version":"0.1.0","label":"Pixel"}}""",
            hello.encode(),
        )
    }
}
