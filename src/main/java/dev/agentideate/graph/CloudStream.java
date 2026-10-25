package dev.agentideate.graph;

import dev.agentideate.graph.CodeGraph.TypeNode;
import dev.agentideate.graph.Integrations.Message;
import dev.agentideate.index.Ann;
import dev.agentideate.index.MethodDecl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Spring Cloud Stream, functional style. The code only declares functions; config decides where
 * they connect:
 *
 * <ol>
 *   <li>a {@code @Bean} returning {@code Function}/{@code Consumer}/{@code Supplier}, or a component
 *       implementing one, is a functional bean named after the method (or class);</li>
 *   <li>{@code spring.cloud.function.definition} picks which are bound ({@code a;b}, composition
 *       {@code a|b}); without it, a lone functional bean is bound and several are not;</li>
 *   <li>bindings are named {@code <name>-in-0} / {@code <name>-out-0} (a composition's names are
 *       concatenated), optionally renamed by {@code spring.cloud.stream.function.bindings.*};</li>
 *   <li>{@code spring.cloud.stream.bindings.<binding>.destination} is the topic (the binding name
 *       itself when unset) and {@code .group} the consumer group;</li>
 *   <li>{@code StreamBridge.send(x, ...)} sends to binding {@code x}'s destination, or to {@code x}
 *       itself as a destination when no such binding is configured.</li>
 * </ol>
 *
 * Messages are named by broker ({@code kafka}/{@code rabbitmq}, from the binder), so a Stream
 * consumer matches a plain {@code KafkaTemplate} producer on the same topic.
 */
final class CloudStream {

    record FunctionBean(TypeNode owner, MethodDecl method, String name, String kind) {
    }

    private static final Set<String> STEREOTYPES = Set.of("Component", "Service", "Configuration");

    private final CodeGraph graph;
    private final Integrations integ;

    CloudStream(CodeGraph graph, Integrations integ) {
        this.graph = graph;
        this.integ = integ;
    }

    /** Whether a module uses Spring Cloud Stream at all: a binder/stream dependency or stream config. */
    boolean isStreamModule(String module) {
        for (String d : integ.dependencies(module)) {
            if (d.contains("spring-cloud-stream") || d.contains("spring-cloud-starter-stream")) {
                return true;
            }
        }
        for (String k : integ.properties(module).keySet()) {
            if (k.startsWith("spring.cloud.stream.") || k.equals("spring.cloud.function.definition")) {
                return true;
            }
        }
        return false;
    }

    void collect(List<Message> out) {
        Map<String, List<FunctionBean>> beansByModule = new LinkedHashMap<>();
        for (TypeNode n : graph.types()) {
            if (!n.file().isTest()) {
                for (FunctionBean b : functionBeans(n)) {
                    beansByModule.computeIfAbsent(graph.moduleKey(b.owner().file()), k -> new ArrayList<>()).add(b);
                }
            }
        }
        for (Map.Entry<String, List<FunctionBean>> e : beansByModule.entrySet()) {
            String module = e.getKey();
            if (isStreamModule(module)) {
                bindModule(module, e.getValue(), out);
            }
        }
    }

    private void bindModule(String module, List<FunctionBean> beans, List<Message> out) {
        Map<String, String> props = integ.properties(module);
        String definition = props.get("spring.cloud.function.definition");
        if (definition == null) {
            if (beans.size() == 1) {
                FunctionBean b = beans.get(0);
                bind(module, b.name(), b, b, out);
            } else {
                for (FunctionBean b : beans) {
                    unbound(module, b, "not bound: several functions and no spring.cloud.function.definition", out);
                }
            }
            return;
        }
        Set<String> named = new HashSet<>();
        for (String entry : definition.split("[;,]")) {
            String[] parts = entry.trim().split("\\|");
            if (parts.length == 0 || parts[0].isBlank()) {
                continue;
            }
            for (String p : parts) {
                named.add(p.trim());
            }
            FunctionBean first = find(beans, parts[0].trim());
            FunctionBean last = find(beans, parts[parts.length - 1].trim());
            StringBuilder base = new StringBuilder();
            for (String p : parts) {
                base.append(p.trim());
            }
            bind(module, base.toString(), first, last, out);
        }
        for (FunctionBean b : beans) {
            if (!named.contains(b.name())) {
                unbound(module, b, "not bound: not in spring.cloud.function.definition", out);
            }
        }
    }

    /** Input binding from the first function of a (possibly composed) definition, output from the last. */
    private void bind(String module, String base, FunctionBean first, FunctionBean last, List<Message> out) {
        if (first != null && !first.kind().equals("supplier")) {
            binding(module, base + "-in-0", first, true, out);
        }
        if (last != null && !last.kind().equals("consumer")) {
            binding(module, base + "-out-0", last, false, out);
        }
    }

