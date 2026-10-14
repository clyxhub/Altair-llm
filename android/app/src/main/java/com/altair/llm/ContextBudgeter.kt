package com.altair.llm

/**
 * §5 Token budgeter.
 *
 * Runs before every request and guarantees the prompt fits the model's real
 * context window: newest turns stay raw, older turns collapse into a compact
 * note, and the whole thing is measured in tokens (not characters) when the
 * backend can tokenize. This is what stops long chats from silently overflowing
 * the window — or the phone's memory.
 */
object ContextBudgeter {

    data class Turn(val role: String, val content: String)

    data class Result(
        val turns: List<Turn>,
        val droppedTurns: Int,
        val summarized: Boolean,
        val usedTokens: Int
    )

    fun fit(
        system: String,
        turns: List<Turn>,
        profile: ModelProfile,
        reserveTokens: Int = profile.reserveTokens,
        countTokens: (String) -> Int
    ): Result {
        val budget = (profile.contextTokens - reserveTokens).coerceAtLeast(256)
        val sysCost = if (system.isBlank()) 0 else countTokens(system)
        var remaining = budget - sysCost

        val kept = ArrayDeque<Turn>()
        var dropped = 0
        for (t in turns.asReversed()) {
            val c = countTokens(t.content) + 4
            if (c > remaining && kept.isNotEmpty()) {
                dropped = turns.size - kept.size
                break
            }
            kept.addFirst(t)
            remaining -= c
        }
        // Always keep at least the newest turn.
        if (kept.isEmpty() && turns.isNotEmpty()) {
            kept.addLast(turns.last())
            dropped = turns.size - 1
        }

        val used = if (kept.isEmpty() && sysCost == 0) 0 else (budget - remaining).coerceAtLeast(sysCost)
        if (dropped <= 0) return Result(kept.toList(), 0, false, used)

        val note = Turn(
            "system",
            "[Earlier conversation: $dropped message(s) compacted to fit this model's context window.]"
        )
        val out = ArrayList<Turn>(kept.size + 1)
        out.add(note)
        out.addAll(kept)
        return Result(out, dropped, true, used)
    }
}
