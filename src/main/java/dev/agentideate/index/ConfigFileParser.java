package dev.agentideate.index;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the non-Java files whose contents matter to wiring: Spring config files into flat
 * {@code a.b.c -> value} keys, and migrations into the tables they create or alter.
 *
 * <p>The YAML reader is deliberately a subset, written here instead of adding a YAML library to
 * the jar: nested maps, scalars, quoted values, comments, lists of scalars and of maps, flow lists
 * ({@code [a, b]}), block scalars and {@code ---} documents. That covers Spring Boot config as it
 * is normally written; anchors, aliases and flow maps are not interpreted (see KNOWN_LIMITATIONS).
 */
public final class ConfigFileParser {

    private static final Pattern SQL_TABLE = Pattern.compile(
            "(?i)\\b(?:create|alter)\\s+table\\s+(?:if\\s+(?:not\\s+)?exists\\s+)?((?:[`\"\\[]?\\w+[`\"\\]]?\\.)*[`\"\\[]?\\w+[`\"\\]]?)");
    private static final Pattern LIQUIBASE_TABLE = Pattern.compile(
            "(?i)<(?:createTable|addColumn|dropColumn|renameColumn|modifyDataType|addForeignKeyConstraint|createIndex)\\b[^>]*?\\b(?:baseTableName|tableName)\\s*=\\s*\"([\\w.]+)\"");

    private ConfigFileParser() {
    }

