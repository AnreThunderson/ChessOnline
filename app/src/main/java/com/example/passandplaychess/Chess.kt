package com.example.passandplaychess

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

// ── Bot (offline AI) ──────────────────────────────────────────────────────────

data class BotConfig(
    val enabled: Boolean,
    val botSide: Side,
    /** Search depth in plies (half-moves). 1–4 recommended for phones. */
    val depth: Int
)

object ChessBot {

    /** Returns a UCI move string (e.g. "e2e4") or null if no legal move exists. */
    fun chooseMoveUci(
        state: ChessGameState,
        config: BotConfig,
        rng: Random = Random.Default
    ): String? {
        if (!config.enabled) return null
        if (state.result != GameResult.Ongoing) return null
        if (state.sideToMove != config.botSide) return null

        val moves = state.allLegalMoves()
        if (moves.isEmpty()) return null

        val depth = config.depth.coerceIn(1, 5)

        // Score from bot's perspective: maximize when bot to move, minimize otherwise.
        val maximizing = (state.sideToMove == config.botSide)

        // Evaluate all moves; keep a small set of best moves to add variety.
        var bestScore = if (maximizing) Int.MIN_VALUE else Int.MAX_VALUE
        val bestMoves = mutableListOf<Move>()

        for (mv in moves) {
            val next = state.applyMoveForAnalysis(mv) ?: continue
            val score = minimax(
                state = next,
                depth = depth - 1,
                alpha = Int.MIN_VALUE,
                beta = Int.MAX_VALUE,
                botSide = config.botSide
            )

            if (maximizing) {
                if (score > bestScore) {
                    bestScore = score
                    bestMoves.clear()
                    bestMoves.add(mv)
                } else if (score == bestScore) {
                    bestMoves.add(mv)
                }
            } else {
                if (score < bestScore) {
                    bestScore = score
                    bestMoves.clear()
                    bestMoves.add(mv)
                } else if (score == bestScore) {
                    bestMoves.add(mv)
                }
            }
        }

        val chosen =
            if (bestMoves.isNotEmpty()) bestMoves[rng.nextInt(bestMoves.size)]
            else moves[rng.nextInt(moves.size)]

        return chosen.toUci()
    }

    private fun minimax(
        state: ChessGameState,
        depth: Int,
        alpha: Int,
        beta: Int,
        botSide: Side
    ): Int {
        if (depth <= 0 || state.result != GameResult.Ongoing) {
            return evaluate(state, botSide)
        }

        val moves = state.allLegalMoves()
        if (moves.isEmpty()) return evaluate(state, botSide)

        var a = alpha
        var b = beta

        val maximizing = state.sideToMove == botSide

        if (maximizing) {
            var best = Int.MIN_VALUE
            for (mv in moves) {
                val next = state.applyMoveForAnalysis(mv) ?: continue
                val score = minimax(next, depth - 1, a, b, botSide)
                best = max(best, score)
                a = max(a, best)
                if (a >= b) break // beta cut
            }
            return best
        } else {
            var best = Int.MAX_VALUE
            for (mv in moves) {
                val next = state.applyMoveForAnalysis(mv) ?: continue
                val score = minimax(next, depth - 1, a, b, botSide)
                best = min(best, score)
                b = min(b, best)
                if (a >= b) break // alpha cut
            }
            return best
        }
    }

    /**
     * Simple evaluation:
     * - material (dominant)
     * - small mobility bonus
     * - terminal states heavily weighted
     */
    private fun evaluate(state: ChessGameState, botSide: Side): Int {
        when (val r = state.result) {
            GameResult.Ongoing -> { /* continue */ }
            is GameResult.Checkmate -> {
                // winner gets big score from bot perspective
                return if (r.winner == botSide) 1_000_000 else -1_000_000
            }
            else -> return 0 // draws
        }

        var score = 0

        for ((_, p) in state.board.allPieces()) {
            val v = pieceValue(p.type)
            score += if (p.side == botSide) v else -v
        }

        // Mobility (tiny): number of legal moves for side to move
        val mobility = state.allLegalMoves().size
        score += if (state.sideToMove == botSide) mobility else -mobility

        // In-check penalty (tiny)
        if (state.isInCheck(botSide)) score -= 10
        if (state.isInCheck(botSide.opposite())) score += 10

        return score
    }

    private fun pieceValue(type: PieceType): Int = when (type) {
        PieceType.PAWN -> 100
        PieceType.KNIGHT -> 320
        PieceType.BISHOP -> 330
        PieceType.ROOK -> 500
        PieceType.QUEEN -> 900
        PieceType.KING -> 0
    }
}

