package com.example.passandplaychess.multiplayer

import android.os.SystemClock
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

sealed class MultiplayerUiState {
    data object Idle : MultiplayerUiState()
    data object Connecting : MultiplayerUiState()
    data class WaitingForPeer(val roomCode: String, val role: String) : MultiplayerUiState()
    data class InGame(
        val roomCode: String,
        val role: String,
        val myTurn: Boolean
    ) : MultiplayerUiState()

    data class PeerDisconnected(val message: String) : MultiplayerUiState()
    data class Failure(val message: String) : MultiplayerUiState()
}

class MultiplayerViewModel : ViewModel() {

    private val relay = RelayClient(BuildConfig.WS_BASE_URL)

    private val _uiState = MutableStateFlow<MultiplayerUiState>(MultiplayerUiState.Idle)
    val uiState: StateFlow<MultiplayerUiState> = _uiState

    private val _gameState = MutableStateFlow(ChessGameState.initial())
    val gameState: StateFlow<ChessGameState> = _gameState

    // ── Time control / clocks ──────────────────────────────────────────────

    private val _selectedMinutes = MutableStateFlow(5) // host chooses before creating room
    val selectedMinutes: StateFlow<Int> = _selectedMinutes

    private val _initialTimeMs = MutableStateFlow(minutesToMs(_selectedMinutes.value))
    val initialTimeMs: StateFlow<Long> = _initialTimeMs

    private val _timeWhiteMs = MutableStateFlow(_initialTimeMs.value)
    val timeWhiteMs: StateFlow<Long> = _timeWhiteMs

    private val _timeBlackMs = MutableStateFlow(_initialTimeMs.value)
    val timeBlackMs: StateFlow<Long> = _timeBlackMs

    private var clockJob: Job? = null
    private var lastTick: Long? = null

    /** Running sequence number for outbound moves. */
    private var outSeq = 0

    /** The role assigned after a successful hello/welcome exchange. */
    private var myRole: String = "host"

    init {
        viewModelScope.launch {
            relay.connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Connecting -> _uiState.value = MultiplayerUiState.Connecting
                    is ConnectionState.Connected -> {
                        myRole = state.role
                        _uiState.value = MultiplayerUiState.WaitingForPeer(
                            roomCode = state.room,
                            role = state.role
                        )
                    }
                    is ConnectionState.Error -> {
                        _uiState.value = MultiplayerUiState.Failure(state.message)
                        stopClock()
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

        relay.onEvent = { event ->
            viewModelScope.launch { handleRelayEvent(event) }
        }
    }

    // Host-only (UI calls this before hostGame)
    fun setTimeControlMinutes(minutes: Int) {
        val m = minutes.coerceIn(1, 60)
        _selectedMinutes.value = m
        val ms = minutesToMs(m)
        _initialTimeMs.value = ms

        // If we're not already in a match, reflect choice in the displayed clocks
        if (_uiState.value !is MultiplayerUiState.InGame &&
            _uiState.value !is MultiplayerUiState.WaitingForPeer
        ) {
            _timeWhiteMs.value = ms
            _timeBlackMs.value = ms
        }
    }

    fun hostGame(roomCode: String) {
        resetGameAndClocks()
        outSeq = 0
        relay.connect(roomCode, "host")
    }

    fun joinGame(roomCode: String) {
        // guest doesn't pick the clock; it will be synced by host via state_sync
        resetGameAndClocks()
        outSeq = 0
        relay.connect(roomCode, "guest")
    }

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
    }

    fun leaveGame() {
        relay.disconnect()
        stopClock()
        _gameState.value = ChessGameState.initial()
        _uiState.value = MultiplayerUiState.Idle

        // reset clocks to selected time for convenience
        val ms = _initialTimeMs.value
        _timeWhiteMs.value = ms
        _timeBlackMs.value = ms
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

                // Host sends initial state + time control
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
                _uiState.value = MultiplayerUiState.PeerDisconnected("Your opponent disconnected.")
                stopClock()
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
                // Guest learns the time control here
                val t = event.initialTimeMs
                if (t != null && t > 0) {
                    _initialTimeMs.value = t
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
        if (state.result != GameResult.Ongoing) stopClock()
    }

    private fun resetGameAndClocks() {
        stopClock()
        _gameState.value = ChessGameState.initial()
        val ms = _initialTimeMs.value
        _timeWhiteMs.value = ms
        _timeBlackMs.value = ms
        lastTick = null
    }

    private fun startClockIfNeeded() {
        if (clockJob != null) return
        if (_uiState.value !is MultiplayerUiState.InGame) return
        if (_gameState.value.result != GameResult.Ongoing) return

        clockJob = viewModelScope.launch {
            while (true) {
                delay(100)
                tickClock()
            }
        }
    }

    private fun stopClock() {
        clockJob?.cancel()
        clockJob = null
        lastTick = null
    }

    private fun tickClock() {
        val ui = _uiState.value
        val gs = _gameState.value
        if (ui !is MultiplayerUiState.InGame) return
        if (gs.result != GameResult.Ongoing) return

        val now = SystemClock.elapsedRealtime()
        val prev = lastTick
        lastTick = now
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
        _gameState.update { it.copy(result = GameResult.Checkmate(winner = winner)) }
        updateInGameTurn(_gameState.value)
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
