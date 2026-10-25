package dev.agentideate.index;

import java.util.List;
import java.util.Map;

/**
 * A top-level or nested type.
 *
 * @param kind      {@code class}, {@code interface}, {@code enum}, {@code record} or {@code annotation}
 * @param basePath  class-level {@code @RequestMapping} path, or {@code ""}
 * @param constants {@code static final String} fields with a literal value, by field name
 * @param superTypeArgs raw type arguments of each generic supertype, e.g.
 *                  {@code JpaRepository -> [OrderEntity, Long]}
 */
public record TypeDecl(String name, String fqn, String kind, int line, int endLine,
                       List<Ann> annotations, List<String> extendsTypes, List<String> implementsTypes,
                       List<FieldDecl> fields, List<MethodDecl> methods, List<InjectionPoint> injections,
                       List<Endpoint> endpoints, Map<String, String> constants, String basePath,
                       Map<String, List<String>> superTypeArgs) {

    public boolean hasAnnotation(String simpleName) {
        return annotation(simpleName) != null;
    }

    public Ann annotation(String simpleName) {
        for (Ann a : annotations) {
            if (a.name().equals(simpleName)) {
                return a;
            }
        }
        return null;
    }
}
