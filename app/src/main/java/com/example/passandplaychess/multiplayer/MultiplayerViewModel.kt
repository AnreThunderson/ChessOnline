package com.example.passandplaychess.multiplayer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.passandplaychess.BuildConfig
import com.example.passandplaychess.ChessGameState
import com.example.passandplaychess.GameResult
import com.example.passandplaychess.Side
import com.example.passandplaychess.Square
import com.example.passandplaychess.toUci
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ── UI state ──────────────────────────────────────────────────────────────────

sealed class MultiplayerUiState {
    data object Idle : MultiplayerUiState()
    data object Connecting : MultiplayerUiState()
    /** Waiting for the second player to join. */
    data class WaitingForPeer(val roomCode: String, val role: String) : MultiplayerUiState()
    /** Both players are connected and the game is in progress. */
    data class InGame(
        val roomCode: String,
        val role: String,
        val myTurn: Boolean
    ) : MultiplayerUiState()
    data class PeerDisconnected(val message: String) : MultiplayerUiState()
    data class Failure(val message: String) : MultiplayerUiState()
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

class MultiplayerViewModel : ViewModel() {

    private val relay = RelayClient(BuildConfig.WS_BASE_URL)

    private val _uiState = MutableStateFlow<MultiplayerUiState>(MultiplayerUiState.Idle)
    val uiState: StateFlow<MultiplayerUiState> = _uiState

    private val _gameState = MutableStateFlow(ChessGameState.initial())
    val gameState: StateFlow<ChessGameState> = _gameState

    /** Running sequence number for outbound moves. */
    private var outSeq = 0

    /** The role assigned after a successful hello/welcome exchange. */
    private var myRole: String = "host"

    init {
        // Observe connection-level events
        viewModelScope.launch {
            relay.connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Connecting -> {
                        _uiState.value = MultiplayerUiState.Connecting
                    }
                    is ConnectionState.Connected -> {
                        myRole = state.role
                        _uiState.value = MultiplayerUiState.WaitingForPeer(
                            roomCode = state.room,
                            role = state.role
                        )
                    }
                    is ConnectionState.Error -> {
                        _uiState.value = MultiplayerUiState.Failure(state.message)
                    }
                    is ConnectionState.Disconnected -> {
                        // Only update if we weren't already showing a richer status
                        val current = _uiState.value
                        if (current is MultiplayerUiState.InGame ||
                            current is MultiplayerUiState.WaitingForPeer
                        ) {
                            _uiState.value = MultiplayerUiState.Idle
                        }
                    }
                }
            }
        }

        // Observe relay events (moves, peer lifecycle)
        relay.onEvent = { event ->
            viewModelScope.launch {
                handleRelayEvent(event)
            }
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────

    fun hostGame(roomCode: String) {
        _gameState.value = ChessGameState.initial()
        outSeq = 0
        relay.connect(roomCode, "host")
    }

    fun joinGame(roomCode: String) {
        _gameState.value = ChessGameState.initial()
        outSeq = 0
        relay.connect(roomCode, "guest")
    }

    /**
     * Called by the UI when the local player taps a square.
     * Only processes the tap if it is this player's turn.
     */
    fun onTap(sq: Square) {
        val state = _gameState.value
        if (state.result != GameResult.Ongoing) return
        if (!isMyTurn(state)) return

        val tap = state.handleTap(sq)
        val newState = tap.newState

        // If the tap resulted in a completed move, send it to the peer
        if (tap.move != null) {
            relay.sendMove(tap.move.toUci(), ++outSeq)
        }

        _gameState.value = newState
        updateInGameTurn(newState)
    }

    fun leaveGame() {
        relay.disconnect()
        _gameState.value = ChessGameState.initial()
        _uiState.value = MultiplayerUiState.Idle
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private fun isMyTurn(state: ChessGameState): Boolean {
        return when (myRole) {
            "host" -> state.sideToMove == Side.WHITE
            "guest" -> state.sideToMove == Side.BLACK
            else -> false
        }
    }

    private fun handleRelayEvent(event: RelayEvent) {
        when (event) {
            is RelayEvent.PeerJoined -> {
                val gs = _gameState.value
                _uiState.value = MultiplayerUiState.InGame(
                    roomCode = (relay.connectionState.value as? ConnectionState.Connected)?.room ?: "",
                    role = myRole,
                    myTurn = isMyTurn(gs)
                )
                // Host sends initial state sync so the guest sees the same board
                if (myRole == "host") {
                    relay.sendStateSync(
                        fen = gs.toFen(),
                        sideToMove = if (gs.sideToMove == Side.WHITE) "w" else "b"
                    )
                }
            }
            is RelayEvent.PeerLeft -> {
                _uiState.value = MultiplayerUiState.PeerDisconnected(
                    "Your opponent disconnected."
                )
            }
            is RelayEvent.MoveReceived -> {
                val current = _gameState.value
                val next = current.applyUciMove(event.uci)
                if (next != null) {
                    _gameState.value = next
                    updateInGameTurn(next)
                }
            }
            is RelayEvent.StateSyncReceived -> {
                val synced = ChessGameState.fromFen(event.fen)
                if (synced != null) {
                    _gameState.value = synced
                    _uiState.update {
                        val roomCode = (relay.connectionState.value as? ConnectionState.Connected)?.room ?: ""
                        MultiplayerUiState.InGame(
                            roomCode = roomCode,
                            role = myRole,
                            myTurn = isMyTurn(synced)
                        )
                    }
                }
            }
            is RelayEvent.RemoteError -> {
                _uiState.value = MultiplayerUiState.Failure(event.message)
            }
        }
    }

    private fun updateInGameTurn(state: ChessGameState) {
        _uiState.update { prev ->
            if (prev is MultiplayerUiState.InGame) {
                prev.copy(myTurn = isMyTurn(state) && state.result == GameResult.Ongoing)
            } else prev
        }
    }

    override fun onCleared() {
        super.onCleared()
        relay.disconnect()
    }
}

