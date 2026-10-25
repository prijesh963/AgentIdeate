package dev.agentideate.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;

/**
 * The way back to the MCP client (Copilot) while a tool runs: asking the developer something
 * through a form the client draws in the chat. Absent when agentideate runs from the terminal.
 */
public interface ClientChannel {

    /** Whether the client declared MCP elicitation, i.e. can show a form in the chat. */
    boolean supportsElicitation();

    /**
     * Shows the developer a form ({@code elicitation/create}) and waits for their answer. The
     * answer goes from the client to agentideate directly; the model never sees or produces it.
     *
     * @return the result ({@code action}: accept / decline / cancel, and {@code content}), or
     *         {@code null} if no answer came within {@code timeout} or the client went away
     */
    JsonNode elicit(String message, ObjectNode requestedSchema, Duration timeout) throws InterruptedException;
}