// ── Model ────────────────────────────────────────────────────────────────────��

enum class Side {
    WHITE, BLACK;

    fun opposite(): Side = if (this == WHITE) BLACK else WHITE
}

enum class PieceType { KING, QUEEN, ROOK, BISHOP, KNIGHT, PAWN }

data class Piece(val side: Side, val type: PieceType) {
    fun toUnicode(): String = when (side) {
        Side.WHITE -> when (type) {
            PieceType.KING -> "♔"
            PieceType.QUEEN -> "♕"
            PieceType.ROOK -> "♖"
            PieceType.BISHOP -> "♗"
            PieceType.KNIGHT -> "♘"
            PieceType.PAWN -> "♙"
        }

        Side.BLACK -> when (type) {
            PieceType.KING -> "♚"
            PieceType.QUEEN -> "♛"
            PieceType.ROOK -> "♜"
            PieceType.BISHOP -> "♝"
            PieceType.KNIGHT -> "♞"
            PieceType.PAWN -> "♟"
        }
    }
}

data class Square(val file: Int, val rank: Int) {
    init {
        require(file in 0..7 && rank in 0..7)
    }

    override fun toString(): String = "${('a' + file)}${rank + 1}"
}

data class CastlingRights(
    val wk: Boolean = true,
    val wq: Boolean = true,
    val bk: Boolean = true,
    val bq: Boolean = true
)

sealed class Move {
    abstract val from: Square
    abstract val to: Square

    data class Normal(
        override val from: Square,
        override val to: Square,
        val promotion: PieceType? = null
    ) : Move()

    data class Castle(
        override val from: Square,
        override val to: Square,
        val rookFrom: Square,
        val rookTo: Square
    ) : Move()

    data class EnPassant(
        override val from: Square,
        override val to: Square,
        val capturedPawnSquare: Square
    ) : Move()
}

data class Board(private val cells: List<Piece?>) {
    companion object {
        fun initial(): Board {
            fun p(side: Side, type: PieceType) = Piece(side, type)
            val arr = Array<Piece?>(64) { null }

            // White pieces
            arr[idx(0, 0)] = p(Side.WHITE, PieceType.ROOK)
            arr[idx(1, 0)] = p(Side.WHITE, PieceType.KNIGHT)
            arr[idx(2, 0)] = p(Side.WHITE, PieceType.BISHOP)
            arr[idx(3, 0)] = p(Side.WHITE, PieceType.QUEEN)
            arr[idx(4, 0)] = p(Side.WHITE, PieceType.KING)
            arr[idx(5, 0)] = p(Side.WHITE, PieceType.BISHOP)
            arr[idx(6, 0)] = p(Side.WHITE, PieceType.KNIGHT)
            arr[idx(7, 0)] = p(Side.WHITE, PieceType.ROOK)
            for (f in 0..7) arr[idx(f, 1)] = p(Side.WHITE, PieceType.PAWN)

            // Black pieces
            arr[idx(0, 7)] = p(Side.BLACK, PieceType.ROOK)
            arr[idx(1, 7)] = p(Side.BLACK, PieceType.KNIGHT)
            arr[idx(2, 7)] = p(Side.BLACK, PieceType.BISHOP)
            arr[idx(3, 7)] = p(Side.BLACK, PieceType.QUEEN)
            arr[idx(4, 7)] = p(Side.BLACK, PieceType.KING)
            arr[idx(5, 7)] = p(Side.BLACK, PieceType.BISHOP)
            arr[idx(6, 7)] = p(Side.BLACK, PieceType.KNIGHT)
            arr[idx(7, 7)] = p(Side.BLACK, PieceType.ROOK)
            for (f in 0..7) arr[idx(f, 6)] = p(Side.BLACK, PieceType.PAWN)

            return Board(arr.toList())
        }

        fun empty(): Board = Board(List(64) { null })

        fun idx(file: Int, rank: Int): Int = rank * 8 + file
        fun idx(sq: Square): Int = idx(sq.file, sq.rank)
    }

    fun pieceAt(sq: Square): Piece? = cells[Board.idx(sq)]

    fun withPiece(sq: Square, piece: Piece?): Board {
        val m = cells.toMutableList()
        m[Board.idx(sq)] = piece
        return Board(m.toList())
    }

    fun movePiece(from: Square, to: Square): Board {
        val piece = pieceAt(from)
        var b = withPiece(from, null)
        b = b.withPiece(to, piece)
        return b
    }