    /** Spring config files: {@code application*.yml|yaml|properties} and {@code bootstrap*}. */
    public static boolean isSpringConfig(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        boolean springName = name.startsWith("application") || name.startsWith("bootstrap");
        return springName && (name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".properties"));
    }

    public static boolean isBuildFile(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.equals("build.gradle") || name.equals("build.gradle.kts") || name.equals("pom.xml");
    }

    private static final Pattern GRADLE_COORDINATE = Pattern.compile("['\"]([\\w.\\-]+):([\\w.\\-]+)(?::[^'\"]*)?['\"]");
    private static final Pattern POM_ARTIFACT = Pattern.compile("<artifactId>\\s*([\\w.\\-]+)\\s*</artifactId>");

    /**
     * Artifact ids a build file names: {@code 'group:artifact:version'} strings in Gradle,
     * {@code <artifactId>} in Maven. Version catalogs ({@code libs.x.y}) are not resolved.
     */
    public static List<String> dependencies(String path, String content) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = (path.endsWith("pom.xml") ? POM_ARTIFACT : GRADLE_COORDINATE).matcher(content);
        while (m.find()) {
            out.add(m.group(path.endsWith("pom.xml") ? 1 : 2));
        }
        return List.copyOf(out);
    }

    public static boolean isMigration(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".sql") || (lower.endsWith(".xml") && (lower.contains("changelog") || lower.contains("liquibase")));
    }

    /** Keys whose values are secrets: never stored, not even in the local cache. */
    private static final Pattern SECRET_KEY = Pattern.compile(
            "(?i).*(password|passwd|pwd|secret|token|credential|api[-_.]?key|private[-_.]?key|access[-_.]?key).*");
    private static final Pattern URL_USERINFO = Pattern.compile("(//)[^/@\\s]+@");

    static final String REDACTED = "<redacted>";

    /**
     * A config file's flattened keys. Values of secret-looking keys are replaced with
     * {@value #REDACTED}, and user/password parts of URLs are masked, before anything is stored.
     */
    public static Map<String, String> properties(String path, String content) {
        Map<String, String> raw = path.toLowerCase(Locale.ROOT).endsWith(".properties") ? javaProperties(content) : yaml(content);
        Map<String, String> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> out.put(k, SECRET_KEY.matcher(k).matches() ? REDACTED
                : URL_USERINFO.matcher(v).replaceAll("$1***@")));
        return out;
    }

    static Map<String, String> javaProperties(String content) {
        Properties p = new Properties();
        try {
            p.load(new StringReader(content));
        } catch (IOException | IllegalArgumentException e) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        p.stringPropertyNames().stream().sorted().forEach(k -> out.put(k, p.getProperty(k).trim()));
        return out;
    }

    private record Frame(int indent, String prefix) {
    }

    /**
     * Flattens YAML to dotted keys. A list of scalars is stored both per index ({@code key[0]}) and
     * joined with commas under the key itself, which is how Spring binds a list to a comma string.
     * With several documents, the first value seen for a key is kept.
     */
    static Map<String, String> yaml(String content) {
        Map<String, String> out = new LinkedHashMap<>();
        Map<String, List<String>> lists = new LinkedHashMap<>();
        Map<String, Integer> listCounters = new LinkedHashMap<>();
        Deque<Frame> stack = new ArrayDeque<>();
        String[] lines = content.replace("\r\n", "\n").split("\n");
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            if (raw.startsWith("---") || raw.startsWith("...")) {
                stack.clear();
                continue;
            }
            String line = stripComment(raw);
            if (line.isBlank()) {
                continue;
            }
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') {
                indent++;
            }
            String text = line.substring(indent).stripTrailing();
            boolean dash = text.startsWith("- ") || text.equals("-");
            // A dash line may sit at its key's own indent ("topics:\n- a"), so a key frame at the
            // same indent stays open for it; an earlier list item's frame at that indent closes.
            while (!stack.isEmpty() && (stack.peek().indent() > indent
                    || (stack.peek().indent() == indent && (!dash || stack.peek().prefix().endsWith("]"))))) {
                stack.pop();
            }
            String prefix = stack.isEmpty() ? "" : stack.peek().prefix();

            if (dash) {
                String item = text.length() > 1 ? text.substring(2).trim() : "";
                int idx = listCounters.merge(prefix, 1, Integer::sum) - 1;
                String itemKey = prefix + "[" + idx + "]";
                int colon = keyColon(item);
                if (colon > 0) {
                    // "- name: x" starts a map; its further keys are indented past the dash
                    stack.push(new Frame(indent, itemKey));
                    String k = unquote(item.substring(0, colon).trim());
                    String v = item.substring(colon + 1).trim();
                    if (v.isEmpty()) {
                        stack.push(new Frame(indent + 2, itemKey + "." + k));
                    } else {
                        putFirst(out, itemKey + "." + k, scalar(v));
                    }
                } else if (!item.isEmpty()) {
                    String v = scalar(item);
                    putFirst(out, itemKey, v);
                    lists.computeIfAbsent(prefix, k -> new ArrayList<>()).add(v);
                }
                continue;
            }

            int colon = keyColon(text);
            if (colon <= 0) {
                continue; // a continuation line we do not interpret
            }
            String key = unquote(text.substring(0, colon).trim());
            String value = text.substring(colon + 1).trim();
            String full = prefix.isEmpty() ? key : prefix + "." + key;
            if (value.isEmpty()) {
                stack.push(new Frame(indent, full));
            } else if (value.equals("|") || value.equals(">") || value.startsWith("|") || value.startsWith(">")) {
                StringBuilder block = new StringBuilder();
                boolean folded = value.startsWith(">");
                while (i + 1 < lines.length && (lines[i + 1].isBlank() || leading(lines[i + 1]) > indent)) {
                    i++;
                    if (!block.isEmpty()) {
                        block.append(folded ? " " : "\n");
                    }
                    block.append(lines[i].trim());
                }
                putFirst(out, full, block.toString().trim());
            } else {
                putFirst(out, full, scalar(value));
            }
        }
        lists.forEach((k, v) -> putFirst(out, k, String.join(",", v)));
        return out;
    }

    private static void putFirst(Map<String, String> out, String key, String value) {
        out.putIfAbsent(key, value);
    }

    private static int leading(String s) {
        int n = 0;
        while (n < s.length() && s.charAt(n) == ' ') {
            n++;
        }
        return n;
    }

    /** Index of the colon that ends a key (followed by space or end), ignoring colons inside quotes. */
    static int keyColon(String text) {
        boolean single = false;
        boolean dbl = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'' && !dbl) {
                single = !single;
            } else if (c == '"' && !single) {
                dbl = !dbl;
            } else if (c == ':' && !single && !dbl && (i + 1 == text.length() || text.charAt(i + 1) == ' ')) {
                return i;
            }
        }
        return -1;
    }

    private static String stripComment(String line) {
        boolean single = false;
        boolean dbl = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !dbl) {
                single = !single;
            } else if (c == '"' && !single) {
                dbl = !dbl;
            } else if (c == '#' && !single && !dbl && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    /** A scalar value: quotes removed, a flow list {@code [a, b]} joined to {@code a,b}. */
    static String scalar(String v) {
        String t = v.trim();
        if (t.startsWith("[") && t.endsWith("]")) {
            List<String> parts = new ArrayList<>();
            for (String p : t.substring(1, t.length() - 1).split(",")) {
                if (!p.isBlank()) {
                    parts.add(unquote(p.trim()));
                }
            }
            return String.join(",", parts);
        }
        return unquote(t);
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /** Tables a migration creates or alters, lower-cased, without schema or quotes. */
    public static List<String> tables(String path, String content) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = (path.toLowerCase(Locale.ROOT).endsWith(".xml") ? LIQUIBASE_TABLE : SQL_TABLE).matcher(content);
        while (m.find()) {
            String name = m.group(1).replaceAll("[`\"\\[\\]]", "");
            name = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            if (!name.isEmpty()) {
                out.add(name);
            }
        }
        return List.copyOf(out);
    }
}
