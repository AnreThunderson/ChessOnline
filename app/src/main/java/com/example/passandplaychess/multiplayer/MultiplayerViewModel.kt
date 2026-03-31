package com.example.passandplaychess.multiplayer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.passandplaychess.BuildConfig
import com.example.passandplaychess.ChessGameState
import com.example.passandplaychess.GameResult
import com.example.passandplaychess.Side
import com.example.passandplaychess.Square
import com.example.passandplaychess.toUci
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.max

// ── UI state ──────────────────���───────────────────────────────────────────────

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

    // ── Clock state ───────────────────────────────────────────────────────────

    // Chosen before connecting (host chooses; guest just uses whatever host sends)
    private val _selectedMinutes = MutableStateFlow(5) // default 5 min
    val selectedMinutes: StateFlow<Int> = _selectedMinutes

    private val _initialTimeMs = MutableStateFlow(minutesToMs(_selectedMinutes.value))
    val initialTimeMs: StateFlow<Long> = _initialTimeMs

    private val _timeWhiteMs = MutableStateFlow(_initialTimeMs.value)
    val timeWhiteMs: StateFlow<Long> = _timeWhiteMs

    private val _timeBlackMs = MutableStateFlow(_initialTimeMs.value)
    val timeBlackMs: StateFlow<Long> = _timeBlackMs

    private var clockJob: Job? = null
    private var lastTickElapsedRealtime: Long? = null

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
                        val current = _uiState.value
                        if (current is MultiplayerUiState.InGame ||
                            current is MultiplayerUiState.WaitingForPeer
                        ) {
                            _uiState.value = MultiplayerUiState.Idle
                        }
                        stopClock()
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

    fun setTimeControlMinutes(minutes: Int) {
        val m = minutes.coerceIn(1, 60)
        _selectedMinutes.value = m
        val ms = minutesToMs(m)
        _initialTimeMs.value = ms
        // If not in a running game, also reset displayed times
        if (_uiState.value !is MultiplayerUiState.InGame && _uiState.value !is MultiplayerUiState.WaitingForPeer) {
            _timeWhiteMs.value = ms
            _timeBlackMs.value = ms
        }
    }

    fun hostGame(roomCode: String) {
        resetGameAndClocksForNewMatch()
        outSeq = 0
        relay.connect(roomCode, "host")
    }

    fun joinGame(roomCode: String) {
        resetGameAndClocksForNewMatch()
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

        if (tap.move != null) {
            relay.sendMove(tap.move.toUci(), ++outSeq)
        }

        _gameState.value = newState
        updateInGameTurn(newState)
        // turn switches => next tick will subtract from the other side automatically
    }

    fun leaveGame() {
        relay.disconnect()
        stopClock()
        _gameState.value = ChessGameState.initial()
        _uiState.value = MultiplayerUiState.Idle
        // keep selected minutes, but reset times to initial for convenience
        _timeWhiteMs.value = _initialTimeMs.value
        _timeBlackMs.value = _initialTimeMs.value
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private fun resetGameAndClocksForNewMatch() {
        stopClock()
        _gameState.value = ChessGameState.initial()

        val ms = _initialTimeMs.value
        _timeWhiteMs.value = ms
        _timeBlackMs.value = ms
        lastTickElapsedRealtime = null
    }

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

                // Host sends initial state sync so the guest sees the same board + time control
                if (myRole == "host") {
                    relay.sendStateSync(
                        fen = gs.toFen(),
                        sideToMove = if (gs.sideToMove == Side.WHITE) "w" else "b",
                        initialTimeMs = _initialTimeMs.value
                    )
                }

                startClockIfNeeded()
            }

            is RelayEvent.PeerLeft -> {
                _uiState.value = MultiplayerUiState.PeerDisconnected(
                    "Your opponent disconnected."
                )
                stopClock()
            }

            is RelayEvent.MoveReceived -> {
                val current = _gameState.value
                val next = current.applyUciMove(event.uci)
                if (next != null) {
                    _gameState.value = next
                    updateInGameTurn(next)
                    // turn switches => next tick subtracts from correct side
                }
            }

            is RelayEvent.StateSyncReceived -> {
                // If host included a time control, adopt it (guest side)
                val t = event.initialTimeMs
                if (t != null && t > 0) {
                    _initialTimeMs.value = t
                    // Reset both clocks on first sync only (i.e., when still at initial values)
                    _timeWhiteMs.value = t
                    _timeBlackMs.value = t
                }

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
                    startClockIfNeeded()
                }
            }

            is RelayEvent.RemoteError -> {
                _uiState.value = MultiplayerUiState.Failure(event.message)
                stopClock()
            }
        }
    }

    private fun updateInGameTurn(state: ChessGameState) {
        _uiState.update { prev ->
            if (prev is MultiplayerUiState.InGame) {
                prev.copy(myTurn = isMyTurn(state) && state.result == GameResult.Ongoing)
            } else prev
        }

        if (state.result != GameResult.Ongoing) {
            stopClock()
        }
    }

    private fun startClockIfNeeded() {
        if (clockJob != null) return
        if (_uiState.value !is MultiplayerUiState.InGame) return
        if (_gameState.value.result != GameResult.Ongoing) return

        clockJob = viewModelScope.launch {
            // tick ~10 times/sec (smooth enough; cheap)
            while (true) {
                delay(100)
                tickClock()
            }
        }
    }

    private fun stopClock() {
        clockJob?.cancel()
        clockJob = null
        lastTickElapsedRealtime = null
    }

    private fun tickClock() {
        val ui = _uiState.value
        val gs = _gameState.value
        if (ui !is MultiplayerUiState.InGame) return
        if (gs.result != GameResult.Ongoing) return

        val now = android.os.SystemClock.elapsedRealtime()
        val prev = lastTickElapsedRealtime
        lastTickElapsedRealtime = now
        if (prev == null) return

        val delta = max(0L, now - prev)

        when (gs.sideToMove) {
            Side.WHITE -> {
                val next = max(0L, _timeWhiteMs.value - delta)
                _timeWhiteMs.value = next
                if (next == 0L) onFlagFell(winner = Side.BLACK)
            }
            Side.BLACK -> {
                val next = max(0L, _timeBlackMs.value - delta)
                _timeBlackMs.value = next
                if (next == 0L) onFlagFell(winner = Side.WHITE)
            }
        }
    }

    private fun onFlagFell(winner: Side) {
        stopClock()
        // Treat as checkmate-style terminal result for UI purposes.
        // (If you want a dedicated "timeout" result type later, we can add it.)
        _gameState.update { it.copy(result = GameResult.Checkmate(winner = winner)) }
        updateInGameTurn(_gameState.value)
        // Optional: you could also tell peer via a new relay message type, but both sides will
        // reach timeout independently (clocks are kept in sync by turn + time control).
    }

    override fun onCleared() {
        super.onCleared()
        relay.disconnect()
        stopClock()
    }

    private companion object {
        fun minutesToMs(minutes: Int): Long = minutes.toLong() * 60_000L
    }
}
