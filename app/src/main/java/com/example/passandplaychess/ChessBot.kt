package com.example.passandplaychess

import kotlin.math.max
import kotlin.random.Random

data class BotConfig(
    val enabled: Boolean,
    val botSide: Side,
    /** Search depth in plies (half-moves). 1–6 supported; 2–4 recommended for phones. */
    val depth: Int,
    /** Soft time limit per move (ms). */
    val timeLimitMs: Long = 450L
)

object ChessBot {

    fun chooseMoveUci(
        state: ChessGameState,
        config: BotConfig,
        rng: Random = Random.Default
    ): String? {
        if (!config.enabled) return null
        if (state.result != GameResult.Ongoing) return null
        if (state.sideToMove != config.botSide) return null

        val rootMoves = state.allLegalMoves()
        if (rootMoves.isEmpty()) return null

        val deadlineNs = System.nanoTime() + config.timeLimitMs * 1_000_000L
        val maxDepth = config.depth.coerceIn(1, 7)

        var bestMoveSoFar: Move = rootMoves[rng.nextInt(rootMoves.size)]
        var bestScoreSoFar = Int.MIN_VALUE

        for (d in 1..maxDepth) {
            val rr = searchRoot(state, depth = d, deadlineNs = deadlineNs, rng = rng)
            if (!rr.completed) break
            if (rr.move != null) {
                bestMoveSoFar = rr.move
                bestScoreSoFar = rr.score
            }
        }

        return bestMoveSoFar.toUci()
    }

    private data class RootResult(val move: Move?, val score: Int, val completed: Boolean)

    private fun searchRoot(
        state: ChessGameState,
        depth: Int,
        deadlineNs: Long,
        rng: Random
    ): RootResult {
        val moves = orderMoves(state, state.allLegalMoves())

        var bestScore = Int.MIN_VALUE
        val bestMoves = mutableListOf<Move>()

        for (mv in moves) {
            if (System.nanoTime() >= deadlineNs) {
                return RootResult(bestMoves.randomOrNull(rng), bestScore, completed = false)
            }

            val next = state.applyMoveForAnalysis(mv) ?: continue

            val score = -negamax(
                state = next,
                depth = depth - 1,
                alpha = Int.MIN_VALUE + 1,
                beta = Int.MAX_VALUE,
                deadlineNs = deadlineNs
            )

            if (score > bestScore) {
                bestScore = score
                bestMoves.clear()
                bestMoves.add(mv)
            } else if (score == bestScore) {
                bestMoves.add(mv)
            }
        }

        return RootResult(bestMoves.randomOrNull(rng), bestScore, completed = true)
    }

    /**
     * Negamax with alpha-beta. Evaluation must be from side-to-move perspective.
     * Uses quiescence search at leaf nodes to avoid capture-horizon blunders.
     */
    private fun negamax(
        state: ChessGameState,
        depth: Int,
        alpha: Int,
        beta: Int,
        deadlineNs: Long
    ): Int {
        if (System.nanoTime() >= deadlineNs) return evaluateSideToMove(state)

        if (state.result != GameResult.Ongoing) return evaluateSideToMove(state)

        if (depth <= 0) {
            return quiescence(state, alpha, beta, deadlineNs)
        }

        val moves = state.allLegalMoves()
        if (moves.isEmpty()) return evaluateSideToMove(state)

        var a = alpha
        var best = Int.MIN_VALUE

        val ordered = orderMoves(state, moves)

        for (mv in ordered) {
            if (System.nanoTime() >= deadlineNs) break

            val next = state.applyMoveForAnalysis(mv) ?: continue
            val score = -negamax(
                state = next,
                depth = depth - 1,
                alpha = -beta,
                beta = -a,
                deadlineNs = deadlineNs
            )

            best = max(best, score)
            a = max(a, score)
            if (a >= beta) break
        }

        return best
    }

