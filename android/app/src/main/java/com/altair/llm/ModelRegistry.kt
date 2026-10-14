package com.altair.llm

/**
 * §7 Model registry + auto router.
 *
 * The registry turns the live model library (local GGUFs, LAN peers, cloud
 * providers) into [ModelProfile]s. The router picks the cheapest tier that can
 * actually do the job: on-device first, then LAN peers, then cloud.
 */
object ModelRegistry {

    /** Profile for an on-device (or LAN-hosted) local GGUF. */
    fun forLocal(
        entry: ModelRepository.Entry,
        caps: ModelCapabilities,
        contextTokens: Int,
        speedTier: Int = 2,
        tier: ModelProfile.Tier = ModelProfile.Tier.LOCAL_DEVICE
    ): ModelProfile = ModelProfile(
        id = entry.id,
        label = entry.name,
        tier = tier,
        contextTokens = contextTokens,
        supportsVision = caps.vision,
        supportsAudio = caps.audio,
        supportsTools = caps.tools,
        reasoning = caps.reasoning,
        costPerMTokUsd = 0.0,
        speedTier = speedTier
    )

    /** Profile for a BYOK cloud model. */
    fun forCloud(
        providerId: String,
        modelId: String,
        contextTokens: Int = 128_000,
        vision: Boolean = true,
        tools: Boolean = true,
        reasoning: Boolean = true,
        costPerMTokUsd: Double = 0.0
    ): ModelProfile = ModelProfile(
        id = "cloud:$providerId:$modelId",
        label = modelId,
        tier = ModelProfile.Tier.CLOUD,
        contextTokens = contextTokens,
        supportsVision = vision,
        supportsAudio = false,
        supportsTools = tools,
        reasoning = reasoning,
        costPerMTokUsd = costPerMTokUsd,
        speedTier = 3
    )

    /** What a task needs from a model. */
    data class Requirement(
        val needsVision: Boolean = false,
        val needsAudio: Boolean = false,
        val needsTools: Boolean = false,
        val needsReasoning: Boolean = false,
        val minContext: Int = 0,
        val preferLocal: Boolean = true
    )

    /**
     * Auto route: the cheapest capable tier, honouring capability gates.
     * Local device → LAN peer → cheapest cloud that qualifies.
     */
    fun route(profiles: List<ModelProfile>, req: Requirement): ModelProfile? {
        val capable = profiles.filter { p ->
            (!req.needsVision || p.supportsVision) &&
                (!req.needsAudio || p.supportsAudio) &&
                (!req.needsTools || p.supportsTools) &&
                (!req.needsReasoning || p.reasoning) &&
                p.contextTokens >= req.minContext
        }
        if (capable.isEmpty()) return null
        if (req.preferLocal) {
            capable.firstOrNull { it.tier == ModelProfile.Tier.LOCAL_DEVICE }?.let { return it }
            capable.firstOrNull { it.tier == ModelProfile.Tier.LOCAL_LAN }?.let { return it }
        }
        return capable.filter { it.tier == ModelProfile.Tier.CLOUD }.minByOrNull { it.costPerMTokUsd }
            ?: capable.first()
    }

    /** Human-readable summary for the Settings / Diagnostics surfaces. */
    fun describe(p: ModelProfile): String = buildString {
        append(p.tier.name.lowercase().replace('_', ' '))
        append(" · ctx ").append(p.contextTokens)
        val mods = buildList {
            if (p.supportsVision) add("vision")
            if (p.supportsAudio) add("audio")
            if (p.supportsTools) add("tools")
            if (p.reasoning) add("thinking")
        }
        if (mods.isNotEmpty()) append(" · ").append(mods.joinToString("/"))
        if (p.tier == ModelProfile.Tier.CLOUD) append(" · $").append(p.costPerMTokUsd).append("/Mtok")
    }
}
