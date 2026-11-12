package dev.mahali.hatch.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonObject

/** The control channel: `wss://<host>/api/control` with this WebSocket subprotocol. */
public object Control {
    public const val SUBPROTOCOL: String = "rex.control.v1"
    public const val API_REVISION: Int = 11
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
public sealed interface Envelope {
    /** Client to server, first message after the socket opens. */
    @Serializable @SerialName("hello")
    public data class Hello(
        @SerialName("api_revision") val apiRevision: Int = Control.API_REVISION,
        @SerialName("api_min_revision") val apiMinRevision: Int = Control.API_REVISION,
        val client: ClientInfo,
        @SerialName("reconnect_token") val reconnectToken: String? = null,
    ) : Envelope {
        override fun toString(): String = "Hello(apiRevision=$apiRevision, client=$client, resuming=${reconnectToken != null})"
    }

    /** Server to client, always its first message. Check [protocol] before sending anything else. */
    @Serializable @SerialName("welcome")
    public data class Welcome(
        val protocol: String,
        val limits: Limits,
        /** Left out by servers that cannot resume clients. */
        val client: WelcomeClient? = null,
        @SerialName("api_revision") val apiRevision: Int = 0,
        /** Optional server behaviours. Send a newer field only if its feature is listed. */
        val features: List<String> = emptyList(),
    ) : Envelope

    @Serializable @SerialName("request")
    public data class Request(val id: String, val method: String, val payload: JsonObject = JsonObject(emptyMap())) : Envelope

    /** [seq] is the last event reflected in the result; skip events at or below it. */
    @Serializable @SerialName("response")
    public data class Response(
        val id: String,
        val seq: Long = 0,
        val payload: JsonObject? = null,
        val error: ErrorInfo? = null,
    ) : Envelope

    /** [seq] counts events on this connection from 1, with no gaps. */
    @Serializable @SerialName("event")
    public data class Event(val seq: Long, val payload: JsonObject) : Envelope
}

@Serializable
public data class ClientInfo(
    val kind: String = "app",
    val platform: String = "other",
    val name: String = "Hatch",
    val version: String,
    val label: String,
)

@Serializable
public data class Limits(@SerialName("max_message_bytes") val maxMessageBytes: Int)

/** [reconnectToken] continues this client after a drop. Never log it. */
@Serializable
public data class WelcomeClient(
    val id: String,
    @SerialName("reconnect_token") val reconnectToken: String,
    val resumed: Boolean = false,
) {
    override fun toString(): String = "WelcomeClient(id=$id, resumed=$resumed)"
}

@Serializable
public data class ErrorInfo(val code: String, val message: String)

public val ProtocolJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

public fun Envelope.encode(): String = ProtocolJson.encodeToString(Envelope.serializer(), this)

public fun decodeEnvelope(text: String): Envelope = ProtocolJson.decodeFromString(Envelope.serializer(), text)
