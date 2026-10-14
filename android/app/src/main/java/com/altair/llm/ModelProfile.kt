package com.altair.llm

/**
 * §7 Capability profile — what a model can *actually* do.
 *
 * Restrictions attach to this profile, never to "local vs cloud" as a category:
 * a 70B GGUF served from a PC over LAN can be as unrestricted as a cloud model,
 * and a tiny on-device model gets phone-appropriate treatment automatically.
 *
 * The profile drives the context budgeter, retrieval depth, tool-schema
 * trimming, reasoning depth and the auto router.
 */
data class ModelProfile(
    val id: String,
    val label: String,
    val tier: Tier,
    /** Real context window in tokens for this model as configured. */
    val contextTokens: Int,
    val supportsVision: Boolean = false,
    val supportsAudio: Boolean = false,
    val supportsTools: Boolean = false,
    val reasoning: Boolean = false,
    /** USD per million tokens; 0 for local/LAN. */
    val costPerMTokUsd: Double = 0.0,
    /** 0 = slow ... 3 = fast (local models refine this from measured tok/s). */
    val speedTier: Int = 2
) {
    enum class Tier { LOCAL_DEVICE, LOCAL_LAN, CLOUD }

    /** How many recent turns may stay raw before older ones are compacted. */
    val rawTurnBudget: Int
        get() = when {
            contextTokens >= 32768 -> 40
            contextTokens >= 16384 -> 24
            contextTokens >= 8192 -> 16
            contextTokens >= 4096 -> 10
            else -> 6
        }

    /** How many long-term memory snippets to retrieve for a request. */
    val retrievalDepth: Int
        get() = when {
            contextTokens >= 32768 -> 12
            contextTokens >= 8192 -> 6
            else -> 3
        }

    /** Answer space to reserve when budgeting the prompt. */
    val reserveTokens: Int
        get() = when {
            contextTokens >= 8192 -> 1024
            contextTokens >= 4096 -> 768
            else -> 512
        }
}
