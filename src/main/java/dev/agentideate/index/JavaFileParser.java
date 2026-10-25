package dev.agentideate.index;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses one Java source file into the index model. Purely syntactic: it records what is written,
 * and leaves resolving names to types across files to {@code CodeGraph}.
 */
public final class JavaFileParser {

    /** Class annotations that make Spring create a bean, so constructor parameters are injections. */
    static final Set<String> BEAN_STEREOTYPES = Set.of(
            "Component", "Service", "Repository", "Controller", "RestController", "Configuration");

    private static final Set<String> INJECT_ANNOTATIONS = Set.of("Autowired", "Inject", "Resource", "PersistenceContext");

    private static final Map<String, String> MAPPING_ANNOTATIONS = Map.of(
            "GetMapping", "GET", "PostMapping", "POST", "PutMapping", "PUT",
            "DeleteMapping", "DELETE", "PatchMapping", "PATCH", "RequestMapping", "ANY");

    /** How many leading call arguments are recorded: enough for {@code send(topic, key, value)}. */
    static final int MAX_ARGS = 3;

    public record Parsed(String packageName, List<String> imports, List<TypeDecl> types, String error) {
    }

    private final JavaParser parser = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

    public Parsed parse(String source) {
        ParseResult<CompilationUnit> result = parser.parse(source);
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            String why = result.getProblems().isEmpty() ? "unknown parse error"
                    : result.getProblems().get(0).getVerboseMessage();
            return new Parsed("", List.of(), List.of(), firstLine(why));
        }
        CompilationUnit cu = result.getResult().get();
        String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        List<String> imports = new ArrayList<>();
        for (ImportDeclaration imp : cu.getImports()) {
            imports.add((imp.isStatic() ? "static " : "") + imp.getNameAsString() + (imp.isAsterisk() ? ".*" : ""));
        }
        List<TypeDecl> types = new ArrayList<>();
        for (TypeDeclaration<?> type : cu.getTypes()) {
            collectType(type, pkg.isEmpty() ? "" : pkg + ".", types);
        }
        return new Parsed(pkg, imports, types, null);
    }

    private void collectType(TypeDeclaration<?> type, String prefix, List<TypeDecl> out) {
        String fqn = prefix + type.getNameAsString();
        out.add(toTypeDecl(type, fqn));
        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof TypeDeclaration<?> nested) {
                collectType(nested, fqn + ".", out);
            }
        }
    }

    private TypeDecl toTypeDecl(TypeDeclaration<?> type, String fqn) {
        String kind = "class";
        List<String> extendsTypes = new ArrayList<>();
        List<String> implementsTypes = new ArrayList<>();
        Map<String, List<String>> superTypeArgs = new LinkedHashMap<>();
        if (type instanceof ClassOrInterfaceDeclaration c) {
            kind = c.isInterface() ? "interface" : "class";
            c.getExtendedTypes().forEach(t -> extendsTypes.add(t.getNameAsString()));
            c.getImplementedTypes().forEach(t -> implementsTypes.add(t.getNameAsString()));
            for (ClassOrInterfaceType t : c.getExtendedTypes()) {
                t.getTypeArguments().ifPresent(args -> superTypeArgs.put(t.getNameAsString(),
                        args.stream().map(JavaFileParser::rawType).toList()));
            }
            for (ClassOrInterfaceType t : c.getImplementedTypes()) {
                t.getTypeArguments().ifPresent(args -> superTypeArgs.put(t.getNameAsString(),
                        args.stream().map(JavaFileParser::rawType).toList()));
            }
        } else if (type instanceof EnumDeclaration e) {
            kind = "enum";
            e.getImplementedTypes().forEach(t -> implementsTypes.add(t.getNameAsString()));
        } else if (type instanceof RecordDeclaration r) {
            kind = "record";
            r.getImplementedTypes().forEach(t -> implementsTypes.add(t.getNameAsString()));
        } else if (type instanceof AnnotationDeclaration) {
            kind = "annotation";
        }

        List<Ann> annotations = annotations(type);
        Map<String, String> fieldTypes = new HashMap<>();
        Map<String, String> valueFields = new HashMap<>();
        List<FieldDecl> fields = new ArrayList<>();
        Map<String, String> constants = new LinkedHashMap<>();
        for (FieldDeclaration fd : type.getFields()) {
            List<Ann> fieldAnns = annotations(fd);
            for (VariableDeclarator v : fd.getVariables()) {
                String vType = rawType(v.getType());
                fieldTypes.put(v.getNameAsString(), vType);
                for (Ann a : fieldAnns) {
                    if (a.name().equals("Value") && a.attr("value") != null) {
                        valueFields.put(v.getNameAsString(), a.attr("value"));
                    }
                }
                fields.add(new FieldDecl(v.getNameAsString(), vType, line(v), fieldAnns,
                        fd.isStatic(), fd.isFinal(), v.getInitializer().isPresent()));
                if (fd.isStatic() && fd.isFinal() && "String".equals(vType) && v.getInitializer().isPresent()) {
                    constants.put(v.getNameAsString(), render(v.getInitializer().get()));
                }
            }
        }

        boolean isBean = annotations.stream().anyMatch(a -> BEAN_STEREOTYPES.contains(a.name()));
        boolean isFeign = annotations.stream().anyMatch(a -> a.name().equals("FeignClient"));

        List<MethodDecl> methods = new ArrayList<>();
        List<Endpoint> endpoints = new ArrayList<>();
        List<InjectionPoint> injections = new ArrayList<>();

        for (MethodDeclaration m : type.getMethods()) {
            List<Ann> mAnns = annotations(m);
            methods.add(new MethodDecl(m.getNameAsString(), signature(m), m.getType().asString(),
                    line(m), endLine(m), mAnns, calls(m, fieldTypes, valueFields)));
            for (Ann a : mAnns) {
                String http = MAPPING_ANNOTATIONS.get(a.name());
                if (http == null) {
                    continue;
                }
                if (http.equals("ANY")) {
                    http = requestMethod(a.attr("method"));
                }
                for (String path : paths(a)) {
                    endpoints.add(new Endpoint(http, path, m.getNameAsString(), line(m), isFeign));
                }
            }
            if (mAnns.stream().anyMatch(a -> INJECT_ANNOTATIONS.contains(a.name()))) {
                for (Parameter p : m.getParameters()) {
                    injections.add(new InjectionPoint(p.getNameAsString(), rawType(p.getType()), "setter", line(p)));
                }
            }
        }

        List<ConstructorDeclaration> ctors = type.getConstructors();
        for (ConstructorDeclaration ctor : ctors) {
            methods.add(new MethodDecl(ctor.getNameAsString(), signature(ctor), "", line(ctor), endLine(ctor),
                    annotations(ctor), calls(ctor, fieldTypes, valueFields)));
        }

        for (FieldDecl f : fields) {
            if (!f.isStatic() && f.annotations().stream().anyMatch(a -> INJECT_ANNOTATIONS.contains(a.name()))) {
                injections.add(new InjectionPoint(f.name(), f.type(), "field", f.line()));
            }
        }
        if (isBean && kind.equals("class")) {
            ConstructorDeclaration injecting = injectingConstructor(ctors);
            if (injecting != null) {
                for (Parameter p : injecting.getParameters()) {
                    injections.add(new InjectionPoint(p.getNameAsString(), rawType(p.getType()), "constructor", line(p)));
                }
            } else if (ctors.isEmpty()) {
                boolean required = annotations.stream().anyMatch(a -> a.name().equals("RequiredArgsConstructor"));
                boolean all = annotations.stream().anyMatch(a -> a.name().equals("AllArgsConstructor"));
                if (required || all) {
                    for (FieldDecl f : fields) {
                        if (!f.isStatic() && !f.initialized() && (all || f.isFinal())) {
                            injections.add(new InjectionPoint(f.name(), f.type(), "lombok", f.line()));
                        }
                    }
                }
            }
        }

        String basePath = "";
        Ann classMapping = annotations.stream().filter(a -> a.name().equals("RequestMapping")).findFirst().orElse(null);
        if (classMapping != null) {
            List<String> ps = paths(classMapping);
            basePath = ps.isEmpty() ? "" : ps.get(0);
        } else if (isFeign) {
            // A Feign client's own "path" attribute prefixes each of its method mappings.
            Ann feign = annotations.stream().filter(a -> a.name().equals("FeignClient")).findFirst().orElseThrow();
            String p = feign.attr("path");
            basePath = p == null ? "" : p;
        }

        return new TypeDecl(type.getNameAsString(), fqn, kind, line(type), endLine(type), annotations,
                extendsTypes, implementsTypes, fields, methods, injections, endpoints, constants, basePath, superTypeArgs);
    }

    /** Spring uses the only constructor, or the one marked {@code @Autowired}. */
    private static ConstructorDeclaration injectingConstructor(List<ConstructorDeclaration> ctors) {
        for (ConstructorDeclaration c : ctors) {
            if (c.getAnnotations().stream().anyMatch(a -> INJECT_ANNOTATIONS.contains(a.getNameAsString()))) {
                return c;
            }
        }
        return ctors.size() == 1 ? ctors.get(0) : null;
    }

    /**
     * Calls in a method body, each with the declared type of its receiver when that can be read
     * from a parameter, a local variable or a field. Chained calls ({@code a.b().c()}) are skipped:
     * resolving them needs return types this parser does not compute.
     */
    private static List<CallSite> calls(CallableDeclaration<?> callable, Map<String, String> fieldTypes,
                                        Map<String, String> valueFields) {
        Map<String, String> locals = new HashMap<>();
        for (Parameter p : callable.getParameters()) {
            locals.put(p.getNameAsString(), rawType(p.getType()));
        }
        for (VariableDeclarationExpr vde : callable.findAll(VariableDeclarationExpr.class)) {
            for (VariableDeclarator v : vde.getVariables()) {
                String t = rawType(v.getType());
                if ("var".equals(t)) {
                    t = v.getInitializer()
                            .filter(Expression::isObjectCreationExpr)
                            .map(e -> rawType(((ObjectCreationExpr) e).getType()))
                            .orElse(null);
                }
                if (t != null) {
                    locals.put(v.getNameAsString(), t);
                }
            }
        }
        List<CallSite> calls = new ArrayList<>();
        for (MethodCallExpr call : callable.findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            int ln = line(call);
            List<String> args = new ArrayList<>();
            for (int i = 0; i < Math.min(MAX_ARGS, call.getArguments().size()); i++) {
                args.add(renderArg(call.getArgument(i), locals, valueFields));
            }
            if (call.getScope().isEmpty() || call.getScope().get() instanceof ThisExpr) {
                calls.add(new CallSite(null, name, ln, args));
                continue;
            }
            Expression scope = call.getScope().get();
            if (scope instanceof NameExpr ne) {
                String id = ne.getNameAsString();
                String t = locals.containsKey(id) ? locals.get(id) : fieldTypes.get(id);
                if (t == null && !id.isEmpty() && Character.isUpperCase(id.charAt(0))) {
                    t = id; // a static call such as OrderMapper.toDto(order)
                }
                if (t != null) {
                    calls.add(new CallSite(t, name, ln, args));
                }
            } else if (scope instanceof FieldAccessExpr fa && fa.getScope() instanceof ThisExpr) {
                String t = fieldTypes.get(fa.getNameAsString());
                if (t != null) {
                    calls.add(new CallSite(t, name, ln, args));
                }
            }
        }
        return calls;
    }

    /**
     * A call argument as text: a literal as itself, a constant as {@code ${NAME}} (resolved later
     * by the graph), a {@code @Value} field as its placeholder, a local variable or parameter as
     * {@code <name>} (a runtime value, never looked up as a constant), anything else as {@code ?}.
     */
    static String renderArg(Expression e, Map<String, String> locals, Map<String, String> valueFields) {
        if (e instanceof StringLiteralExpr s) {
            return s.asString();
        }
        if (e instanceof NameExpr n) {
            String id = n.getNameAsString();
            if (locals.containsKey(id)) {
                return "<" + id + ">";
            }
            return valueFields.containsKey(id) ? valueFields.get(id) : "${" + id + "}";
        }
        if (e instanceof FieldAccessExpr f) {
            if (f.getScope() instanceof ThisExpr) {
                String id = f.getNameAsString();
                return valueFields.containsKey(id) ? valueFields.get(id) : "${" + id + "}";
            }
            return "${" + f + "}";
        }
        if (e instanceof BinaryExpr b && b.getOperator() == BinaryExpr.Operator.PLUS) {
            return renderArg(b.getLeft(), locals, valueFields) + renderArg(b.getRight(), locals, valueFields);
        }
        return "?";
    }

    private static List<String> paths(Ann mapping) {
        String raw = mapping.firstAttr("value", "path");
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            out.add("");
            return out;
        }
        for (String p : raw.split(",")) {
            out.add(p.trim());
        }
        return out;
    }

    /** {@code method = RequestMethod.POST} is rendered {@code ${RequestMethod.POST}}; keep {@code POST}. */
    private static String requestMethod(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "ANY";
        }
        String first = raw.split(",")[0].trim();
        if (first.startsWith("${") && first.endsWith("}")) {
            first = first.substring(2, first.length() - 1);
        }
        int dot = first.lastIndexOf('.');
        return dot >= 0 ? first.substring(dot + 1) : first;
    }

    private static List<Ann> annotations(NodeWithAnnotations<?> node) {
        List<Ann> out = new ArrayList<>();
        for (AnnotationExpr a : node.getAnnotations()) {
            Map<String, String> attrs = new LinkedHashMap<>();
            if (a instanceof SingleMemberAnnotationExpr s) {
                attrs.put("value", render(s.getMemberValue()));
            } else if (a instanceof NormalAnnotationExpr n) {
                n.getPairs().forEach(p -> attrs.put(p.getNameAsString(), render(p.getValue())));
            }
            out.add(new Ann(a.getName().getIdentifier(), attrs));
        }
        return out;
    }

    /**
     * Renders an annotation value or constant initializer. A constant reference becomes
     * {@code ${NAME}} or {@code ${Owner.NAME}} so it is resolved later rather than read as text.
     */
    static String render(Expression e) {
        if (e instanceof StringLiteralExpr s) {
            return s.asString();
        }
        if (e instanceof ArrayInitializerExpr arr) {
            List<String> parts = new ArrayList<>();
            arr.getValues().forEach(v -> parts.add(render(v)));
            return String.join(",", parts);
        }
        if (e instanceof NameExpr n) {
            return "${" + n.getNameAsString() + "}";
        }
        if (e instanceof FieldAccessExpr f) {
            return "${" + f.toString() + "}";
        }
        if (e instanceof BinaryExpr b && b.getOperator() == BinaryExpr.Operator.PLUS) {
            return render(b.getLeft()) + render(b.getRight());
        }
        if (e instanceof ClassExpr c) {
            return rawType(c.getType());
        }
        return e.toString();
    }

    static String rawType(Type t) {
        if (t instanceof ClassOrInterfaceType c) {
            return c.getNameAsString();
        }
        if (t instanceof ArrayType a) {
            return rawType(a.getComponentType()) + "[]";
        }
        return t.asString();
    }

    private static String signature(CallableDeclaration<?> c) {
        StringBuilder sb = new StringBuilder();
        if (c instanceof MethodDeclaration m) {
            sb.append(m.getType().asString()).append(' ');
        }
        sb.append(c.getNameAsString()).append('(');
        NodeList<Parameter> params = c.getParameters();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Parameter p = params.get(i);
            sb.append(p.getType().asString()).append(p.isVarArgs() ? "... " : " ").append(p.getNameAsString());
        }
        return sb.append(')').toString();
    }

    private static int line(Node n) {
        return n.getBegin().map(p -> p.line).orElse(0);
    }

    private static int endLine(Node n) {
        return n.getEnd().map(p -> p.line).orElse(0);
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }
}
