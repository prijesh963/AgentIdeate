package dev.agentideate.tools;

import com.fasterxml.jackson.databind.JsonNode;

/** Typed access to a tool call's arguments. */
public final class ToolArgs {

    private final JsonNode node;

    public ToolArgs(JsonNode node) {
        this.node = node;
    }

    /** The raw arguments, for tools that take structured input. */
    public JsonNode node() {
        return node;
    }

    public String string(String name) {
        JsonNode v = node == null ? null : node.get(name);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.isTextual() ? v.asText() : v.toString();
        return s.isBlank() ? null : s.trim();
    }

    public String required(String name) throws ToolException {
        String v = string(name);
        if (v == null) {
            throw new ToolException("Missing required argument '" + name + "'.");
        }
        return v;
    }

    public int integer(String name, int fallback, int min, int max) {
        JsonNode v = node == null ? null : node.get(name);
        int n = fallback;
        if (v != null && v.canConvertToInt()) {
            n = v.asInt();
        } else if (v != null && v.isTextual()) {
            try {
                n = Integer.parseInt(v.asText().trim());
            } catch (NumberFormatException ignored) {
                // keep fallback
            }
        }
        return Math.max(min, Math.min(max, n));
    }
}