    fun allPieces(): List<Pair<Square, Piece>> {
        val out = mutableListOf<Pair<Square, Piece>>()
        for (rank in 0..7) for (file in 0..7) {
            val sq = Square(file, rank)
            val p = pieceAt(sq)
            if (p != null) out += sq to p
        }
        return out
    }
}

sealed class GameResult {
    data object Ongoing : GameResult()
    data class Checkmate(val winner: Side) : GameResult()
    data object Stalemate : GameResult()
    data object DrawInsufficientMaterial : GameResult()
    data object DrawFiftyMove : GameResult()
    data object DrawThreefold : GameResult()
}

data class ChessGameState(
    val board: Board,
    val sideToMove: Side,
    val castling: CastlingRights,
    val enPassantTarget: Square?, // square a pawn could capture into
    val halfmoveClock: Int,
    val fullmoveNumber: Int,
    val selected: Square?,
    val legalTargets: Set<Square>,
    val lastMessage: String,
    val positionCounts: Map<String, Int>,
    val result: GameResult
) {
    companion object {
        fun initial(): ChessGameState {
            val b = Board.initial()
            val s = ChessGameState(
                board = b,
                sideToMove = Side.WHITE,
                castling = CastlingRights(true, true, true, true),
                enPassantTarget = null,
                halfmoveClock = 0,
                fullmoveNumber = 1,
                selected = null,
                legalTargets = emptySet(),
                lastMessage = "",
                positionCounts = emptyMap(),
                result = GameResult.Ongoing
            )
            return s.withRepetitionUpdated().withResultRecomputed()
        }

        /**
         * Parses a FEN string and returns the corresponding [ChessGameState],
         * or null if the FEN is invalid.
         */
        fun fromFen(fen: String): ChessGameState? {
            return try {
                val parts = fen.trim().split(" ")
                if (parts.size < 4) return null

                // 1. Piece placement
                val rows = parts[0].split("/")
                if (rows.size != 8) return null
                val cells = Array<Piece?>(64) { null }
                for ((rankIdx, row) in rows.withIndex()) {
                    val rank = 7 - rankIdx
                    var file = 0
                    for (ch in row) {
                        if (ch.isDigit()) {
                            file += ch.digitToInt()
                        } else {
                            val side = if (ch.isUpperCase()) Side.WHITE else Side.BLACK
                            val type = when (ch.lowercaseChar()) {
                                'k' -> PieceType.KING
                                'q' -> PieceType.QUEEN
                                'r' -> PieceType.ROOK
                                'b' -> PieceType.BISHOP
                                'n' -> PieceType.KNIGHT
                                'p' -> PieceType.PAWN
                                else -> return null
                            }
                            cells[rank * 8 + file] = Piece(side, type)
                            file++
                        }
                    }
                }
                val board = Board(cells.toList())

                // 2. Active color
                val sideToMove = when (parts[1]) {
                    "w" -> Side.WHITE
                    "b" -> Side.BLACK
                    else -> return null
                }

                // 3. Castling
                val castlingStr = parts[2]
                val castling = CastlingRights(
                    wk = castlingStr.contains('K'),
                    wq = castlingStr.contains('Q'),
                    bk = castlingStr.contains('k'),
                    bq = castlingStr.contains('q')
                )

                // 4. En passant
                val epStr = parts[3]
                val enPassantTarget: Square? = if (epStr == "-") null else {
                    val f = epStr[0] - 'a'
                    val r = epStr[1] - '1'
                    if (f in 0..7 && r in 0..7) Square(f, r) else null
                }

                // 5. Halfmove clock (optional)
                val halfmoveClock = if (parts.size > 4) parts[4].toIntOrNull() ?: 0 else 0

                // 6. Fullmove number (optional)
                val fullmoveNumber = if (parts.size > 5) parts[5].toIntOrNull() ?: 1 else 1

                ChessGameState(
                    board = board,
                    sideToMove = sideToMove,
                    castling = castling,
                    enPassantTarget = enPassantTarget,
                    halfmoveClock = halfmoveClock,
                    fullmoveNumber = fullmoveNumber,
                    selected = null,
                    legalTargets = emptySet(),
                    lastMessage = "",
                    positionCounts = emptyMap(),
                    result = GameResult.Ongoing
                ).withRepetitionUpdated().withResultRecomputed()
            } catch (_: Exception) {
                null
            }
        }
    }

    fun handleTap(sq: Square): TapResult {
        if (result != GameResult.Ongoing) return TapResult(this, "Game over")
        val p = board.pieceAt(sq)

        // If nothing selected yet: select your own piece
        if (selected == null) {
            if (p == null) return TapResult(this, "")
            if (p.side != sideToMove) return TapResult(this, "Not your piece")
            val moves = legalMovesFrom(sq)
            return TapResult(
                newState = copy(
                    selected = sq,
                    legalTargets = moves.map { it.to }.toSet(),
                    lastMessage = ""
                ),
                message = ""
            )
        }

        // If tapping same square: deselect
        if (selected == sq) {
            return TapResult(copy(selected = null, legalTargets = emptySet(), lastMessage = ""), "")
        }

        // If tapping another of your pieces: switch selection
        if (p != null && p.side == sideToMove) {
            val moves = legalMovesFrom(sq)
            return TapResult(
                copy(selected = sq, legalTargets = moves.map { it.to }.toSet(), lastMessage = ""),
                ""
            )
        }

        // Attempt move from selected -> sq if legal
        val from = selected
        val candidate = legalMovesFrom(from).firstOrNull { it.to == sq }
        if (candidate == null) {
            return TapResult(copy(lastMessage = "Illegal move"), "Illegal move")
        }

        val next = applyMove(candidate)
            .copy(selected = null, legalTargets = emptySet(), lastMessage = "")
            .withRepetitionUpdated()
            .withResultRecomputed()

        return TapResult(next, "", candidate)
    }

    fun isInCheck(side: Side): Boolean {
        val kingSq = board.allPieces()
            .firstOrNull { it.second.side == side && it.second.type == PieceType.KING }
            ?.first ?: return false
        return isSquareAttacked(kingSq, side.opposite())
    }

    // ── Added for offline bot support ──────────────────────────────────────

    /** Public: all legal moves for the side to move (needed for the offline bot). */
    fun allLegalMoves(): List<Move> = generateAllLegalMoves(sideToMove)

    /**
     * Public: apply a Move and return the resulting state.
     * Used for bot search; validates legality before applying.
     */
    fun applyMoveForAnalysis(mv: Move): ChessGameState? {
        val legal = legalMovesFrom(mv.from)
        if (legal.none { it == mv }) return null

        return applyMove(mv)
            .copy(selected = null, legalTargets = emptySet(), lastMessage = "")
            .withRepetitionUpdated()
            .withResultRecomputed()
    }

    // ── Move generation ────────────────────────────────────────────────────

    private fun legalMovesFrom(from: Square): List<Move> {
        val piece = board.pieceAt(from) ?: return emptyList()
        if (piece.side != sideToMove) return emptyList()

        val pseudo = generatePseudoLegalMoves(from, piece)
        // Filter out moves that leave your king in check
        return pseudo.filter { mv ->
            val after = applyMove(mv)
            !after.isInCheck(piece.side)
        }
    }

    private fun generateAllLegalMoves(side: Side): List<Move> {
        if (side != sideToMove) return emptyList()
        val out = mutableListOf<Move>()
        for ((sq, p) in board.allPieces()) {
            if (p.side != side) continue
            out += legalMovesFrom(sq)
        }
        return out
    }

    private fun generatePseudoLegalMoves(from: Square, piece: Piece): List<Move> {
        return when (piece.type) {
            PieceType.PAWN -> pawnMoves(from, piece.side)
            PieceType.KNIGHT -> knightMoves(from, piece.side)
            PieceType.BISHOP -> slidingMoves(
                from,
                piece.side,
                dirs = listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)
            )

            PieceType.ROOK -> slidingMoves(
                from,
                piece.side,
                dirs = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
            )

            PieceType.QUEEN -> slidingMoves(
                from,
                piece.side,
                dirs = listOf(
                    1 to 1, 1 to -1, -1 to 1, -1 to -1,
                    1 to 0, -1 to 0, 0 to 1, 0 to -1
                )
            )

            PieceType.KING -> kingMoves(from, piece.side)
        }
    }

    private fun pawnMoves(from: Square, side: Side): List<Move> {
        val out = mutableListOf<Move>()
        val dir = if (side == Side.WHITE) 1 else -1
        val startRank = if (side == Side.WHITE) 1 else 6
        val promoteRank = if (side == Side.WHITE) 7 else 0

        // forward 1
        val one = Square(from.file, from.rank + dir).takeIf { it.rank in 0..7 }
        if (one != null && board.pieceAt(one) == null) {
            if (one.rank == promoteRank) {
                out += Move.Normal(from, one, promotion = PieceType.QUEEN)
            } else out += Move.Normal(from, one)

            // forward 2 from start
            if (from.rank == startRank) {
                val two = Square(from.file, from.rank + 2 * dir)
                if (board.pieceAt(two) == null) out += Move.Normal(from, two)
            }
        }

        // captures
        for (df in listOf(-1, 1)) {
            val tf = from.file + df
            val tr = from.rank + dir
            if (tf !in 0..7 || tr !in 0..7) continue
            val to = Square(tf, tr)
            val target = board.pieceAt(to)
            if (target != null && target.side != side) {
                if (to.rank == promoteRank) out += Move.Normal(from, to, promotion = PieceType.QUEEN)
                else out += Move.Normal(from, to)
            }
            // en passant
            if (enPassantTarget != null && to == enPassantTarget) {
                val captured = Square(tf, from.rank) // pawn sits behind target square
                out += Move.EnPassant(from, to, capturedPawnSquare = captured)
            }
        }
        return out
    }

    private fun knightMoves(from: Square, side: Side): List<Move> {
        val out = mutableListOf<Move>()
        val deltas = listOf(
            1 to 2, 2 to 1, -1 to 2, -2 to 1,
            1 to -2, 2 to -1, -1 to -2, -2 to -1
        )
        for ((df, dr) in deltas) {
            val f = from.file + df
            val r = from.rank + dr
            if (f !in 0..7 || r !in 0..7) continue
            val to = Square(f, r)
            val t = board.pieceAt(to)
            if (t == null || t.side != side) out += Move.Normal(from, to)
        }
        return out
    }

    private fun slidingMoves(from: Square, side: Side, dirs: List<Pair<Int, Int>>): List<Move> {
        val out = mutableListOf<Move>()
        for ((df, dr) in dirs) {
            var f = from.file + df
            var r = from.rank + dr
            while (f in 0..7 && r in 0..7) {
                val to = Square(f, r)
                val t = board.pieceAt(to)
                if (t == null) {
                    out += Move.Normal(from, to)
                } else {
                    if (t.side != side) out += Move.Normal(from, to)
                    break
                }
                f += df
                r += dr
            }
        }
        return out
    }

    private fun kingMoves(from: Square, side: Side): List<Move> {
        val out = mutableListOf<Move>()
        for (dr in -1..1) for (df in -1..1) {
            if (df == 0 && dr == 0) continue
            val f = from.file + df
            val r = from.rank + dr
            if (f !in 0..7 || r !in 0..7) continue
            val to = Square(f, r)
            val t = board.pieceAt(to)
            if (t == null || t.side != side) out += Move.Normal(from, to)
        }

        // Castling (must be pseudo-legal; check conditions here)
        if (!isInCheck(side)) {
            val rights = castling
            if (side == Side.WHITE) {
                // King on e1
                if (from == Square(4, 0)) {
                    if (rights.wk && canCastleThrough(side, listOf(Square(5, 0), Square(6, 0))) &&
                        board.pieceAt(Square(7, 0))?.let { it.side == side && it.type == PieceType.ROOK } == true
                    ) {
                        out += Move.Castle(
                            from,
                            Square(6, 0),
                            rookFrom = Square(7, 0),
                            rookTo = Square(5, 0)
                        )
                    }
                    if (rights.wq && canCastleThrough(side, listOf(Square(3, 0), Square(2, 0))) &&
                        board.pieceAt(Square(0, 0))?.let { it.side == side && it.type == PieceType.ROOK } == true &&
                        board.pieceAt(Square(1, 0)) == null // b1 must be empty too
                    ) {
                        // squares between king and rook: d1,c1,b1 (b1 already checked)
                        if (board.pieceAt(Square(3, 0)) == null && board.pieceAt(Square(2, 0)) == null) {
                            out += Move.Castle(
                                from,
                                Square(2, 0),
                                rookFrom = Square(0, 0),
                                rookTo = Square(3, 0)
                            )
                        }
                    }
                }
            } else {
                // black king on e8
                if (from == Square(4, 7)) {
                    if (rights.bk && canCastleThrough(side, listOf(Square(5, 7), Square(6, 7))) &&
                        board.pieceAt(Square(7, 7))?.let { it.side == side && it.type == PieceType.ROOK } == true
                    ) {
                        out += Move.Castle(
                            from,
                            Square(6, 7),
                            rookFrom = Square(7, 7),
                            rookTo = Square(5, 7)
                        )
                    }
                    if (rights.bq && canCastleThrough(side, listOf(Square(3, 7), Square(2, 7))) &&
                        board.pieceAt(Square(0, 7))?.let { it.side == side && it.type == PieceType.ROOK } == true &&
                        board.pieceAt(Square(1, 7)) == null
                    ) {
                        if (board.pieceAt(Square(3, 7)) == null && board.pieceAt(Square(2, 7)) == null) {
                            out += Move.Castle(
                                from,
                                Square(2, 7),
                                rookFrom = Square(0, 7),
                                rookTo = Square(3, 7)
                            )
                        }
                    }
                }
            }
        }

        return out
    }

    private fun canCastleThrough(side: Side, squares: List<Square>): Boolean {
        // squares must be empty and not attacked
        for (sq in squares) {
            if (board.pieceAt(sq) != null) return false
            if (isSquareAttacked(sq, side.opposite())) return false
        }
        return true
    }

    private fun isSquareAttacked(target: Square, bySide: Side): Boolean {
        // Generate attacks by scanning pieces of bySide and seeing if target is in their attack set.
        for ((sq, p) in board.allPieces()) {
            if (p.side != bySide) continue
            if (attacksSquare(from = sq, piece = p, target = target)) return true
        }
        return false
    }

    private fun attacksSquare(from: Square, piece: Piece, target: Square): Boolean {
        val df = target.file - from.file
        val dr = target.rank - from.rank
        return when (piece.type) {
            PieceType.PAWN -> {
                val dir = if (piece.side == Side.WHITE) 1 else -1
                dr == dir && abs(df) == 1
            }

            PieceType.KNIGHT -> (abs(df) == 1 && abs(dr) == 2) || (abs(df) == 2 && abs(dr) == 1)
            PieceType.BISHOP -> attacksSliding(
                from,
                target,
                dirs = listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)
            )

            PieceType.ROOK -> attacksSliding(from, target, dirs = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1))
            PieceType.QUEEN -> attacksSliding(
                from,
                target,
                dirs = listOf(
                    1 to 1, 1 to -1, -1 to 1, -1 to -1,
                    1 to 0, -1 to 0, 0 to 1, 0 to -1
                )
            )

            PieceType.KING -> abs(df) <= 1 && abs(dr) <= 1
        }
    }

    private fun attacksSliding(from: Square, target: Square, dirs: List<Pair<Int, Int>>): Boolean {
        for ((stepF, stepR) in dirs) {
            var f = from.file + stepF
            var r = from.rank + stepR
            while (f in 0..7 && r in 0..7) {
                val sq = Square(f, r)
                if (sq == target) return true
                if (board.pieceAt(sq) != null) break
                f += stepF
                r += stepR
            }
        }
        return false
    }

    private fun applyMove(mv: Move): ChessGameState {
        val moving = board.pieceAt(mv.from) ?: return this
        var nb = board
        var nCastling = castling
        var nEnPassant: Square? = null
        var nHalfmove = halfmoveClock + 1
        var nFullmove = fullmoveNumber

        val capturedPiece: Piece? = when (mv) {
            is Move.EnPassant -> board.pieceAt(mv.capturedPawnSquare)
            else -> board.pieceAt(mv.to)
        }

        // halfmove clock resets on pawn move or capture
        if (moving.type == PieceType.PAWN || capturedPiece != null) nHalfmove = 0

        // Update castling rights if king/rook moves or rook is captured
        fun removeWhiteKingSide() {
            nCastling = nCastling.copy(wk = false)
        }

        fun removeWhiteQueenSide() {
            nCastling = nCastling.copy(wq = false)
        }

        fun removeBlackKingSide() {
            nCastling = nCastling.copy(bk = false)
        }

        fun removeBlackQueenSide() {
            nCastling = nCastling.copy(bq = false)
        }

        // If moving king, lose both rights
        if (moving.type == PieceType.KING) {
            if (moving.side == Side.WHITE) nCastling = nCastling.copy(wk = false, wq = false)
            else nCastling = nCastling.copy(bk = false, bq = false)
        }

        // If moving rook from its initial square, lose that side right
        if (moving.type == PieceType.ROOK) {
            if (moving.side == Side.WHITE) {
                if (mv.from == Square(0, 0)) removeWhiteQueenSide()
                if (mv.from == Square(7, 0)) removeWhiteKingSide()
            } else {
                if (mv.from == Square(0, 7)) removeBlackQueenSide()
                if (mv.from == Square(7, 7)) removeBlackKingSide()
            }
        }

        // If capturing rook on its initial square, lose that side right
        if (capturedPiece?.type == PieceType.ROOK) {
            if (capturedPiece.side == Side.WHITE) {
                if (mv.to == Square(0, 0)) removeWhiteQueenSide()
                if (mv.to == Square(7, 0)) removeWhiteKingSide()
            } else {
                if (mv.to == Square(0, 7)) removeBlackQueenSide()
                if (mv.to == Square(7, 7)) removeBlackKingSide()
            }
        }

        when (mv) {
            is Move.Normal -> {
                nb = nb.withPiece(mv.from, null)

                val placedPiece =
                    if (mv.promotion != null) Piece(moving.side, mv.promotion) else moving
                nb = nb.withPiece(mv.to, placedPiece)

                // Set en passant target if a pawn moved 2 squares
                if (moving.type == PieceType.PAWN && abs(mv.to.rank - mv.from.rank) == 2) {
                    val midRank = (mv.to.rank + mv.from.rank) / 2
                    nEnPassant = Square(mv.from.file, midRank)
                }
            }

            is Move.EnPassant -> {
                nb = nb.withPiece(mv.from, null)
                nb = nb.withPiece(mv.to, moving)
                nb = nb.withPiece(mv.capturedPawnSquare, null)
            }

            is Move.Castle -> {
                nb = nb.withPiece(mv.from, null)
                nb = nb.withPiece(mv.to, moving)
                val rookOriginal = board.pieceAt(mv.rookFrom)
                nb = nb.withPiece(mv.rookFrom, null)
                nb = nb.withPiece(mv.rookTo, rookOriginal)
            }
        }

        val nextSide = sideToMove.opposite()
        if (nextSide == Side.WHITE) nFullmove += 1

        return copy(
            board = nb,
            sideToMove = nextSide,
            castling = nCastling,
            enPassantTarget = nEnPassant,
            halfmoveClock = nHalfmove,
            fullmoveNumber = nFullmove
        )
    }

    private fun withResultRecomputed(): ChessGameState {
        // 50-move rule
        if (halfmoveClock >= 100) {
            return copy(result = GameResult.DrawFiftyMove)
        }

        // Insufficient material
        if (isInsufficientMaterial(board)) {
            return copy(result = GameResult.DrawInsufficientMaterial)
        }

        // Threefold repetition
        val key = positionKey()
        val count = positionCounts[key] ?: 0
        if (count >= 3) {
            return copy(result = GameResult.DrawThreefold)
        }

        val legal = generateAllLegalMoves(sideToMove)
        if (legal.isNotEmpty()) return copy(result = GameResult.Ongoing)

        // no legal moves -> checkmate or stalemate
        return if (isInCheck(sideToMove)) {
            copy(result = GameResult.Checkmate(winner = sideToMove.opposite()))
        } else {
            copy(result = GameResult.Stalemate)
        }
    }

    private fun withRepetitionUpdated(): ChessGameState {
        val key = positionKey()
        val newCounts = positionCounts.toMutableMap()
        newCounts[key] = (newCounts[key] ?: 0) + 1
        return copy(positionCounts = newCounts.toMap())
    }

    // ── Public helpers for online multiplayer ───────────────────────────────

    /**
     * Finds the legal move matching the given UCI string and applies it.
     * Returns null if [uci] is malformed or not a legal move in this position.
     */
    fun applyUciMove(uci: String): ChessGameState? {
        if (uci.length < 4) return null
        val fromFile = uci[0] - 'a'
        val fromRank = uci[1] - '1'
        val toFile = uci[2] - 'a'
        val toRank = uci[3] - '1'
        if (fromFile !in 0..7 || fromRank !in 0..7 || toFile !in 0..7 || toRank !in 0..7) return null
        val from = Square(fromFile, fromRank)
        val to = Square(toFile, toRank)
        val promoType: PieceType? = if (uci.length >= 5) {
            when (uci[4].lowercaseChar()) {
                'q' -> PieceType.QUEEN
                'r' -> PieceType.ROOK
                'b' -> PieceType.BISHOP
                'n' -> PieceType.KNIGHT
                else -> null
            }
        } else null

        val candidate = legalMovesFrom(from).firstOrNull { mv ->
            mv.to == to && (mv !is Move.Normal || mv.promotion == promoType)
        } ?: return null

        return applyMove(candidate)
            .copy(selected = null, legalTargets = emptySet(), lastMessage = "")
            .withRepetitionUpdated()
            .withResultRecomputed()
    }

    /** Serialises the current position to a FEN string. */
    fun toFen(): String {
        val sb = StringBuilder()
        for (rank in 7 downTo 0) {
            var empty = 0
            for (file in 0..7) {
                val p = board.pieceAt(Square(file, rank))
                if (p == null) {
                    empty++
                } else {
                    if (empty > 0) {
                        sb.append(empty); empty = 0
                    }
                    val ch = when (p.type) {
                        PieceType.KING -> 'K'
                        PieceType.QUEEN -> 'Q'
                        PieceType.ROOK -> 'R'
                        PieceType.BISHOP -> 'B'
                        PieceType.KNIGHT -> 'N'
                        PieceType.PAWN -> 'P'
                    }
                    sb.append(if (p.side == Side.WHITE) ch else ch.lowercaseChar())
                }
            }
            if (empty > 0) sb.append(empty)
            if (rank > 0) sb.append('/')
        }
        sb.append(' ')
        sb.append(if (sideToMove == Side.WHITE) 'w' else 'b')
        sb.append(' ')
        val castlingStr = buildString {
            if (castling.wk) append('K')
            if (castling.wq) append('Q')
            if (castling.bk) append('k')
            if (castling.bq) append('q')
            if (isEmpty()) append('-')
        }
        sb.append(castlingStr)
        sb.append(' ')
        sb.append(enPassantTarget?.toString() ?: "-")
        sb.append(' ')
        sb.append(halfmoveClock)
        sb.append(' ')
        sb.append(fullmoveNumber)
        return sb.toString()
    }

    private fun positionKey(): String {
        // A simple repetition key: piece placement + side + castling + en-passant file
        // (Enough for threefold repetition detection in practice.)
        val sb = StringBuilder()
        for (rank in 7 downTo 0) {
            for (file in 0..7) {
                val p = board.pieceAt(Square(file, rank))
                sb.append(
                    when (p?.side) {
                        Side.WHITE -> when (p.type) {
                            PieceType.KING -> 'K'
                            PieceType.QUEEN -> 'Q'
                            PieceType.ROOK -> 'R'
                            PieceType.BISHOP -> 'B'
                            PieceType.KNIGHT -> 'N'
                            PieceType.PAWN -> 'P'
                        }

                        Side.BLACK -> when (p.type) {
                            PieceType.KING -> 'k'
                            PieceType.QUEEN -> 'q'
                            PieceType.ROOK -> 'r'
                            PieceType.BISHOP -> 'b'
                            PieceType.KNIGHT -> 'n'
                            PieceType.PAWN -> 'p'
                        }

                        null -> '.'
                    }
                )
            }
            sb.append('/')
        }
        sb.append('|')
        sb.append(if (sideToMove == Side.WHITE) 'w' else 'b')
        sb.append('|')
        sb.append(if (castling.wk) 'K' else '-')
        sb.append(if (castling.wq) 'Q' else '-')
        sb.append(if (castling.bk) 'k' else '-')
        sb.append(if (castling.bq) 'q' else '-')
        sb.append('|')
        sb.append(enPassantTarget?.file ?: -1)
        return sb.toString()
    }
}

