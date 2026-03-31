package com.example.passandplaychess.multiplayer

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
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
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

private const val PREFS_NAME = "chessonline_prefs"
private const val KEY_DAILY_GAMES = "daily_games_v1"
private const val ONE_DAY_MS = 86_400_000L

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

class MultiplayerViewModel(app: Application) : AndroidViewModel(app) {

    private val relay = RelayClient(BuildConfig.WS_BASE_URL)

    private val _uiState = MutableStateFlow<MultiplayerUiState>(MultiplayerUiState.Idle)
    val uiState: StateFlow<MultiplayerUiState> = _uiState

    private val _gameState = MutableStateFlow(ChessGameState.initial())
    val gameState: StateFlow<ChessGameState> = _gameState

    private val _selectedMinutes = MutableStateFlow(5)
    val selectedMinutes: StateFlow<Int> = _selectedMinutes

    private val _initialTimeMs = MutableStateFlow(minutesToMs(_selectedMinutes.value))
    val initialTimeMs: StateFlow<Long> = _initialTimeMs

    private val _timeWhiteMs = MutableStateFlow(_initialTimeMs.value)
    val timeWhiteMs: StateFlow<Long> = _timeWhiteMs

    private val _timeBlackMs = MutableStateFlow(_initialTimeMs.value)
    val timeBlackMs: StateFlow<Long> = _timeBlackMs

    private val _turnDeadlineEpochMs = MutableStateFlow<Long?>(null)

    private var clockJob: Job? = null
    private var lastTick: Long? = null

    private var outSeq = 0
    private var myRole: String = "host"
    private var roomCode: String? = null

