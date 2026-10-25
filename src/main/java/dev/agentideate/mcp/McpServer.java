package dev.agentideate.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.agentideate.Version;
import dev.agentideate.tools.ClientChannel;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A Model Context Protocol server over stdio: newline-delimited JSON-RPC 2.0 on stdin/stdout.
 *
 * <p>Hand-written rather than built on an SDK: the protocol surface used here is small (initialize,
 * tools, ping, roots, elicitation), and every dependency in the jar is one more thing a security
 * review has to clear. Nothing but protocol messages is ever written to stdout; diagnostics go to
 * stderr.
 *
 * <p>The reader thread only reads: requests run one at a time on a worker thread, so a tool can
 * ask the client something (an approval form in the chat) and wait for the answer while the reader
 * keeps receiving it. After initialization the server asks the client for its roots (the open
 * project folders) when the client supports them, so no folder has to be configured by hand.
 */
public final class McpServer implements ClientChannel {

    static final List<String> SUPPORTED_VERSIONS = List.of("2025-06-18", "2025-03-26", "2024-11-05");

    static final String INSTRUCTIONS = "agentideate indexes this Java/Spring workspace locally. Before opening "
            + "files to learn structure, trace a flow or size a change, call repo_overview, locate, endpoint_flow, "
            + "messaging_map, data_map, impact_of or slice_for_task: their answers carry file:line and cost far fewer "
            + "tokens than reading the files. To change code, first submit a plan with plan_submit; the developer "
            + "approves it in the chat before anything is written.";

    private static final String ROOTS_REQUEST_ID = "agentideate-roots";

    private final ObjectMapper json = new ObjectMapper();
    private final ToolRegistry tools;
    private final WorkspaceService workspaces;
    private final BufferedReader in;
    private final PrintStream out;
    private final PrintStream log;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "agentideate-worker");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicInteger requestIds = new AtomicInteger();
    private volatile boolean clientSupportsRoots;
    private volatile boolean clientSupportsElicitation;
    private volatile boolean closed;
    private int rootsRequests;

    public McpServer(ToolRegistry tools, WorkspaceService workspaces, InputStream in, OutputStream out, PrintStream log) {
        this.tools = tools;
        this.workspaces = workspaces;
        this.in = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.out = new PrintStream(out, true, StandardCharsets.UTF_8);
        this.log = log;
        tools.client(this);
    }

    /** Serves until stdin closes, then finishes the requests already received. */
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
            dispatch(msg);
        }
        closed = true;
        pending.values().forEach(f -> f.complete(null)); // nobody will answer now
        worker.shutdown();
        try {
            if (!worker.awaitTermination(1, TimeUnit.MINUTES)) {
                log.println("agentideate: requests still running at exit were abandoned");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void dispatch(JsonNode msg) {
        if (msg.isArray()) {
            msg.forEach(this::dispatch); // a JSON-RPC batch from an older client
            return;
        }
        if (!msg.has("method")) {
            JsonNode id = msg.get("id");
            CompletableFuture<JsonNode> f = id == null ? null : pending.remove(id.asText());
            if (f != null) {
                f.complete(msg); // a response to something this server asked
            } else if (id != null && ROOTS_REQUEST_ID.equals(id.asText())) {
                onRootsResponse(msg); // roots are safe to apply whenever they arrive, even ahead of the worker
            }
            return;
        }
        worker.submit(() -> {
            try {
                handle(msg);
            } catch (RuntimeException e) {
                log.println("agentideate: internal error: " + e);
                if (msg.hasNonNull("id")) {
                    send(error(msg.get("id"), -32603, "Internal error: " + e.getMessage()));
                }
            }
        });
    }

    void handle(JsonNode msg) {
        JsonNode id = msg.get("id");
        String method = msg.get("method").asText();
        JsonNode params = msg.get("params");
        boolean notification = id == null || id.isNull();
        switch (method) {
            case "initialize" -> send(result(id, initialize(params)));
            case "notifications/initialized", "notifications/roots/list_changed" -> requestRoots();
            case "ping" -> send(result(id, json.createObjectNode()));
            case "tools/list" -> send(result(id, tools.toolsList()));
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
        JsonNode caps = params == null ? json.createObjectNode() : params.path("capabilities");
        clientSupportsRoots = caps.has("roots");
        clientSupportsElicitation = caps.has("elicitation");
        rememberClient(params);
        ObjectNode r = json.createObjectNode();
        r.put("protocolVersion", SUPPORTED_VERSIONS.contains(requested) ? requested : SUPPORTED_VERSIONS.get(0));
        r.putObject("capabilities").putObject("tools").put("listChanged", false);
        r.putObject("serverInfo").put("name", "agentideate").put("version", Version.current());
        r.put("instructions", INSTRUCTIONS);
        return r;
    }

    /**
     * Notes which client connected and what it supports in {@code client.json}, so {@code setup}
     * can tell a developer whether their Copilot plugin offers in-chat approval forms.
     */
    private void rememberClient(JsonNode params) {
        try {
            ObjectNode c = json.createObjectNode();
            c.put("name", params == null ? "?" : params.path("clientInfo").path("name").asText("?"));
            c.put("version", params == null ? "?" : params.path("clientInfo").path("version").asText("?"));
            c.put("elicitation", clientSupportsElicitation);
            c.put("roots", clientSupportsRoots);
            c.put("seen", Instant.now().toString());
            Path file = workspaces.home().dir().resolve("client.json");
            Files.createDirectories(file.getParent());
            Files.writeString(file, json.writerWithDefaultPrettyPrinter().writeValueAsString(c));
        } catch (IOException | RuntimeException e) {
            log.println("agentideate: could not record client info: " + e.getMessage());
        }
        log.println("agentideate: client supports in-chat forms (elicitation): " + clientSupportsElicitation);
    }

    private void requestRoots() {
        if (!clientSupportsRoots) {
            return;
        }
        rootsRequests++;
        CompletableFuture<JsonNode> f = new CompletableFuture<>();
        pending.put(ROOTS_REQUEST_ID, f);
        f.thenAccept(msg -> {
            if (msg != null) {
                onRootsResponse(msg);
            }
        });
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

    @Override
    public boolean supportsElicitation() {
        return clientSupportsElicitation && !closed;
    }

    @Override
    public JsonNode elicit(String message, ObjectNode requestedSchema, Duration timeout) throws InterruptedException {
        if (!supportsElicitation()) {
            return null;
        }
        String id = "agentideate-" + requestIds.incrementAndGet();
        CompletableFuture<JsonNode> f = new CompletableFuture<>();
        pending.put(id, f);
        ObjectNode req = json.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", id);
        req.put("method", "elicitation/create");
        ObjectNode p = req.putObject("params");
        p.put("message", message);
        p.set("requestedSchema", requestedSchema);
        send(req);
        try {
            JsonNode response = f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return response == null || !response.has("result") ? null : response.get("result");
        } catch (TimeoutException | ExecutionException e) {
            return null;
        } finally {
            pending.remove(id);
        }
    }

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
