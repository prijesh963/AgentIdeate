package dev.agentideate.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.agentideate.Version;
import dev.agentideate.tools.Tool;
import dev.agentideate.tools.ToolRegistry;
import dev.agentideate.workspace.WorkspaceService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A Model Context Protocol server over stdio: newline-delimited JSON-RPC 2.0 on stdin/stdout.
 *
 * <p>Hand-written rather than built on an SDK: the protocol surface used here is small (initialize,
 * tools, ping, roots), and every dependency in the jar is one more thing a security review has to
 * clear. Nothing but protocol messages is ever written to stdout; diagnostics go to stderr.
 *
 * <p>After the client confirms initialization, the server asks it for its roots (the open project
 * folders) when the client says it supports them, so no folder has to be configured by hand.
 */
public final class McpServer {

    static final List<String> SUPPORTED_VERSIONS = List.of("2025-06-18", "2025-03-26", "2024-11-05");

    static final String INSTRUCTIONS = "agentideate indexes this Java/Spring workspace locally. Before opening "
            + "files to learn structure or trace a flow, call repo_overview, locate or endpoint_flow: "
            + "their answers carry file:line and cost far fewer tokens than reading the files.";

    private static final String ROOTS_REQUEST_ID = "agentideate-roots";

    private final ObjectMapper json = new ObjectMapper();
    private final ToolRegistry tools;
    private final WorkspaceService workspaces;
    private final BufferedReader in;
    private final PrintStream out;
    private final PrintStream log;
    private boolean clientSupportsRoots;
    private int rootsRequests;

    public McpServer(ToolRegistry tools, WorkspaceService workspaces, InputStream in, OutputStream out, PrintStream log) {
        this.tools = tools;
        this.workspaces = workspaces;
        this.in = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.out = new PrintStream(out, true, StandardCharsets.UTF_8);
        this.log = log;
    }

    /** Serves until stdin closes. */
    public void run() throws IOException {
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode msg;
            try {
                msg = json.readTree(line);
            } catch (IOException e) {
                send(error(null, -32700, "Parse error"));
                continue;
            }
            try {
                handle(msg);
            } catch (RuntimeException e) {
                log.println("agentideate: internal error: " + e);
                if (msg.hasNonNull("id") && msg.has("method")) {
                    send(error(msg.get("id"), -32603, "Internal error: " + e.getMessage()));
                }
            }
        }
    }

    void handle(JsonNode msg) {
        if (msg.isArray()) {
            msg.forEach(this::handle); // a JSON-RPC batch from an older client
            return;
        }
        JsonNode id = msg.get("id");
        if (!msg.has("method")) {
            if (id != null && ROOTS_REQUEST_ID.equals(id.asText())) {
                onRootsResponse(msg);
            }
            return; // a response to something we did not ask, ignored
        }
        String method = msg.get("method").asText();
        JsonNode params = msg.get("params");
        boolean notification = id == null || id.isNull();
        switch (method) {
            case "initialize" -> send(result(id, initialize(params)));
            case "notifications/initialized", "notifications/roots/list_changed" -> requestRoots();
            case "ping" -> send(result(id, json.createObjectNode()));
            case "tools/list" -> send(result(id, listTools()));
            case "tools/call" -> send(result(id, callTool(params)));
            default -> {
                if (!notification) {
                    send(error(id, -32601, "Method not found: " + method));
                }
            }
        }
    }

    private ObjectNode initialize(JsonNode params) {
        String requested = params != null && params.hasNonNull("protocolVersion")
                ? params.get("protocolVersion").asText() : SUPPORTED_VERSIONS.get(0);
        clientSupportsRoots = params != null && params.path("capabilities").has("roots");
        ObjectNode r = json.createObjectNode();
        r.put("protocolVersion", SUPPORTED_VERSIONS.contains(requested) ? requested : SUPPORTED_VERSIONS.get(0));
        r.putObject("capabilities").putObject("tools").put("listChanged", false);
        r.putObject("serverInfo").put("name", "agentideate").put("version", Version.current());
        r.put("instructions", INSTRUCTIONS);
        return r;
    }

    private void requestRoots() {
        if (!clientSupportsRoots) {
            return;
        }
        rootsRequests++;
        ObjectNode req = json.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", ROOTS_REQUEST_ID);
        req.put("method", "roots/list");
        send(req);
    }

    private void onRootsResponse(JsonNode msg) {
        JsonNode roots = msg.path("result").path("roots");
        if (!roots.isArray()) {
            return;
        }
        List<Path> paths = new ArrayList<>();
        for (JsonNode r : roots) {
            String uri = r.path("uri").asText("");
            if (uri.startsWith("file:")) {
                try {
                    paths.add(Path.of(URI.create(uri)));
                } catch (IllegalArgumentException e) {
                    log.println("agentideate: ignoring root " + uri + ": " + e.getMessage());
                }
            }
        }
        workspaces.setClientRoots(paths);
        log.println("agentideate: client roots " + paths);
    }

    private ObjectNode listTools() {
        ObjectNode r = json.createObjectNode();
        ArrayNode arr = r.putArray("tools");
        for (Tool t : tools.tools()) {
            ObjectNode o = arr.addObject();
            o.put("name", t.name());
            o.put("description", t.description());
            o.set("inputSchema", tools.schema(t));
            ObjectNode annotations = o.putObject("annotations");
            annotations.put("readOnlyHint", READ_ONLY.contains(t.name()));
        }
        return r;
    }

    /** Every M1 tool only reads. Tools that write (implementing a plan) will not be listed here. */
    private static final Set<String> READ_ONLY = Set.of("repo_overview", "locate", "endpoint_flow");

    private ObjectNode callTool(JsonNode params) {
        String name = params == null ? "" : params.path("name").asText("");
        JsonNode arguments = params == null ? null : params.get("arguments");
        ToolRegistry.Outcome outcome = tools.call(name, arguments);
        ObjectNode r = json.createObjectNode();
        r.putArray("content").addObject().put("type", "text").put("text", outcome.text());
        r.put("isError", outcome.error());
        return r;
    }

    int rootsRequests() {
        return rootsRequests;
    }

    private ObjectNode result(JsonNode id, JsonNode result) {
        ObjectNode r = json.createObjectNode();
        r.put("jsonrpc", "2.0");
        r.set("id", id);
        r.set("result", result);
        return r;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode r = json.createObjectNode();
        r.put("jsonrpc", "2.0");
        r.set("id", id);
        r.putObject("error").put("code", code).put("message", message);
        return r;
    }

    private synchronized void send(ObjectNode msg) {
        try {
            out.println(json.writeValueAsString(msg));
        } catch (IOException e) {
            log.println("agentideate: could not write response: " + e);
        }
    }
}
