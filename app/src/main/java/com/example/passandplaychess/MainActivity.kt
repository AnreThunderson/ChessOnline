package com.example.passandplaychess

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.passandplaychess.R as AppR
import com.example.passandplaychess.multiplayer.MultiplayerScreen
import com.example.passandplaychess.ui.theme.PassAndPlayChessTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── Screen navigation ─────────────────────────────────────────────────────────

private sealed class AppScreen {
    data object Menu : AppScreen()
    data object LocalGame : AppScreen()
    data object Multiplayer : AppScreen()
}

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

    when (screen) {
        AppScreen.Menu -> MenuScreen(
            onLocalPlay = { screen = AppScreen.LocalGame },
            onMultiplayer = { screen = AppScreen.Multiplayer }
        )
        AppScreen.LocalGame -> ChessScreen(
            onBack = { screen = AppScreen.Menu }
        )
        AppScreen.Multiplayer -> MultiplayerScreen(
            onBack = { screen = AppScreen.Menu }
        )
    }
}

// ── Main menu ─────────────────────────────────────────────────────────────────

@Composable
private fun MenuScreen(
    onLocalPlay: () -> Unit,
    onMultiplayer: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "ChessOnline",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(48.dp))
        Button(
            onClick = onLocalPlay,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Local Play (vs friend / vs bot)")
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onMultiplayer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Online Multiplayer")
        }
    }
}

@Composable
private fun ChessScreen(onBack: () -> Unit) {
    var state by remember { mutableStateOf(ChessGameState.initial()) }

    // Bot configuration UI state
    var vsBot by remember { mutableStateOf(false) }
    var botSide by remember { mutableStateOf(Side.BLACK) }
    var difficulty by remember { mutableIntStateOf(2) } // depth 1..4

    val scope = rememberCoroutineScope()

    fun maybeMakeBotMove() {
        if (!vsBot) return
        if (state.result != GameResult.Ongoing) return
        if (state.sideToMove != botSide) return

        val cfg = BotConfig(enabled = true, botSide = botSide, depth = difficulty)

        scope.launch(Dispatchers.Default) {
            // Small delay feels more natural
            delay(250)
            val uci = ChessBot.chooseMoveUci(state, cfg) ?: return@launch
            val next = state.applyUciMove(uci) ?: return@launch

            launch(Dispatchers.Main) {
                state = next
            }
        }
    }

    // If user changes bot settings mid-game, allow bot to move if it's its turn.
    LaunchedEffect(vsBot, botSide, difficulty, state.sideToMove, state.result) {
        maybeMakeBotMove()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Header(
            state = state,
            onNewGame = { state = ChessGameState.initial() },
            onClearSelection = { state = state.copy(selected = null, legalTargets = emptySet(), lastMessage = "") },
            onBack = onBack
        )

        BotControls(
            vsBot = vsBot,
            onVsBotChange = { enabled ->
                vsBot = enabled
                maybeMakeBotMove()
            },
            botSide = botSide,
            onBotSideChange = {
                botSide = it
                maybeMakeBotMove()
            },
            difficulty = difficulty,
            onDifficultyChange = {
                difficulty = it
                maybeMakeBotMove()
            }
        )

        ChessBoard(
            state = state,
            onTap = { sq ->
                // If it's bot's turn, ignore taps
                if (vsBot && state.sideToMove == botSide) return@ChessBoard

                val res = state.handleTap(sq)
                state = res.newState

                // After human moves, let bot respond
                maybeMakeBotMove()
            }
        )

        Footer(state = state)
    }
}

@Composable
private fun BotControls(
    vsBot: Boolean,
    onVsBotChange: (Boolean) -> Unit,
    botSide: Side,
    onBotSideChange: (Side) -> Unit,
    difficulty: Int,
    onDifficultyChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Play vs Bot", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Switch(checked = vsBot, onCheckedChange = onVsBotChange)
        }

        if (!vsBot) return

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Bot plays:", modifier = Modifier.width(80.dp))
            Button(
                onClick = { onBotSideChange(Side.WHITE) },
                enabled = botSide != Side.WHITE
            ) { Text("White") }
            Button(
                onClick = { onBotSideChange(Side.BLACK) },
                enabled = botSide != Side.BLACK
            ) { Text("Black") }
        }

        Column {
            Text("Difficulty: $difficulty", fontWeight = FontWeight.SemiBold)
            Slider(
                value = difficulty.toFloat(),
                onValueChange = { onDifficultyChange(it.toInt().coerceIn(1, 4)) },
                valueRange = 1f..4f,
                steps = 2
            )
            Text(
                "1 = easy, 4 = harder (slower on older phones)",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
        // White
        "♔" -> AppR.drawable.chess_klt45
        "♕" -> AppR.drawable.chess_qlt45
        "♖" -> AppR.drawable.chess_rlt45
        "♗" -> AppR.drawable.chess_blt45
        "♘" -> AppR.drawable.chess_nlt45
        "♙" -> AppR.drawable.chess_plt45

        // Black
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
                    val isLight = (file + rank) % 2 == 0
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