    /**
     * Quiescence search: extend only capture sequences (and promotions).
     * This prevents "win a pawn, lose a piece" blunders at the horizon.
     */
    private fun quiescence(
        state: ChessGameState,
        alpha: Int,
        beta: Int,
        deadlineNs: Long
    ): Int {
        if (System.nanoTime() >= deadlineNs) return evaluateSideToMove(state)
        if (state.result != GameResult.Ongoing) return evaluateSideToMove(state)

        var a = alpha

        val standPat = evaluateSideToMove(state)
        if (standPat >= beta) return standPat
        if (standPat > a) a = standPat

        // Generate only tactical moves: captures, en-passant, promotions.
        val tactical = state.allLegalMoves().filter { mv ->
            when (mv) {
                is Move.EnPassant -> true
                is Move.Castle -> false
                is Move.Normal -> {
                    val isCapture = state.board.pieceAt(mv.to) != null
                    val isPromo = mv.promotion != null
                    isCapture || isPromo
                }
            }
        }

        if (tactical.isEmpty()) return standPat

        val ordered = orderMoves(state, tactical)

        for (mv in ordered) {
            if (System.nanoTime() >= deadlineNs) break
            val next = state.applyMoveForAnalysis(mv) ?: continue

            val score = -quiescence(next, -beta, -a, deadlineNs)

            if (score >= beta) return score
            if (score > a) a = score
        }

        return a
    }

    /**
     * Move ordering:
     * - captures first (by captured piece value)
     * - promotions next
     * - small check bonus
     */
    private fun orderMoves(state: ChessGameState, moves: List<Move>): List<Move> {
        fun scoreMove(mv: Move): Int {
            val captured = when (mv) {
                is Move.EnPassant -> Piece(state.sideToMove.opposite(), PieceType.PAWN)
                else -> state.board.pieceAt(mv.to)
            }
            val captureScore = if (captured != null) pieceValue(captured.type) else 0

            val promoScore = when (mv) {
                is Move.Normal -> if (mv.promotion != null) pieceValue(mv.promotion) + 200 else 0
                else -> 0
            }

            val checkBonus = runCatching {
                val next = state.applyMoveForAnalysis(mv)
                if (next != null && next.isInCheck(next.sideToMove)) 25 else 0
            }.getOrDefault(0)

            return captureScore * 10 + promoScore + checkBonus
        }

        return moves.sortedByDescending(::scoreMove)
    }

    /**
     * Static eval from side-to-move perspective.
     * Includes: material + PST + simple king safety.
     */
    private fun evaluateSideToMove(state: ChessGameState): Int {
        when (val r = state.result) {
            GameResult.Ongoing -> { /* continue */ }
            is GameResult.Checkmate -> {
                return if (r.winner == state.sideToMove) 1_000_000 else -1_000_000
            }
            else -> return 0
        }

        var scoreWhite = 0

        var whiteKingSq: Square? = null
        var blackKingSq: Square? = null
        var whiteQueenOnBoard = false
        var blackQueenOnBoard = false

        for ((sq, p) in state.board.allPieces()) {
            val v = pieceValue(p.type)
            val pst = pstBonus(p, sq)
            scoreWhite += if (p.side == Side.WHITE) (v + pst) else -(v + pst)

            if (p.type == PieceType.KING) {
                if (p.side == Side.WHITE) whiteKingSq = sq else blackKingSq = sq
            }
            if (p.type == PieceType.QUEEN) {
                if (p.side == Side.WHITE) whiteQueenOnBoard = true else blackQueenOnBoard = true
            }
        }

        fun kingSafetyPenalty(side: Side, kingSq: Square?, enemyQueenExists: Boolean): Int {
            if (kingSq == null) return 0
            if (!enemyQueenExists) return 0

            val centerPenalty =
                if (kingSq.file in 2..5 && kingSq.rank in 2..5) 60 else 0

            val homeRank = if (side == Side.WHITE) 0 else 7
            val castledSq1 = Square(6, homeRank)
            val castledSq2 = Square(2, homeRank)

            val notCastledPenalty =
                if (kingSq != castledSq1 && kingSq != castledSq2) 20 else 0

            val leftHomeRankPenalty =
                if (kingSq.rank != homeRank) 35 else 0

            return centerPenalty + notCastledPenalty + leftHomeRankPenalty
        }

        scoreWhite -= kingSafetyPenalty(Side.WHITE, whiteKingSq, enemyQueenExists = blackQueenOnBoard)
        scoreWhite += kingSafetyPenalty(Side.BLACK, blackKingSq, enemyQueenExists = whiteQueenOnBoard)

        if (state.isInCheck(Side.WHITE)) scoreWhite -= 25
        if (state.isInCheck(Side.BLACK)) scoreWhite += 25

        return if (state.sideToMove == Side.WHITE) scoreWhite else -scoreWhite
    }

