package com.example.passandplaychess

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

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
        val maximizing = (state.sideToMove == config.botSide)

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
                if (a >= b) break
            }
            return best
        } else {
            var best = Int.MAX_VALUE
            for (mv in moves) {
                val next = state.applyMoveForAnalysis(mv) ?: continue
                val score = minimax(next, depth - 1, a, b, botSide)
                best = min(best, score)
                b = min(b, best)
                if (a >= b) break
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
                return if (r.winner == botSide) 1_000_000 else -1_000_000
            }
            else -> return 0 // draws
        }

        var score = 0
        for ((_, p) in state.board.allPieces()) {
            val v = pieceValue(p.type)
            score += if (p.side == botSide) v else -v
        }

        // Mobility (tiny)
        val mobility = state.allLegalMoves().size
        score += if (state.sideToMove == botSide) mobility else -mobility

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
