package com.example.passandplaychess

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.passandplaychess.R as AppR
import com.example.passandplaychess.multiplayer.MultiplayerScreen
import com.example.passandplaychess.ui.theme.PassAndPlayChessTheme
import org.json.JSONArray
import org.json.JSONObject

private const val PREFS_NAME = "chessonline_prefs"
private const val KEY_LOCAL_GAME = "local_game_state_v1"

// Daily saved games
private const val KEY_DAILY_GAMES = "daily_games_v1"

private sealed class AppScreen {
    data object Menu : AppScreen()
    data object LocalGame : AppScreen()
    data class Multiplayer(val resume: DailyResumeParams? = null) : AppScreen()
}

data class DailyResumeParams(
    val roomCode: String,
    val role: String // "host" or "guest"
)

data class SavedDailyGame(
    val roomCode: String,
    val role: String,              // "host" or "guest"
    val fen: String,
    val sideToMove: String,         // "w" or "b"
    val initialTimeMs: Long,
    val turnDeadlineEpochMs: Long?,
    val updatedAtEpochMs: Long
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PassAndPlayChessTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    AppNavigation()
                }
            }
        }
    }
}

@Composable
private fun AppNavigation() {
    var screen by remember { mutableStateOf<AppScreen>(AppScreen.Menu) }

    when (val s = screen) {
        AppScreen.Menu -> MenuScreen(
            onLocalPlay = { screen = AppScreen.LocalGame },
            onMultiplayer = { screen = AppScreen.Multiplayer(null) },
            onOpenDaily = { params -> screen = AppScreen.Multiplayer(params) }
        )

        AppScreen.LocalGame -> ChessScreen(
            onBack = { screen = AppScreen.Menu }
        )

        is AppScreen.Multiplayer -> MultiplayerScreen(
            onBack = { screen = AppScreen.Menu },
            resumeRoomCode = s.resume?.roomCode,
            resumeRole = s.resume?.role
        )
    }
}

// ── Donation helper ──────────────────────────────────────────────────────────

private fun openDonationLink(context: Context) {
    val url = BuildConfig.DONATION_URL?.trim().orEmpty()
    if (url.isBlank() || url == "REPLACE_ME") {
        Toast.makeText(context, "Donation link is not configured.", Toast.LENGTH_LONG).show()
        return
    }

    val uri = runCatching { Uri.parse(url) }.getOrNull()
    if (uri == null || uri.scheme.isNullOrBlank()) {
        Toast.makeText(context, "Donation link is invalid.", Toast.LENGTH_LONG).show()
        return
    }

    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    if (intent.resolveActivity(context.packageManager) == null) {
        Toast.makeText(context, "No browser found to open donation link.", Toast.LENGTH_LONG).show()
        return
    }

    context.startActivity(intent)
}

// ── Daily persistence helpers ───────────────────────────────────────────────

private fun loadDailyGames(context: Context): List<SavedDailyGame> {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val raw = prefs.getString(KEY_DAILY_GAMES, null) ?: return emptyList()
    return runCatching {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    SavedDailyGame(
                        roomCode = o.getString("roomCode"),
                        role = o.getString("role"),
                        fen = o.getString("fen"),
                        sideToMove = o.getString("sideToMove"),
                        initialTimeMs = o.getLong("initialTimeMs"),
                        turnDeadlineEpochMs = if (o.has("turnDeadlineEpochMs") && !o.isNull("turnDeadlineEpochMs"))
                            o.getLong("turnDeadlineEpochMs") else null,
                        updatedAtEpochMs = o.getLong("updatedAtEpochMs")
                    )
                )
            }
        }
    }.getOrElse { emptyList() }
}

private fun saveDailyGames(context: Context, games: List<SavedDailyGame>) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val arr = JSONArray()
    games.forEach { g ->
        val o = JSONObject().apply {
            put("roomCode", g.roomCode)
            put("role", g.role)
            put("fen", g.fen)
            put("sideToMove", g.sideToMove)
            put("initialTimeMs", g.initialTimeMs)
            if (g.turnDeadlineEpochMs != null) put("turnDeadlineEpochMs", g.turnDeadlineEpochMs)
            put("updatedAtEpochMs", g.updatedAtEpochMs)
        }
        arr.put(o)
    }
    prefs.edit().putString(KEY_DAILY_GAMES, arr.toString()).apply()
}

private fun deleteDailyGame(context: Context, roomCode: String) {
    val list = loadDailyGames(context).filterNot { it.roomCode == roomCode }
    saveDailyGames(context, list)
}

// ── Main menu ───────────────────────────────────────────────────────────────

