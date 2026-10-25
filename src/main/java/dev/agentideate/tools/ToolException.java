package dev.agentideate.tools;

/** A problem with the request itself (a missing argument, nothing matching); shown to the agent as is. */
public final class ToolException extends Exception {
    public ToolException(String message) {
        super(message);
    }
}
