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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.passandplaychess.ui.theme.PassAndPlayChessTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PassAndPlayChessTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    ChessScreen()
                }
            }
        }
    }
}

@Composable
private fun ChessScreen() {
    var state by remember { mutableStateOf(ChessGameState.initial()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Header(
            state = state,
            onNewGame = { state = ChessGameState.initial() },
            onClearSelection = { state = state.copy(selected = null, legalTargets = emptySet(), lastMessage = "") }
        )

        ChessBoard(
            state = state,
            onTap = { sq ->
                val res = state.handleTap(sq)
                state = res.newState
            }
        )

        Footer(state = state)
    }
}

@Composable
private fun Header(
    state: ChessGameState,
    onNewGame: () -> Unit,
    onClearSelection: () -> Unit
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
    // We map using your existing Unicode piece characters.
    return when (this.toUnicode()) {
        // White
        "♔" -> R.drawable.chess_klt45
        "♕" -> R.drawable.chess_qlt45
        "♖" -> R.drawable.chess_rlt45
        "♗" -> R.drawable.chess_blt45
        "♘" -> R.drawable.chess_nlt45
        "♙" -> R.drawable.chess_plt45

        // Black
        "♚" -> R.drawable.chess_kdt45
        "♛" -> R.drawable.chess_qdt45
        "♜" -> R.drawable.chess_rdt45
        "♝" -> R.drawable.chess_bdt45
        "♞" -> R.drawable.chess_ndt45
        "♟" -> R.drawable.chess_pdt45

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