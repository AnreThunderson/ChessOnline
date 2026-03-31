package com.example.passandplaychess

import kotlin.math.max
import kotlin.random.Random

data class BotConfig(
    val enabled: Boolean,
    val botSide: Side,
    /** Search depth in plies (half-moves). 1–6 supported; 2–4 recommended for phones. */
    val depth: Int,
    /** Soft time limit per move (ms). Keeps higher depths responsive. */
    val timeLimitMs: Long = 350L
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

        val rootMoves = state.allLegalMoves()
        if (rootMoves.isEmpty()) return null

        val deadlineNs = System.nanoTime() + config.timeLimitMs * 1_000_000L
        val maxDepth = config.depth.coerceIn(1, 6)

        // Always have a fallback move.
        var bestMoveSoFar: Move = rootMoves[rng.nextInt(rootMoves.size)]
        var bestScoreSoFar = Int.MIN_VALUE

        // Iterative deepening: 1..maxDepth. If we time out mid-iteration, keep last completed depth's result.
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

            // After we make a move, it becomes opponent-to-move, so negate.
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
     * Negamax with alpha-beta pruning.
     * IMPORTANT: evaluateSideToMove() must be from side-to-move's perspective.
     */
    private fun negamax(
        state: ChessGameState,
        depth: Int,
        alpha: Int,
        beta: Int,
        deadlineNs: Long
    ): Int {
        if (System.nanoTime() >= deadlineNs) return evaluateSideToMove(state)

        if (depth <= 0 || state.result != GameResult.Ongoing) {
            return evaluateSideToMove(state)
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

            // Optional: tiny check bonus. If you want max speed, remove this block.
            val checkBonus = runCatching {
                val next = state.applyMoveForAnalysis(mv)
                if (next != null && next.isInCheck(next.sideToMove)) 25 else 0
            }.getOrDefault(0)

            return captureScore * 10 + promoScore + checkBonus
        }

        return moves.sortedByDescending(::scoreMove)
    }

    /**
     * Static eval from the CURRENT side-to-move perspective.
     * Positive => good for sideToMove, negative => good for the other side.
     *
     * This includes:
     * - Material (dominant)
     * - Piece-square tables (PST) for basic development/center play
     * - King safety / castling incentive (discourage early king walks with queens on board)
     * - Check bonus/penalty
     */
    private fun evaluateSideToMove(state: ChessGameState): Int {
        when (val r = state.result) {
            GameResult.Ongoing -> { /* continue */ }
            is GameResult.Checkmate -> {
                return if (r.winner == state.sideToMove) 1_000_000 else -1_000_000
            }
            else -> return 0
        }

        // Material + PST from White’s perspective
        var scoreWhite = 0

        var whiteKingSq: Square? = null
        var blackKingSq: Square? = null
        var whiteQueenOnBoard = false
        var blackQueenOnBoard = false

        for ((sq, p) in state.board.allPieces()) {
            val v = pieceValue(p.type)
            val pst = pstBonus(p, sq)

            val signed = if (p.side == Side.WHITE) (v + pst) else -(v + pst)
            scoreWhite += signed

            if (p.type == PieceType.KING) {
                if (p.side == Side.WHITE) whiteKingSq = sq else blackKingSq = sq
            }
            if (p.type == PieceType.QUEEN) {
                if (p.side == Side.WHITE) whiteQueenOnBoard = true else blackQueenOnBoard = true
            }
        }

        // King safety / opening sanity:
        fun kingSafetyPenalty(side: Side, kingSq: Square?, enemyQueenExists: Boolean): Int {
            if (kingSq == null) return 0
            if (!enemyQueenExists) return 0

            // Center is dangerous when queens exist.
            val centerPenalty =
                if (kingSq.file in 2..5 && kingSq.rank in 2..5) 60 else 0

            val homeRank = if (side == Side.WHITE) 0 else 7
            val castledSq1 = Square(6, homeRank) // g1/g8
            val castledSq2 = Square(2, homeRank) // c1/c8

            val notCastledPenalty =
                if (kingSq != castledSq1 && kingSq != castledSq2) 20 else 0

            // Penalize leaving the home rank early.
            val leftHomeRankPenalty =
                if (kingSq.rank != homeRank) 35 else 0

            return centerPenalty + notCastledPenalty + leftHomeRankPenalty
        }

        // Penalties apply to that side (so subtract for white, add for black because scoreWhite is white-perspective)
        scoreWhite -= kingSafetyPenalty(Side.WHITE, whiteKingSq, enemyQueenExists = blackQueenOnBoard)
        scoreWhite += kingSafetyPenalty(Side.BLACK, blackKingSq, enemyQueenExists = whiteQueenOnBoard)

        // Check heuristic (white-perspective)
        if (state.isInCheck(Side.WHITE)) scoreWhite -= 25
        if (state.isInCheck(Side.BLACK)) scoreWhite += 25

        // Convert to side-to-move perspective for negamax
        return if (state.sideToMove == Side.WHITE) scoreWhite else -scoreWhite
    }

    /**
     * Very small piece-square tables (PST).
     * Values are from White’s perspective; Black is mirrored.
     */
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

// ── Piece-square tables (PST) ────────────────────────────────────────────────
// These are simple/cheap and mainly prevent nonsense openings (king walks, random pawns).

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
