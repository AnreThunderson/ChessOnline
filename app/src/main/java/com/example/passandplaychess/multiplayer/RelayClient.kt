package com.example.passandplaychess.multiplayer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// ── Domain types ──────────────────────────────────────────────────────────────

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data class Connected(val clientId: String, val room: String, val role: String) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

sealed class RelayEvent {
    data class PeerJoined(val role: String, val occupants: Int) : RelayEvent()
    data class PeerLeft(val role: String) : RelayEvent()
    data class MoveReceived(val uci: String, val seq: Int) : RelayEvent()

    data class StateSyncReceived(
        val fen: String,
        val sideToMove: String,
        val moveHistory: List<String>,
        val initialTimeMs: Long? = null
    ) : RelayEvent()

    data class RemoteError(val message: String) : RelayEvent()
}

// ── RelayClient ───────────────────────────────────────────────────────────────

class RelayClient(private val serverUrl: String) {

    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null

    private val _connectionState =
        MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    /** Invoked (on the OkHttp thread) whenever a relay event arrives. */
    var onEvent: ((RelayEvent) -> Unit)? = null

    fun connect(roomCode: String, role: String) {
        disconnect()
        _connectionState.value = ConnectionState.Connecting

        val request = Request.Builder().url(serverUrl).build()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                val hello = JSONObject().apply {
                    put("type", "hello")
                    put("room", roomCode.uppercase())
                    put("role", role)
                }
                ws.send(hello.toString())
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                _connectionState.value = ConnectionState.Error(
                    t.message ?: "Connection failed"
                )
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                _connectionState.value = ConnectionState.Disconnected
            }
        })
    }

    fun sendMove(uci: String, seq: Int) {
        send(JSONObject().apply {
            put("type", "move")
            put("uci", uci)
            put("seq", seq)
        })
    }

    fun sendStateSync(
        fen: String,
        sideToMove: String,
        moveHistory: List<String> = emptyList(),
        initialTimeMs: Long? = null
    ) {
        send(JSONObject().apply {
            put("type", "state_sync")
            put("fen", fen)
            put("sideToMove", sideToMove)
            put("moveHistory", org.json.JSONArray(moveHistory))
            if (initialTimeMs != null) put("initialTimeMs", initialTimeMs)
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "Client disconnected")
        webSocket = null
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun send(json: JSONObject) {
        webSocket?.send(json.toString())
    }

    private fun handleMessage(text: String) {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return

        when (msg.optString("type")) {
            "welcome" -> {
                _connectionState.value = ConnectionState.Connected(
                    clientId = msg.optString("clientId"),
                    room = msg.optString("room"),
                    role = msg.optString("role")
                )
            }

            "peer_joined" -> {
                onEvent?.invoke(
                    RelayEvent.PeerJoined(
                        role = msg.optString("role"),
                        occupants = msg.optInt("occupants", 2)
                    )
                )
            }

            "peer_left" -> {
                onEvent?.invoke(RelayEvent.PeerLeft(role = msg.optString("role")))
            }

            "move" -> {
                onEvent?.invoke(
                    RelayEvent.MoveReceived(
                        uci = msg.optString("uci"),
                        seq = msg.optInt("seq", 0)
                    )
                )
            }

            "state_sync" -> {
                val history = msg.optJSONArray("moveHistory")
                val historyList = buildList {
                    if (history != null) {
                        for (i in 0 until history.length()) add(history.getString(i))
                    }
                }
                val initialTimeMs =
                    if (msg.has("initialTimeMs")) msg.optLong("initialTimeMs") else null

                onEvent?.invoke(
                    RelayEvent.StateSyncReceived(
                        fen = msg.optString("fen"),
                        sideToMove = msg.optString("sideToMove"),
                        moveHistory = historyList,
                        initialTimeMs = initialTimeMs
                    )
                )
            }

            "error" -> onEvent?.invoke(RelayEvent.RemoteError(msg.optString("message")))
            "ping" -> send(JSONObject().put("type", "pong"))
            "pong" -> { /* ignore */ }
            else -> { /* ignore */ }
        }
    }
}
