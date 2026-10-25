package dev.agentideate.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.agentideate.graph.CodeGraph;
import dev.agentideate.workspace.WorkspaceService;

/**
 * One capability, served identically over MCP and the command line. A tool reads the graph and
 * writes its answer; it never calls a model.
 */
public interface Tool {

    String name();

    /** Sent to the agent with every request, so it is kept short: it is paid for in tokens each turn. */
    String description();

    /** JSON Schema for the arguments, excluding {@code repo}, which the registry adds to every tool. */
    ObjectNode inputSchema();

    /** Whether the tool only reads. Every tool so far does; a tool that writes must say so. */
    default boolean readOnly() {
        return true;
    }

    /**
     * Whether answers belong to a conversation thread (continue a topic, don't repeat what was
     * given). Workspace-wide summaries and log digests are not about a topic, so they are not.
     */
    default boolean threaded() {
        return true;
    }

    /** The answer's size cap; a tool that returns code may need more than the default. */
    default int budgetChars() {
        return ToolRegistry.BUDGET_CHARS;
    }

    void run(ToolArgs args, WorkspaceService.Snapshot snapshot, CodeGraph graph, Answer out) throws ToolException;
}
