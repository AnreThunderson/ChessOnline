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
     * IMPORTANT: evaluate() must be from side-to-move's perspective.
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
     * - (optional) a tiny check bonus
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
     */
    private fun evaluateSideToMove(state: ChessGameState): Int {
        when (val r = state.result) {
            GameResult.Ongoing -> { /* continue */ }
            is GameResult.Checkmate -> {
                // Winner is the side who delivered mate.
                return if (r.winner == state.sideToMove) 1_000_000 else -1_000_000
            }
            else -> return 0
        }

        var materialWhite = 0
        var materialBlack = 0
        for ((_, p) in state.board.allPieces()) {
            val v = pieceValue(p.type)
            if (p.side == Side.WHITE) materialWhite += v else materialBlack += v
        }

        val materialFromWhite = materialWhite - materialBlack
        val scoreFromSideToMove =
            if (state.sideToMove == Side.WHITE) materialFromWhite else -materialFromWhite

        // Tiny check heuristic (still from side-to-move perspective)
        var score = scoreFromSideToMove
        if (state.isInCheck(state.sideToMove)) score -= 15
        if (state.isInCheck(state.sideToMove.opposite())) score += 15

        // Tiny tempo
        score += 2

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

    private fun List<Move>.randomOrNull(rng: Random): Move? =
        if (isEmpty()) null else this[rng.nextInt(size)]
}