data class TapResult(val newState: ChessGameState, val message: String, val move: Move? = null)

/** Returns the UCI notation for a move, e.g. "e2e4" or "e7e8q". */
fun Move.toUci(): String {
    val promo = when (this) {
        is Move.Normal -> when (promotion) {
            PieceType.QUEEN -> "q"
            PieceType.ROOK -> "r"
            PieceType.BISHOP -> "b"
            PieceType.KNIGHT -> "n"
            // These shouldn't occur in real chess promotion, but make the when exhaustive.
            PieceType.KING -> "k"
            PieceType.PAWN -> "p"
            null -> ""
        }

        else -> ""
    }
    return "$from$to$promo"
}

private fun isInsufficientMaterial(board: Board): Boolean {
    val pieces = board.allPieces().map { it.second }
    val nonKings = pieces.filter { it.type != PieceType.KING }
    if (nonKings.isEmpty()) return true // K vs K

    // K vs K + (B or N)
    if (nonKings.size == 1 && (nonKings[0].type == PieceType.BISHOP || nonKings[0].type == PieceType.KNIGHT)) return true

    // K+B vs K+B where bishops are on same color squares
    val bishops = nonKings.filter { it.type == PieceType.BISHOP }
    val others = nonKings.filter { it.type != PieceType.BISHOP }
    if (others.isNotEmpty()) return false
    if (bishops.size == 2) {
        // same color bishops only (both light or both dark)
        val squares = board.allPieces().filter { it.second.type == PieceType.BISHOP }.map { it.first }
        val colors = squares.map { (it.file + it.rank) % 2 }
        if (colors.size == 2 && colors[0] == colors[1]) return true
    }

    return false
}
