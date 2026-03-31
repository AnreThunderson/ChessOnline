package com.example.passandplaychess.multiplayer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.passandplaychess.*
import com.example.passandplaychess.R as AppR

// ── Entry point ───────────────────────────────────────────────────────────────

@Composable
fun MultiplayerScreen(
    onBack: () -> Unit,
    vm: MultiplayerViewModel = viewModel()
) {
    val uiState by vm.uiState.collectAsState()
    val gameState by vm.gameState.collectAsState()

    when (val s = uiState) {
        is MultiplayerUiState.Idle -> {
            LobbyScreen(
                onBack = onBack,
                onHost = { code -> vm.hostGame(code) },
                onJoin = { code -> vm.joinGame(code) }
            )
        }
        is MultiplayerUiState.Connecting -> {
            CenteredStatus("Connecting…")
        }
        is MultiplayerUiState.WaitingForPeer -> {
            WaitingScreen(
                roomCode = s.roomCode,
                role = s.role,
                onCancel = { vm.leaveGame(); onBack() }
            )
        }
        is MultiplayerUiState.InGame -> {
            OnlineGameScreen(
                gameState = gameState,
                role = s.role,
                roomCode = s.roomCode,
                myTurn = s.myTurn,
                onTap = { sq -> vm.onTap(sq) },
                onLeave = { vm.leaveGame(); onBack() }
            )
        }
        is MultiplayerUiState.PeerDisconnected -> {
            DisconnectedScreen(
                message = s.message,
                onBack = { vm.leaveGame(); onBack() }
            )
        }
        is MultiplayerUiState.Failure -> {
            ErrorScreen(
                message = s.message,
                onBack = { vm.leaveGame(); onBack() }
            )
        }
    }
}

// ── Lobby (Host / Join) ───────────────────────────────────────────────────────

@Composable
private fun LobbyScreen(
    onBack: () -> Unit,
    onHost: (String) -> Unit,
    onJoin: (String) -> Unit
) {
    var tab by remember { mutableIntStateOf(0) } // 0=Host, 1=Join

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Online Multiplayer", fontSize = 22.sp, fontWeight = FontWeight.Bold)

        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Host") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Join") })
        }

        if (tab == 0) {
            HostTab(onHost = onHost)
        } else {
            JoinTab(onJoin = onJoin)
        }

        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Back")
        }
    }
}

@Composable
private fun HostTab(onHost: (String) -> Unit) {
    // Generate a random 5-char room code once
    val roomCode = remember { generateRoomCode() }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Share this code with your opponent:", fontSize = 14.sp)
        Text(
            text = roomCode,
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 8.sp,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "You will play as White.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = { onHost(roomCode) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Create Room & Wait")
        }
    }
}

@Composable
private fun JoinTab(onJoin: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    val valid = code.uppercase().matches(Regex("[A-Z0-9]{4,6}"))

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.uppercase().take(6) },
            label = { Text("Room code") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                imeAction = ImeAction.Go
            ),
            keyboardActions = KeyboardActions(onGo = { if (valid) onJoin(code) }),
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "You will play as Black.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = { onJoin(code) },
            enabled = valid,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Join Room")
        }
    }
}

// ── Waiting screen ────────────────────────────────────────────────────────────

@Composable
private fun WaitingScreen(roomCode: String, role: String, onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(24.dp))
        Text("Room: $roomCode", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (role == "host") "Waiting for opponent to join…"
            else "Joining room, please wait…",
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
    }
}

// ── In-game screen ────────────────────────────────────────────────────────────

@Composable
private fun OnlineGameScreen(
    gameState: ChessGameState,
    role: String,
    roomCode: String,
    myTurn: Boolean,
    onTap: (Square) -> Unit,
    onLeave: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Status bar
        OnlineStatusBar(
            gameState = gameState,
            role = role,
            roomCode = roomCode,
            myTurn = myTurn
        )

        // Board — flip for black
        val flipBoard = role == "guest"
        OnlineChessBoard(
            state = gameState,
            flipped = flipBoard,
            onTap = onTap
        )

        // Footer
        val resultText = when (val r = gameState.result) {
            GameResult.Ongoing -> if (myTurn) "Your turn" else "Opponent's turn"
            is GameResult.Checkmate ->
                "Checkmate – ${if (r.winner == Side.WHITE) "White" else "Black"} wins"
            is GameResult.Stalemate -> "Draw: stalemate"
            is GameResult.DrawInsufficientMaterial -> "Draw: insufficient material"
            is GameResult.DrawFiftyMove -> "Draw: 50-move rule"
            is GameResult.DrawThreefold -> "Draw: threefold repetition"
        }
        Text(text = resultText, fontSize = 14.sp)

        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onLeave, modifier = Modifier.fillMaxWidth()) {
            Text("Leave Game")
        }
    }
}

@Composable
private fun OnlineStatusBar(
    gameState: ChessGameState,
    role: String,
    roomCode: String,
    myTurn: Boolean
) {
    val checkStatus = when (gameState.result) {
        GameResult.Ongoing -> if (gameState.isInCheck(gameState.sideToMove)) " – Check!" else ""
        else -> ""
    }
    val turnLabel = if (gameState.sideToMove == Side.WHITE) "White" else "Black"
    val colorLabel = if (role == "host") "White" else "Black"

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Room: $roomCode",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "You: $colorLabel",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Turn: $turnLabel$checkStatus",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = if (myTurn) "Your move" else "Waiting…",
            fontSize = 12.sp,
            color = if (myTurn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ── Board UI (online) ─────────────────────────────────────────────────────────

@Composable
private fun OnlineChessBoard(
    state: ChessGameState,
    flipped: Boolean,
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
        val ranks = if (!flipped) (7 downTo 0) else (0..7)
        val files = if (!flipped) (0..7) else (7 downTo 0)

        for (rank in ranks) {
            Row(modifier = Modifier.weight(1f)) {
                for (file in files) {
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
                        val resId = piece?.drawableResIdOrNullOnline()
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

private fun Piece.drawableResIdOrNullOnline(): Int? {
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

// ── Simple status / error screens ─────────────────────────────────────────────

@Composable
private fun CenteredStatus(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 18.sp)
    }
}

@Composable
private fun DisconnectedScreen(message: String, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Disconnected", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(message, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onBack) { Text("Back") }
    }
}

@Composable
private fun ErrorScreen(message: String, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Error", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Text(message, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onBack) { Text("Back") }
    }
}

private fun generateRoomCode(): String {
    val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    return (1..5).map { chars.random() }.joinToString("")
}
