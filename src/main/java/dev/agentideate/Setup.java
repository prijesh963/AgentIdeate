package dev.agentideate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code java -jar agentideate.jar setup}: checks the machine and prints the exact {@code mcp.json}
 * entry for this jar and this Java, ready to paste into Copilot's MCP settings. It writes nothing:
 * the developer stays in control of their editor configuration.
 */
final class Setup {

    static final int REQUIRED_JAVA = 21;

    private Setup() {
    }

    /** @return 0 when every check passed */
    static int run(AppHome home, PrintStream out) {
        boolean ok = true;
        out.println("agentideate " + Version.current() + " setup");
        out.println();

        int feature = Runtime.version().feature();
        String java = javaCommand();
        boolean javaOk = feature >= REQUIRED_JAVA;
        ok &= javaOk;
        out.println("  Java     " + Runtime.version() + "  " + java + (javaOk ? "  OK" : "  TOO OLD: needs " + REQUIRED_JAVA + " or later"));

        Path jar = jarPath();
        boolean jarOk = jar != null && jar.toString().endsWith(".jar");
        ok &= jarOk;
        out.println("  Jar      " + (jarOk ? jar + "  OK" : "not running from agentideate.jar (run setup with java -jar <path>/agentideate.jar)"));

        boolean homeOk;
        try {
            Files.createDirectories(home.dir());
            Path probe = Files.createTempFile(home.dir(), "probe", ".tmp");
            Files.delete(probe);
            homeOk = true;
        } catch (IOException e) {
            homeOk = false;
        }
        ok &= homeOk;
        out.println("  State    " + home.dir() + (homeOk ? "  writable  OK" : "  NOT WRITABLE: set AGENTIDEATE_HOME to a writable folder"));
        out.println();

        ObjectNode root = new ObjectMapper().createObjectNode();
        ObjectNode server = root.putObject("servers").putObject("agentideate");
        server.put("type", "stdio");
        server.put("command", java);
        server.putArray("args").add("-jar").add(jarOk ? jar.toString() : "<path to agentideate.jar>");
        String snippet;
        try {
            snippet = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (IOException e) {
            snippet = root.toString();
        }
        out.println("Add this to Copilot's MCP configuration in IntelliJ (Settings > Tools > GitHub Copilot >");
        out.println("Model Context Protocol > Configure, or the tools icon in Copilot Chat's agent mode):");
        out.println();
        out.println(snippet);
        out.println();
        out.println("If mcp.json already has a \"servers\" block, add only the \"agentideate\" entry to it.");
        out.println("Then restart IntelliJ and switch Copilot Chat to Agent mode; its tools list shows agentideate.");
        return ok ? 0 : 1;
    }

    /** The Java running now: the same one will work from mcp.json. */
    static String javaCommand() {
        return ProcessHandle.current().info().command()
                .orElse(Path.of(System.getProperty("java.home"), "bin",
                        System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java").toString());
    }

    static Path jarPath() {
        try {
            var source = Setup.class.getProtectionDomain().getCodeSource();
            return source == null ? null : Path.of(source.getLocation().toURI()).toAbsolutePath();
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }
}
