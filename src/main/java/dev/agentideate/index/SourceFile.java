package dev.agentideate.index;

import java.util.List;
import java.util.Map;

/**
 * One indexed file.
 *
 * @param root       name of the workspace root the file belongs to
 * @param path       root-relative path with {@code /} separators
 * @param module     root-relative path of the nearest enclosing build module ({@code ""} for the root)
 * @param sha256     content hash, so an edit is noticed even when size and mtime look unchanged
 * @param parseError why a Java file could not be parsed, or {@code null}
 * @param properties flattened keys of a Spring config file ({@code application*.yml/.properties}),
 *                   otherwise empty
 * @param tables     tables a SQL migration or Liquibase changelog creates or alters, otherwise empty
 * @param dependencies artifact ids a build file declares ({@code build.gradle(.kts)}, {@code pom.xml}),
 *                   otherwise empty
 */
public record SourceFile(String root, String path, String module, long size, long mtime, String sha256,
                         String packageName, List<String> imports, List<TypeDecl> types, String parseError,
                         Map<String, String> properties, List<String> tables, List<String> dependencies) {

    public boolean isJava() {
        return path.endsWith(".java");
    }

    public boolean isTest() {
        return path.contains("/src/test/") || path.startsWith("src/test/");
    }

    public String key() {
        return root + ":" + path;
    }
}
