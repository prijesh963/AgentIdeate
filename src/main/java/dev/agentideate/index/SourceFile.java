package dev.agentideate.index;

import java.util.List;

/**
 * One indexed file.
 *
 * @param root       name of the workspace root the file belongs to
 * @param path       root-relative path with {@code /} separators
 * @param module     root-relative path of the nearest enclosing build module ({@code ""} for the root)
 * @param sha256     content hash, so an edit is noticed even when size and mtime look unchanged
 * @param parseError why a Java file could not be parsed, or {@code null}; non-Java files are never parsed
 */
public record SourceFile(String root, String path, String module, long size, long mtime, String sha256,
                         String packageName, List<String> imports, List<TypeDecl> types, String parseError) {

    public boolean isJava() {
        return path.endsWith(".java");
    }

    public String key() {
        return root + ":" + path;
    }
}
