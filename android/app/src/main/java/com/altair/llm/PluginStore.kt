package com.altair.llm

import android.content.Context

/**
 * Phase 4 — plugins.
 *
 * A plugin is currently a bundle that ships MCP server configs (and later,
 * agent-role templates). Imported manifests land in [McpClient], so plugin tools
 * show up in the same registry as built-ins and MCP servers.
 *
 * Later phases add `agents`/`tools` entries to the manifest and register them
 * here without changing callers.
 */
object PluginStore {

    fun import(ctx: Context, manifestJson: String): Int = McpClient.importPlugins(ctx, manifestJson)

    fun list(ctx: Context): List<McpServer> = McpClient.servers(ctx)

    fun remove(ctx: Context, name: String) = McpClient.removeServer(ctx, name)
}
