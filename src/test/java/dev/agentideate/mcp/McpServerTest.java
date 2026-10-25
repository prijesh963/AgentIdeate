package dev.agentideate.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentideate.AppHome;
import dev.agentideate.TestSupport;
import dev.agentideate.tools.ToolRegistry;
import dev.agentideate.workspace.WorkspaceService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpServerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path home;

    WorkspaceService workspaces;

    /** Feeds the given lines to a server and returns every message it wrote. */
    private List<JsonNode> session(List<Path> repos, String... lines) throws Exception {
        workspaces = new WorkspaceService(new AppHome(home), repos, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        String input = String.join("\n", lines) + "\n";
        new McpServer(ToolRegistry.standard(workspaces), workspaces,
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), out,
                new PrintStream(err, true, StandardCharsets.UTF_8)).run();
        List<JsonNode> msgs = new ArrayList<>();
        for (String l : out.toString(StandardCharsets.UTF_8).split("\n")) {
            if (!l.isBlank()) {
                msgs.add(JSON.readTree(l));
            }
        }
        return msgs;
    }

    private static final String INIT = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""";

    @Test
    void initializeNegotiatesTheClientsVersionAndAdvertisesTools() throws Exception {
        JsonNode r = session(List.of(), INIT).get(0);
        assertEquals(1, r.get("id").asInt());
        assertEquals("2025-03-26", r.at("/result/protocolVersion").asText());
        assertTrue(r.at("/result/capabilities/tools").isObject());
        assertEquals("agentideate", r.at("/result/serverInfo/name").asText());
        assertTrue(r.at("/result/instructions").asText().contains("repo_overview"));
    }

    @Test
    void anUnknownProtocolVersionGetsTheLatestSupported() throws Exception {
        JsonNode r = session(List.of(), """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"1999-01-01","capabilities":{}}}""").get(0);
        assertEquals(McpServer.SUPPORTED_VERSIONS.get(0), r.at("/result/protocolVersion").asText());
    }

    @Test
    void toolsListHasEveryToolWithSchemaAndRepoArgument() throws Exception {
        JsonNode r = session(List.of(), INIT, """
                {"jsonrpc":"2.0","id":2,"method":"tools/list"}""").get(1);
        JsonNode tools = r.at("/result/tools");
        assertEquals(17, tools.size());
        for (JsonNode t : tools) {
            boolean threaded = !List.of("repo_overview", "compress_log", "thread_recall", "plan_submit", "plan_show", "plan_approve",
                    "plan_implement", "plan_rollback", "plan_review", "test_pack", "test_run").contains(t.get("name").asText());
            assertEquals(threaded, t.at("/inputSchema/properties/thread").isObject(), t.get("name").asText());
        }
        for (JsonNode t : tools) {
            assertEquals("object", t.at("/inputSchema/type").asText());
            assertTrue(t.at("/inputSchema/properties/repo").isObject(), t.toString());
            // the tools that approve, write or run builds are not read-only: that is what makes a client without forms confirm them
            boolean acts = List.of("plan_approve", "plan_implement", "plan_rollback", "test_run").contains(t.get("name").asText());
            assertEquals(!acts, t.at("/annotations/readOnlyHint").asBoolean(), t.toString());
            assertTrue(t.get("description").asText().length() < 220, "descriptions are paid for every turn: " + t);
        }
        assertEquals("query", tools.get(1).at("/inputSchema/required/0").asText());
    }

    @Test
    void toolsCallReturnsTextContent() throws Exception {
        JsonNode r = session(List.of(TestSupport.shop()), INIT, """
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"locate","arguments":{"query":"PriceService"}}}""").get(1);
        assertFalse(r.at("/result/isError").asBoolean());
        assertEquals("text", r.at("/result/content/0/type").asText());
        assertTrue(r.at("/result/content/0/text").asText().contains("PriceService [service]"));
    }

    @Test
    void aToolErrorIsAResultWithIsErrorNotAProtocolError() throws Exception {
        JsonNode r = session(List.of(TestSupport.shop()), INIT, """
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"locate","arguments":{}}}""").get(1);
        assertTrue(r.at("/result/isError").asBoolean());
        assertTrue(r.at("/result/content/0/text").asText().contains("query"));
    }

    @Test
    void unknownMethodsErrorAndNotificationsGetNoReply() throws Exception {
        List<JsonNode> msgs = session(List.of(), INIT,
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/whatever\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"resources/list\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"ping\"}");
        assertEquals(3, msgs.size());
        assertEquals(-32601, msgs.get(1).at("/error/code").asInt());
        assertEquals(10, msgs.get(2).get("id").asInt());
        assertTrue(msgs.get(2).get("result").isObject());
    }

    @Test
    void malformedJsonIsAParseErrorAndTheServerKeepsGoing() throws Exception {
        List<JsonNode> msgs = session(List.of(), "{nope", "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}");
        assertEquals(-32700, msgs.get(0).at("/error/code").asInt());
        assertEquals(2, msgs.get(1).get("id").asInt());
    }

    @Test
    void theOpenProjectComesFromTheClientsRoots() throws Exception {
        String uri = TestSupport.shop().toUri().toString();
        List<JsonNode> msgs = session(List.of(), """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{"roots":{}}}}""",
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":\"agentideate-roots\",\"result\":{\"roots\":[{\"uri\":\"" + uri + "\"}]}}",
                """
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"repo_overview","arguments":{}}}""");
        assertEquals("roots/list", msgs.get(1).get("method").asText(), "server asks for roots after initialized");
        assertEquals(List.of(TestSupport.shop().toRealPath()), workspaces.clientRoots().stream().map(p -> {
            try {
                return p.toRealPath();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).toList());
        assertTrue(msgs.get(2).at("/result/content/0/text").asText().contains("4 Spring Boot services"));
    }

    @Test
    void noRootsRequestWhenTheClientDoesNotSupportThem() throws Exception {
        List<JsonNode> msgs = session(List.of(), INIT, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        assertEquals(1, msgs.size());
    }

    @Test
    void aPlanIsApprovedThroughAFormTheClientShowsInTheChat(@TempDir Path tmp) throws Exception {
        Path repo = TestSupport.copyTree(TestSupport.shop(), tmp.resolve("shop"));
        workspaces = new WorkspaceService(new AppHome(home), List.of(repo), null);
        java.io.PipedOutputStream toServer = new java.io.PipedOutputStream();
        java.io.PipedInputStream serverIn = new java.io.PipedInputStream(toServer, 1 << 16);
        java.io.PipedInputStream fromServer = new java.io.PipedInputStream(1 << 16);
        java.io.PipedOutputStream serverOut = new java.io.PipedOutputStream(fromServer);
        McpServer server = new McpServer(ToolRegistry.standard(workspaces), workspaces, serverIn, serverOut,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        Thread t = new Thread(() -> {
            try {
                server.run();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        t.start();
        java.io.BufferedReader replies = new java.io.BufferedReader(new java.io.InputStreamReader(fromServer, StandardCharsets.UTF_8));
        java.util.function.Consumer<String> say = line -> {
            try {
                toServer.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                toServer.flush();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        say.accept("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"capabilities\":{\"elicitation\":{}},\"clientInfo\":{\"name\":\"copilot-sim\",\"version\":\"9\"}}}");
        assertEquals(1, JSON.readTree(replies.readLine()).get("id").asInt());
        ObjectNode submit = JSON.createObjectNode();
        submit.put("jsonrpc", "2.0").put("id", 2).put("method", "tools/call");
        ObjectNode args = submit.putObject("params").put("name", "plan_submit").putObject("arguments");
        args.put("title", "Discount").put("understanding", "10% off");
        args.putArray("changes").addObject().put("kind", "edit")
                .put("file", "order-service/src/main/java/com/shop/order/service/OrderServiceImpl.java")
                .put("before", "        order.setTotal(total(price.amount(), request.quantity()));")
                .put("after", "        order.setTotal(total(price.amount(), request.quantity()).multiply(new java.math.BigDecimal(\"0.9\")));");
        say.accept(JSON.writeValueAsString(submit));
        JsonNode submitted = JSON.readTree(replies.readLine());
        assertTrue(submitted.at("/result/content/0/text").asText().contains("valid"), submitted.toString());

        say.accept("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"plan_approve\",\"arguments\":{\"plan\":\"p1\"}}}");
        JsonNode form = JSON.readTree(replies.readLine());
        assertEquals("elicitation/create", form.get("method").asText(), "the server asks the client to show a form");
        assertTrue(form.at("/params/message").asText().startsWith("Approve plan p1 v1"), form.toString());
        // the developer clicks Approve in the chat; the answer goes to the server, not to the model
        say.accept("{\"jsonrpc\":\"2.0\",\"id\":" + JSON.writeValueAsString(form.get("id"))
                + ",\"result\":{\"action\":\"accept\",\"content\":{\"decision\":\"approve\"}}}");
        JsonNode approved = JSON.readTree(replies.readLine());
        assertEquals(3, approved.get("id").asInt());
        assertTrue(approved.at("/result/content/0/text").asText().startsWith("Approved by the developer in the chat: p1 v1."), approved.toString());
        toServer.close();
        t.join(10_000);

        JsonNode clientInfo = JSON.readTree(home.resolve("client.json").toFile());
        assertEquals("copilot-sim", clientInfo.get("name").asText());
        assertTrue(clientInfo.get("elicitation").asBoolean());
    }
}