    init {
        viewModelScope.launch {
            relay.connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Connecting -> _uiState.value = MultiplayerUiState.Connecting
                    is ConnectionState.Connected -> {
                        myRole = state.role
                        roomCode = state.room
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
                        if (current is MultiplayerUiState.InGame || current is MultiplayerUiState.WaitingForPeer) {
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

    fun setTimeControlMinutes(minutes: Int) {
        val m = if (minutes == 1440) 1440 else minutes.coerceIn(1, 60)
        _selectedMinutes.value = m
        val ms = minutesToMs(m)
        _initialTimeMs.value = ms

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

            // Canonical commit after move
            relay.sendStateSync(
                fen = newState.toFen(),
                sideToMove = if (newState.sideToMove == Side.WHITE) "w" else "b",
                initialTimeMs = _initialTimeMs.value
            )
        }

        _gameState.value = newState
        updateInGameTurn(newState)
    }

    fun leaveGame() {
        relay.disconnect()
        stopClock()
        _gameState.value = ChessGameState.initial()
        _uiState.value = MultiplayerUiState.Idle

        val ms = _initialTimeMs.value
        _timeWhiteMs.value = ms
        _timeBlackMs.value = ms
        _turnDeadlineEpochMs.value = null
    }

    private fun isMyTurn(state: ChessGameState): Boolean {
        return when (myRole) {
            "host" -> state.sideToMove == Side.WHITE
            "guest" -> state.sideToMove == Side.BLACK
            else -> false
        }
    }

    private fun isDaily(): Boolean = _initialTimeMs.value == ONE_DAY_MS

    private fun handleRelayEvent(event: RelayEvent) {
        when (event) {
            is RelayEvent.PeerJoined -> {
                val gs = _gameState.value
                _uiState.value = MultiplayerUiState.InGame(
                    roomCode = roomCode ?: "",
                    role = myRole,
                    myTurn = isMyTurn(gs)
                )

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
                // In daily mode we should NOT treat this as fatal; the game continues offline.
                // We'll still show a message but keep the user in-game.
                val prev = _uiState.value
                if (prev is MultiplayerUiState.InGame && isDaily()) {
                    // stay in game
                } else {
                    _uiState.value = MultiplayerUiState.PeerDisconnected("Your opponent disconnected.")
                    stopClock()
                }
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
                val t = event.initialTimeMs
                if (t != null && t > 0) {
                    _initialTimeMs.value = t
                    if (!isDaily()) {
                        _timeWhiteMs.value = t
                        _timeBlackMs.value = t
                    }
                }

                _turnDeadlineEpochMs.value = event.turnDeadlineEpochMs

                val synced = ChessGameState.fromFen(event.fen)
                if (synced != null) {
                    _gameState.value = synced
                    _uiState.update { prev ->
                        val rc = roomCode ?: ""
                        when (prev) {
                            is MultiplayerUiState.InGame -> prev.copy(myTurn = isMyTurn(synced))
                            else -> MultiplayerUiState.InGame(
                                roomCode = rc,
                                role = myRole,
                                myTurn = isMyTurn(synced)
                            )
                        }
                    }

                    if (t == ONE_DAY_MS) {
                        saveOrUpdateDailyGame(
                            roomCode = roomCode ?: "",
                            role = myRole,
                            fen = event.fen,
                            sideToMove = event.sideToMove,
                            initialTimeMs = ONE_DAY_MS,
                            turnDeadlineEpochMs = event.turnDeadlineEpochMs
                        )
                    }

                    startClockIfNeeded()
                }
            }

            is RelayEvent.TimeForfeit -> {
                // Mark game over immediately in UI
                stopClock()
                val winnerSide = if (event.winner == "w") Side.WHITE else Side.BLACK
                _gameState.update { it.copy(result = GameResult.Checkmate(winner = winnerSide)) }
                updateInGameTurn(_gameState.value)
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
        _turnDeadlineEpochMs.value = null
    }

    private fun startClockIfNeeded() {
        if (clockJob != null) return
        if (_uiState.value !is MultiplayerUiState.InGame) return
        if (_gameState.value.result != GameResult.Ongoing) return

        clockJob = viewModelScope.launch {
            while (true) {
                delay(250)
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

        if (isDaily()) {
            val deadline = _turnDeadlineEpochMs.value ?: return
            val remaining = deadline - System.currentTimeMillis()
            when (gs.sideToMove) {
                Side.WHITE -> _timeWhiteMs.value = remaining.coerceAtLeast(0L)
                Side.BLACK -> _timeBlackMs.value = remaining.coerceAtLeast(0L)
            }
            if (remaining <= 0L) {
                onFlagFell(winner = if (gs.sideToMove == Side.WHITE) Side.BLACK else Side.WHITE)
            }
            return
        }

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

    // ── Local daily saved games (unchanged from earlier) ─────────────────────

    private fun loadDailyList(context: Context): MutableList<JSONObject> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_DAILY_GAMES, null) ?: return mutableListOf()
        return runCatching {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { i -> arr.getJSONObject(i) }
        }.getOrElse { mutableListOf() }
    }

    private fun saveDailyList(context: Context, list: List<JSONObject>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString(KEY_DAILY_GAMES, arr.toString()).apply()
    }

    private fun saveOrUpdateDailyGame(
        roomCode: String,
        role: String,
        fen: String,
        sideToMove: String,
        initialTimeMs: Long,
        turnDeadlineEpochMs: Long?
    ) {
        if (roomCode.isBlank()) return
        val ctx = getApplication<Application>().applicationContext
        val list = loadDailyList(ctx)

        val idx = list.indexOfFirst { it.optString("roomCode") == roomCode }
        val obj = JSONObject().apply {
            put("roomCode", roomCode)
            put("role", role)
            put("fen", fen)
            put("sideToMove", sideToMove)
            put("initialTimeMs", initialTimeMs)
            if (turnDeadlineEpochMs != null) put("turnDeadlineEpochMs", turnDeadlineEpochMs)
            put("updatedAtEpochMs", System.currentTimeMillis())
        }

        if (idx >= 0) list[idx] = obj else list.add(0, obj)
        saveDailyList(ctx, list)
    }

    private companion object {
        fun minutesToMs(minutes: Int): Long {
            return if (minutes == 1440) ONE_DAY_MS else minutes.toLong() * 60_000L
        }
    }
}
