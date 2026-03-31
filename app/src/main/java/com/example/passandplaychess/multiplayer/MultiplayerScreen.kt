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

private const val ONE_DAY_MINUTES = 1440

@Composable
fun MultiplayerScreen(
    onBack: () -> Unit,
    resumeRoomCode: String? = null,
    resumeRole: String? = null,
    vm: MultiplayerViewModel = viewModel()
) {
    val uiState by vm.uiState.collectAsState()
    val gameState by vm.gameState.collectAsState()

    // Auto-resume if coming from main menu saved daily game
    LaunchedEffect(resumeRoomCode, resumeRole) {
        if (!resumeRoomCode.isNullOrBlank() && (resumeRole == "host" || resumeRole == "guest")) {
            if (resumeRole == "host") vm.hostGame(resumeRoomCode)
            else vm.joinGame(resumeRoomCode)
        }
    }

    when (val s = uiState) {
        is MultiplayerUiState.Idle -> {
            LobbyScreen(
                onBack = onBack,
                vm = vm,
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
            val timeWhiteMs by vm.timeWhiteMs.collectAsState()
            val timeBlackMs by vm.timeBlackMs.collectAsState()

            OnlineGameScreen(
                gameState = gameState,
                role = s.role,
                roomCode = s.roomCode,
                myTurn = s.myTurn,
                timeWhiteMs = timeWhiteMs,
                timeBlackMs = timeBlackMs,
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
    vm: MultiplayerViewModel,
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
            HostTab(vm = vm, onHost = onHost)
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
private fun HostTab(
    vm: MultiplayerViewModel,
    onHost: (String) -> Unit
) {
    var step by remember { mutableIntStateOf(0) } // 0=choose time, 1=show code
    val selectedMinutes by vm.selectedMinutes.collectAsState()

    val roomCode = remember { generateRoomCode() }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        if (step == 0) {
            Text("Choose time control", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)

            TimeChoiceRow(
                selectedMinutes = selectedMinutes,
                onSelect = { vm.setTimeControlMinutes(it) }
            )

            val desc = if (selectedMinutes == ONE_DAY_MINUTES) {
                "Daily: each player has 1 day to make each move."
            } else {
                "Each player gets $selectedMinutes minute${if (selectedMinutes == 1) "" else "s"} total."
            }

            Text(
                text = desc,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Button(
                onClick = { step = 1 },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Next") }
        } else {
            Text("Share this code with your opponent:", fontSize = 14.sp)
            Text(
                text = roomCode,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 8.sp,
                color = MaterialTheme.colorScheme.primary
            )

            val timeLabel = if (selectedMinutes == ONE_DAY_MINUTES) "1 day" else "${selectedMinutes} min"
            Text(
                text = "Time: $timeLabel • You will play as White.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                onClick = { onHost(roomCode) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Create Room & Wait") }

            OutlinedButton(
                onClick = { step = 0 },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Change time") }
        }
    }
}

@Composable
private fun TimeChoiceRow(
    selectedMinutes: Int,
    onSelect: (Int) -> Unit
) {
    val opts = listOf(1, 3, 5, 10, ONE_DAY_MINUTES)
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        opts.forEach { m ->
            val selected = m == selectedMinutes
            val label = if (m == ONE_DAY_MINUTES) "1 day (per move)" else "$m minute${if (m == 1) "" else "s"}"
            Button(
                onClick = { onSelect(m) },
                modifier = Modifier.fillMaxWidth(),
                colors = if (selected) ButtonDefaults.buttonColors()
                else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) { Text(label) }
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
        ) { Text("Join Room") }
    }
}

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
            text = if (role == "host") "Waiting for opponent to join…" else "Joining room, please wait…",
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
    }
}

// ── In-game screen ───────────────────────────────────────────────────────────

@Composable
private fun OnlineGameScreen(
    gameState: ChessGameState,
    role: String,
    roomCode: String,
    myTurn: Boolean,
    timeWhiteMs: Long,
    timeBlackMs: Long,
    onTap: (Square) -> Unit,
    onLeave: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OnlineStatusBar(
            gameState = gameState,
            role = role,
            roomCode = roomCode,
            myTurn = myTurn,
            timeWhiteMs = timeWhiteMs,
            timeBlackMs = timeBlackMs
        )

        val flipBoard = role == "guest"
        OnlineChessBoard(
            state = gameState,
            flipped = flipBoard,
            onTap = onTap
        )

        val resultText = when (val r = gameState.result) {
            GameResult.Ongoing -> if (myTurn) "Your turn" else "Opponent's turn"
            is GameResult.Checkmate -> "Game over – ${if (r.winner == Side.WHITE) "White" else "Black"} wins"
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
    myTurn: Boolean,
    timeWhiteMs: Long,
    timeBlackMs: Long
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

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = "White: ${formatClock(timeWhiteMs)}",
                fontSize = 12.sp,
                fontWeight = if (gameState.sideToMove == Side.WHITE && gameState.result == GameResult.Ongoing) FontWeight.Bold else FontWeight.Normal
            )
            Text(
                text = "Black: ${formatClock(timeBlackMs)}",
                fontSize = 12.sp,
                fontWeight = if (gameState.sideToMove == Side.BLACK && gameState.result == GameResult.Ongoing) FontWeight.Bold else FontWeight.Normal
            )
        }

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

private fun formatClock(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L

    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
