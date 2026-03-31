package com.example.passandplaychess

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

data class BotConfig(
    val enabled: Boolean,
    val botSide: Side,
    /** Search depth in plies (half-moves). 1–5 supported; 2–4 recommended for phones. */
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

        // Always have a fallback (random legal move) in case we time out immediately.
        var bestMoveSoFar: Move = rootMoves[rng.nextInt(rootMoves.size)]
        var bestScoreSoFar = Int.MIN_VALUE

        // Iterative deepening: depth 1..maxDepth. If we time out mid-iteration,
        // we keep the best move from the last completed depth.
        for (d in 1..maxDepth) {
            val (mv, score, completed) = searchRoot(
                state = state,
                botSide = config.botSide,
                depth = d,
                deadlineNs = deadlineNs,
                rng = rng
            )
            if (!completed) break

            if (mv != null) {
                bestMoveSoFar = mv
                bestScoreSoFar = score
            }
        }

        // Safety: if something weird happened, still pick a legal move.
        return bestMoveSoFar.toUci()
    }

    private data class RootResult(val move: Move?, val score: Int, val completed: Boolean)

    private fun searchRoot(
        state: ChessGameState,
        botSide: Side,
        depth: Int,
        deadlineNs: Long,
        rng: Random
    ): RootResult {
        val moves = orderMoves(state, state.allLegalMoves(), botSide)

        var bestScore = Int.MIN_VALUE
        val bestMoves = mutableListOf<Move>()

        for (mv in moves) {
            if (System.nanoTime() >= deadlineNs) {
                return RootResult(
                    move = bestMoves.randomOrNull(rng),
                    score = bestScore,
                    completed = false
                )
            }

            val next = state.applyMoveForAnalysis(mv) ?: continue
            val score = negamax(
                state = next,
                depth = depth - 1,
                alpha = Int.MIN_VALUE + 1,
                beta = Int.MAX_VALUE,
                botSide = botSide,
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

        val chosen = bestMoves.randomOrNull(rng)
        return RootResult(chosen, bestScore, completed = true)
    }

    /**
     * Negamax with alpha-beta pruning.
     * Score is always from botSide's perspective.
     */
    private fun negamax(
        state: ChessGameState,
        depth: Int,
        alpha: Int,
        beta: Int,
        botSide: Side,
        deadlineNs: Long
    ): Int {
        if (System.nanoTime() >= deadlineNs) {
            // Time cutoff: return a static eval of current position.
            return evaluate(state, botSide)
        }

        if (depth <= 0 || state.result != GameResult.Ongoing) {
            return evaluate(state, botSide)
        }

        val moves = state.allLegalMoves()
        if (moves.isEmpty()) return evaluate(state, botSide)

        var a = alpha
        var best = Int.MIN_VALUE

        val ordered = orderMoves(state, moves, botSide)

        for (mv in ordered) {
            if (System.nanoTime() >= deadlineNs) break

            val next = state.applyMoveForAnalysis(mv) ?: continue

            // Negamax: score flips sign when side to move changes.
            val score = -negamax(
                state = next,
                depth = depth - 1,
                alpha = -beta,
                beta = -a,
                botSide = botSide,
                deadlineNs = deadlineNs
            )

            best = max(best, score)
            a = max(a, score)
            if (a >= beta) break // beta cut-off
        }

        return best
    }

    /**
     * Move ordering heuristic:
     * - captures first (MVV-ish using piece values)
     * - promotions next
     * - everything else
     */
    private fun orderMoves(state: ChessGameState, moves: List<Move>, botSide: Side): List<Move> {
        fun scoreMove(mv: Move): Int {
            val toPiece = when (mv) {
                is Move.EnPassant -> Piece(state.sideToMove.opposite(), PieceType.PAWN) // treat as pawn capture
                else -> state.board.pieceAt(mv.to)
            }
            val captureScore = if (toPiece != null) pieceValue(toPiece.type) else 0

            val promoScore = when (mv) {
                is Move.Normal -> if (mv.promotion != null) pieceValue(mv.promotion) + 200 else 0
                else -> 0
            }

            // Small bonus if move gives check (cheap-ish: just apply and ask isInCheck)
            // NOTE: This costs some, but helps pruning. If you want max speed, remove.
            val givesCheckBonus = runCatching {
                val next = state.applyMoveForAnalysis(mv)
                if (next != null && next.isInCheck(botSide.opposite())) 25 else 0
            }.getOrDefault(0)

            return captureScore * 10 + promoScore + givesCheckBonus
        }

        return moves.sortedByDescending(::scoreMove)
    }

    /**
     * Static evaluation (fast).
     * - terminal states heavy
     * - material only (+ tiny check bonus/penalty)
     *
     * IMPORTANT: no mobility term (avoid generating moves during eval).
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

        // Tiny check heuristic (cheap enough)
        if (state.isInCheck(botSide)) score -= 15
        if (state.isInCheck(botSide.opposite())) score += 15

        // Slight tempo bonus: prefer positions where it's NOT opponent's advantage to move
        // (kept tiny; remove if you want)
        if (state.sideToMove == botSide) score += 2 else score -= 2

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