@Composable
private fun MenuScreen(
    onLocalPlay: () -> Unit,
    onMultiplayer: () -> Unit,
    onOpenDaily: (DailyResumeParams) -> Unit
) {
    val context = LocalContext.current
    var dailyGames by remember { mutableStateOf(loadDailyGames(context)) }

    LaunchedEffect(Unit) {
        dailyGames = loadDailyGames(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "ChessOnline",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        if (dailyGames.isNotEmpty()) {
            Text(
                text = "Saved daily games",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth()
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(dailyGames, key = { it.roomCode }) { g ->
                    DailyGameRow(
                        game = g,
                        onOpen = { onOpenDaily(DailyResumeParams(roomCode = g.roomCode, role = g.role)) },
                        onDelete = {
                            deleteDailyGame(context, g.roomCode)
                            dailyGames = loadDailyGames(context)
                        }
                    )
                }
            }
        } else {
            Spacer(Modifier.height(8.dp))
        }

        Button(onClick = onLocalPlay, modifier = Modifier.fillMaxWidth()) {
            Text("Local Play (pass and play)")
        }

        Button(onClick = onMultiplayer, modifier = Modifier.fillMaxWidth()) {
            Text("Online Multiplayer")
        }

        OutlinedButton(
            onClick = { openDonationLink(context) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Donate")
        }
    }
}

@Composable
private fun DailyGameRow(
    game: SavedDailyGame,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val remaining = remember(game.turnDeadlineEpochMs) {
        game.turnDeadlineEpochMs?.let { it - System.currentTimeMillis() }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DailyBoardPreview(
            fen = game.fen,
            modifier = Modifier
                .size(96.dp)
                .clickable { onOpen() }
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Room: ${game.roomCode}  (${if (game.role == "host") "White" else "Black"})",
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Turn: ${if (game.sideToMove == "w") "White" else "Black"}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val timeText = if (remaining == null) {
                "Daily: syncing…"
            } else {
                "Daily: ${formatDuration(remaining)} left"
            }
            Text(
                text = timeText,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpen) { Text("Open") }
                OutlinedButton(onClick = onDelete) { Text("Remove") }
            }
        }
    }
}

@Composable
private fun DailyBoardPreview(
    fen: String,
    modifier: Modifier = Modifier
) {
    val state = remember(fen) { ChessGameState.fromFen(fen) }
    if (state == null) {
        Box(modifier = modifier.border(1.dp, Color.Gray), contentAlignment = Alignment.Center) {
            Text("?", fontSize = 18.sp)
        }
        return
    }

    val light = Color(0xFFEEEED2)
    val dark = Color(0xFF769656)

    Column(
        modifier = modifier
            .border(1.dp, Color.Black)
    ) {
        for (rank in 7 downTo 0) {
            Row(modifier = Modifier.weight(1f)) {
                for (file in 0..7) {
                    val sq = Square(file, rank)
                    val piece = state.board.pieceAt(sq)
                    val isLight = (file + rank) % 2 == 1
                    val bg = if (isLight) light else dark

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(bg),
                        contentAlignment = Alignment.Center
                    ) {
                        val resId = piece?.drawableResIdOrNull()
                        if (resId != null) {
                            Image(
                                painter = painterResource(id = resId),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(0.80f)
                            )
                        } else {
                            Text(
                                text = piece?.toUnicode() ?: "",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.SansSerif
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val clamped = ms.coerceAtLeast(0L)
    val totalSeconds = clamped / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

// ── Local game save (existing) ───────────────────────────────────────────────

private fun loadLocalGame(context: Context): ChessGameState? {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val raw = prefs.getString(KEY_LOCAL_GAME, null) ?: return null
    return ChessGameState.fromPersistedString(raw)
}

private fun saveLocalGame(context: Context, state: ChessGameState) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit().putString(KEY_LOCAL_GAME, state.toPersistedString()).apply()
}

private fun clearLocalGame(context: Context) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit().remove(KEY_LOCAL_GAME).apply()
}

@Composable
private fun ChessScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var state by remember { mutableStateOf(ChessGameState.initial()) }
    var loadedOnce by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!loadedOnce) {
            state = loadLocalGame(context) ?: ChessGameState.initial()
            loadedOnce = true
        }
    }

    LaunchedEffect(loadedOnce, state) {
        if (loadedOnce) saveLocalGame(context, state)
    }

    DisposableEffect(lifecycleOwner, loadedOnce) {
        if (!loadedOnce) return@DisposableEffect onDispose { }

        val obs = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                saveLocalGame(context, state)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Header(
            state = state,
            onNewGame = {
                state = ChessGameState.initial()
                saveLocalGame(context, state)
            },
            onClearSelection = { state = state.copy(selected = null, legalTargets = emptySet(), lastMessage = "") },
            onBack = {
                saveLocalGame(context, state)
                onBack()
            }
        )

        ChessBoard(
            state = state,
            onTap = { sq ->
                val res = state.handleTap(sq)
                state = res.newState
            }
        )

        Footer(state = state)

        OutlinedButton(
            onClick = {
                clearLocalGame(context)
                state = ChessGameState.initial()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Reset saved local game")
        }
    }
}

@Composable
private fun Header(
    state: ChessGameState,
    onNewGame: () -> Unit,
    onClearSelection: () -> Unit,
    onBack: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val status = when (state.result) {
            GameResult.Ongoing -> {
                val inCheck = state.isInCheck(state.sideToMove)
                if (inCheck) "Check" else " "
            }

            is GameResult.Checkmate -> "Checkmate"
            is GameResult.Stalemate -> "Stalemate"
            is GameResult.DrawInsufficientMaterial -> "Draw (insufficient material)"
            is GameResult.DrawFiftyMove -> "Draw (50-move rule)"
            is GameResult.DrawThreefold -> "Draw (threefold repetition)"
        }

        Text(
            text = "Turn: ${if (state.sideToMove == Side.WHITE) "White" else "Black"}  $status",
            fontSize = 18.sp
        )

        if (state.lastMessage.isNotBlank()) {
            Text(text = state.lastMessage, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onNewGame) { Text("New game") }
            Button(onClick = onClearSelection) { Text("Clear selection") }
            OutlinedButton(onClick = onBack) { Text("Menu") }
        }
    }
}

@Composable
private fun Footer(state: ChessGameState) {
    val resultText = when (val r = state.result) {
        GameResult.Ongoing -> "Game in progress"
        is GameResult.Checkmate -> "Winner: ${if (r.winner == Side.WHITE) "White" else "Black"}"
        is GameResult.Stalemate -> "Draw: stalemate"
        is GameResult.DrawInsufficientMaterial -> "Draw: insufficient material"
        is GameResult.DrawFiftyMove -> "Draw: 50-move rule"
        is GameResult.DrawThreefold -> "Draw: threefold repetition"
    }

    Text(text = resultText, fontSize = 16.sp)
}

private fun Piece.drawableResIdOrNull(): Int? {
    return when (this.toUnicode()) {
        "♔" -> AppR.drawable.chess_klt45
        "♕" -> AppR.drawable.chess_qlt45
        "♖" -> AppR.drawable.chess_rlt45
        "♗" -> AppR.drawable.chess_blt45
        "♘" -> AppR.drawable.chess_nlt45
        "♙" -> AppR.drawable.chess_plt45
        "♚" -> AppR.drawable.chess_kdt45
        "♛" -> AppR.drawable.chess_qdt45
        "♜" -> AppR.drawable.chess_rdt45
        "♝" -> AppR.drawable.chess_bdt45
        "♞" -> AppR.drawable.chess_ndt45
        "♟" -> AppR.drawable.chess_pdt45
        else -> null
    }
}

@Composable
private fun ChessBoard(
    state: ChessGameState,
    onTap: (Square) -> Unit
) {
    val light = Color(0xFFEEEED2)
    val dark = Color(0xFF769656)
    val selectedColor = Color(0xFFBACA44)
    val targetColor = Color(0xFFDAC36A)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .border(2.dp, Color.Black)
    ) {
        for (rank in 7 downTo 0) {
            Row(modifier = Modifier.weight(1f)) {
                for (file in 0..7) {
                    val sq = Square(file, rank)
                    val piece = state.board.pieceAt(sq)
                    val isLight = (file + rank) % 2 == 1
                    val isSelected = state.selected == sq
                    val isTarget = state.legalTargets.contains(sq)

                    val bg = when {
                        isSelected -> selectedColor
                        isTarget -> targetColor
                        else -> if (isLight) light else dark
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(bg)
                            .clickable(enabled = state.result == GameResult.Ongoing) { onTap(sq) },
                        contentAlignment = Alignment.Center
                    ) {
                        val resId = piece?.drawableResIdOrNull()
                        if (resId != null) {
                            Image(
                                painter = painterResource(id = resId),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(0.85f)
                            )
                        } else {
                            Text(
                                text = piece?.toUnicode() ?: "",
                                fontSize = 28.sp,
                                fontFamily = FontFamily.SansSerif
                            )
                        }
                    }
                }
            }
        }
    }
}