    private void binding(String module, String defaultName, FunctionBean bean, boolean input, List<Message> out) {
        Map<String, String> props = integ.properties(module);
        String binding = props.getOrDefault("spring.cloud.stream.function.bindings." + defaultName, defaultName);
        String dest = props.getOrDefault("spring.cloud.stream.bindings." + binding + ".destination", binding);
        String group = input ? props.get("spring.cloud.stream.bindings." + binding + ".group") : null;
        String system = binder(module, binding);
        for (String d : integ.resolveValue(dest, bean.owner()).split(",")) {
            out.add(new Message(system, d.trim(), input ? "consumer" : "producer", bean.owner(), bean.method(),
                    bean.method().line(), group == null ? null : integ.resolveValue(group, bean.owner()),
                    "stream binding " + binding));
        }
    }

    private void unbound(String module, FunctionBean b, String reason, List<Message> out) {
        out.add(new Message(binder(module, b.name() + "-in-0"), "?", b.kind().equals("supplier") ? "producer" : "consumer",
                b.owner(), b.method(), b.method().line(), null, "stream function " + b.name() + " " + reason));
    }

    /** {@code StreamBridge.send(nameOrDestination, ...)}. */
    Message streamBridge(String module, String rawTarget, TypeNode owner, MethodDecl method, int line) {
        String name = integ.resolveValue(rawTarget, owner);
        String dest = integ.properties(module).get("spring.cloud.stream.bindings." + name + ".destination");
        String detail = dest == null ? "StreamBridge, destination named directly" : "StreamBridge binding " + name;
        String channel = dest == null ? name : integ.resolveValue(dest, owner);
        return new Message(binder(module, name), channel, "producer", owner, method, line, null, detail);
    }

    /**
     * The broker behind a binding: the binding's own {@code binder}, the {@code default-binder}, a
     * binder dependency, or binder-specific config keys. {@code stream} when it cannot be told, so
     * the binding still matches other Stream bindings but is not claimed for Kafka or RabbitMQ.
     */
    String binder(String module, String binding) {
        Map<String, String> props = integ.properties(module);
        String name = props.get("spring.cloud.stream.bindings." + binding + ".binder");
        if (name == null) {
            name = props.get("spring.cloud.stream.default-binder");
        }
        if (name != null) {
            String type = props.get("spring.cloud.stream.binders." + name + ".type");
            String kind = normalize(type != null ? type : name);
            if (kind != null) {
                return kind;
            }
        }
        boolean kafka = false;
        boolean rabbit = false;
        for (String d : integ.dependencies(module)) {
            kafka |= d.contains("binder-kafka") || d.contains("stream-kafka");
            rabbit |= d.contains("binder-rabbit") || d.contains("stream-rabbit");
        }
        if (!kafka && !rabbit) {
            for (String k : props.keySet()) {
                kafka |= k.startsWith("spring.cloud.stream.kafka.");
                rabbit |= k.startsWith("spring.cloud.stream.rabbit.");
            }
        }
        if (kafka != rabbit) {
            return kafka ? "kafka" : "rabbitmq";
        }
        return "stream";
    }

    private static String normalize(String binder) {
        String b = binder.toLowerCase(Locale.ROOT);
        if (b.contains("kafka")) {
            return "kafka";
        }
        if (b.contains("rabbit")) {
            return "rabbitmq";
        }
        return null;
    }

    private static FunctionBean find(List<FunctionBean> beans, String name) {
        for (FunctionBean b : beans) {
            if (b.name().equals(name)) {
                return b;
            }
        }
        return null;
    }

    /** Functional beans declared by a type: {@code @Bean} factory methods, or the type itself as a component. */
    static List<FunctionBean> functionBeans(TypeNode n) {
        List<FunctionBean> out = new ArrayList<>();
        for (MethodDecl m : n.decl().methods()) {
            Ann bean = m.annotations().stream().filter(a -> a.name().equals("Bean")).findFirst().orElse(null);
            if (bean == null) {
                continue;
            }
            String kind = functionalKind(m.returnType());
            if (kind != null) {
                String explicit = bean.firstAttr("name", "value");
                out.add(new FunctionBean(n, m, explicit != null ? explicit.split(",")[0].trim() : m.name(), kind));
            }
        }
        Ann stereotype = n.decl().annotations().stream().filter(a -> STEREOTYPES.contains(a.name())).findFirst().orElse(null);
        if (stereotype != null && !n.isInterface()) {
            for (String sup : n.decl().implementsTypes()) {
                String kind = functionalKind(sup);
                if (kind == null) {
                    continue;
                }
                String method = switch (kind) {
                    case "consumer" -> "accept";
                    case "supplier" -> "get";
                    default -> "apply";
                };
                MethodDecl m = n.method(method);
                if (m != null) {
                    String explicit = stereotype.attr("value");
                    out.add(new FunctionBean(n, m, explicit != null ? explicit : Integrations.decapitalize(n.name()), kind));
                }
            }
        }
        return out;
    }

    /** {@code function}, {@code consumer} or {@code supplier} for a (possibly generic, qualified) type name. */
    static String functionalKind(String type) {
        if (type == null) {
            return null;
        }
        String raw = type.replaceAll("<.*", "").trim();
        raw = raw.substring(raw.lastIndexOf('.') + 1);
        return switch (raw) {
            case "Function", "BiFunction" -> "function";
            case "Consumer", "BiConsumer" -> "consumer";
            case "Supplier" -> "supplier";
            default -> null;
        };
    }
}