    private fun pstBonus(piece: Piece, sq: Square): Int {
        val r = if (piece.side == Side.WHITE) sq.rank else (7 - sq.rank)
        val f = sq.file
        fun at(table: IntArray): Int = table[r * 8 + f]

        return when (piece.type) {
            PieceType.PAWN -> at(PST_PAWN)
            PieceType.KNIGHT -> at(PST_KNIGHT)
            PieceType.BISHOP -> at(PST_BISHOP)
            PieceType.ROOK -> at(PST_ROOK)
            PieceType.QUEEN -> at(PST_QUEEN)
            PieceType.KING -> at(PST_KING_OPENING)
        }
    }

    private fun pieceValue(type: PieceType): Int = when (type) {
        PieceType.PAWN -> 100
        PieceType.KNIGHT -> 320
        PieceType.BISHOP -> 330
        PieceType.ROOK -> 500
        PieceType.QUEEN -> 900
        PieceType.KING -> 0
    }

    private fun List<Move>.randomOrNull(rng: Random): Move? =
        if (isEmpty()) null else this[rng.nextInt(size)]
}

// ── PST tables ───────────────────────────────────────────────────────────────

private val PST_PAWN = intArrayOf(
     0,  0,  0,  0,  0,  0,  0,  0,
     5,  8,  8, -5, -5,  8,  8,  5,
     4,  6,  8, 12, 12,  8,  6,  4,
     2,  4,  6, 10, 10,  6,  4,  2,
     1,  2,  4,  8,  8,  4,  2,  1,
     0,  0,  0,  4,  4,  0,  0,  0,
     0,  0,  0, -8, -8,  0,  0,  0,
     0,  0,  0,  0,  0,  0,  0,  0
)

private val PST_KNIGHT = intArrayOf(
    -50,-40,-30,-30,-30,-30,-40,-50,
    -40,-20,  0,  5,  5,  0,-20,-40,
    -30,  5, 10, 15, 15, 10,  5,-30,
    -30,  0, 15, 20, 20, 15,  0,-30,
    -30,  5, 15, 20, 20, 15,  5,-30,
    -30,  0, 10, 15, 15, 10,  0,-30,
    -40,-20,  0,  0,  0,  0,-20,-40,
    -50,-40,-30,-30,-30,-30,-40,-50
)

private val PST_BISHOP = intArrayOf(
    -20,-10,-10,-10,-10,-10,-10,-20,
    -10,  5,  0,  0,  0,  0,  5,-10,
    -10, 10, 10, 10, 10, 10, 10,-10,
    -10,  0, 10, 10, 10, 10,  0,-10,
    -10,  5,  5, 10, 10,  5,  5,-10,
    -10,  0,  5, 10, 10,  5,  0,-10,
    -10,  0,  0,  0,  0,  0,  0,-10,
    -20,-10,-10,-10,-10,-10,-10,-20
)

private val PST_ROOK = intArrayOf(
     0,  0,  0,  5,  5,  0,  0,  0,
    -5,  0,  0,  0,  0,  0,  0, -5,
    -5,  0,  0,  0,  0,  0,  0, -5,
    -5,  0,  0,  0,  0,  0,  0, -5,
    -5,  0,  0,  0,  0,  0,  0, -5,
    -5,  0,  0,  0,  0,  0,  0, -5,
     5, 10, 10, 10, 10, 10, 10,  5,
     0,  0,  0,  0,  0,  0,  0,  0
)

private val PST_QUEEN = intArrayOf(
    -20,-10,-10, -5, -5,-10,-10,-20,
    -10,  0,  0,  0,  0,  0,  0,-10,
    -10,  0,  5,  5,  5,  5,  0,-10,
     -5,  0,  5,  5,  5,  5,  0, -5,
      0,  0,  5,  5,  5,  5,  0, -5,
    -10,  5,  5,  5,  5,  5,  0,-10,
    -10,  0,  5,  0,  0,  0,  0,-10,
    -20,-10,-10, -5, -5,-10,-10,-20
)

private val PST_KING_OPENING = intArrayOf(
    -30,-40,-40,-50,-50,-40,-40,-30,
    -30,-40,-40,-50,-50,-40,-40,-30,
    -30,-40,-40,-50,-50,-40,-40,-30,
    -30,-40,-40,-50,-50,-40,-40,-30,
    -20,-30,-30,-40,-40,-30,-30,-20,
    -10,-20,-20,-20,-20,-20,-20,-10,
     20, 20,  0,  0,  0,  0, 20, 20,
     20, 30, 10,  0,  0, 10, 30, 20
)
