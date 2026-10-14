package com.altair.llm

/**
 * Phase 4.5 — agent roles.
 *
 * A role is a template: system prompt + which model it needs (policy) + which
 * tools it may use. The orchestrator spawns subagents from roles; each subagent
 * gets its OWN small context (task brief + relevant memory), never the whole
 * transcript, so multi-agent work never blows a phone's context window.
 */
data class ModelPolicy(
    val preferLocal: Boolean = true,
    val needsVision: Boolean = false,
    val needsAudio: Boolean = false,
    val needsTools: Boolean = false,
    val needsReasoning: Boolean = false,
    /** Optional explicit cloud target; null = route automatically. */
    val cloudProviderId: String? = null,
    val cloudModel: String? = null
)

data class AgentRole(
    val id: String,
    val name: String,
    val systemPrompt: String,
    val policy: ModelPolicy = ModelPolicy(),
    val toolNames: List<String> = emptyList()
)

object AgentRoles {

    val ORCHESTRATOR = AgentRole(
        id = "orchestrator",
        name = "Orchestrator",
        systemPrompt = "You are the orchestrator. Break the task into the smallest " +
            "set of concrete subtasks, decide which specialist role handles each, and " +
            "state the plan as a numbered list. Be terse."
    )

    val PLANNER = AgentRole(
        id = "planner",
        name = "Planner",
        systemPrompt = "You are a planner. Produce a precise, ordered plan with " +
            "explicit deliverables. No prose beyond the plan."
    )

    val BUILDER = AgentRole(
        id = "builder",
        name = "Builder",
        systemPrompt = "You are a builder. Carry out the assigned subtask and return " +
            "only the produced work (text, code, or file content)."
    )

    val RESEARCHER = AgentRole(
        id = "researcher",
        name = "Researcher",
        systemPrompt = "You are a researcher. Gather facts with the available tools " +
            "(web_search, fetch_url) and return a compact, sourced summary.",
        policy = ModelPolicy(needsTools = true)
    )

    val WRITER = AgentRole(
        id = "writer",
        name = "Writer",
        systemPrompt = "You are a writer. Turn the provided material into clear, " +
            "well-structured prose for the requested format."
    )

    val REVIEWER = AgentRole(
        id = "reviewer",
        name = "Reviewer",
        systemPrompt = "You are a reviewer. Find errors, gaps and improvements in the " +
            "given work. Be specific and concise. If it is correct, say so."
    )

    val ARTIST = AgentRole(
        id = "artist",
        name = "Artist",
        systemPrompt = "You are an art director. Write a precise image/video generation " +
            "prompt (subject, style, lighting, composition, negatives) for each asset.",
        policy = ModelPolicy(needsVision = false)
    )

    val COMPOSER = AgentRole(
        id = "composer",
        name = "Composer",
        systemPrompt = "You are an editor/compositor. Specify exactly how the produced " +
            "assets are assembled (timeline, ordering, timing, effects) as a deterministic recipe."
    )

    val builtins: List<AgentRole> = listOf(ORCHESTRATOR, PLANNER, BUILDER, RESEARCHER, WRITER, REVIEWER, ARTIST, COMPOSER)

    fun byId(id: String): AgentRole? = builtins.firstOrNull { it.id == id }
}
