package dev.agentideate.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.agentideate.graph.CodeGraph;
import dev.agentideate.graph.Integrations;
import dev.agentideate.graph.Integrations.Message;
import dev.agentideate.workspace.WorkspaceService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Every Kafka topic, RabbitMQ queue and JMS destination in the workspace with who produces to it
 * and who consumes it, across services. Channels with a producer and no consumer here, or the
 * reverse, are marked: that is either an external system or a bug, and worth knowing either way.
 */
final class MessagingMapTool implements Tool {

    @Override
    public String name() {
        return "messaging_map";
    }

    @Override
    public String description() {
        return "Kafka topics, RabbitMQ queues, JMS destinations: producers and consumers per channel across "
                + "services, with file:line. Topic names resolved from constants and application.yml.";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode s = ToolRegistry.objectSchema();
        ((ObjectNode) s.get("properties")).putObject("channel").put("type", "string")
                .put("description", "Only channels whose name contains this (optional)");
        return s;
    }

    @Override
    public void run(ToolArgs args, WorkspaceService.Snapshot snapshot, CodeGraph graph, Answer out) {
        String filter = args.string("channel");
        Integrations integ = graph.integrations();
        Map<String, List<Message>> byChannel = new TreeMap<>();
        List<Message> unresolved = new ArrayList<>();
        for (Message m : integ.messages()) {
            if (!m.resolved()) {
                unresolved.add(m);
                continue;
            }
            if (filter != null && !m.channel().toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT))) {
                continue;
            }
            byChannel.computeIfAbsent(m.system() + " " + m.channel(), k -> new ArrayList<>()).add(m);
        }
        if (byChannel.isEmpty() && unresolved.isEmpty()) {
            out.line(filter == null
                    ? "Not found: no Kafka, RabbitMQ or JMS producers or consumers in this workspace."
                    : "Not found: no channel containing \"" + filter + "\".");
            out.line("Searched: KafkaTemplate/RabbitTemplate/JmsTemplate send calls, @KafkaListener/@RabbitListener/@JmsListener/@KafkaHandler.");
            return;
        }
        for (Map.Entry<String, List<Message>> ch : byChannel.entrySet()) {
            out.line(ch.getKey());
            List<Message> producers = ch.getValue().stream().filter(Message::producer).toList();
            List<Message> consumers = ch.getValue().stream().filter(m -> !m.producer()).toList();
            for (Message p : producers) {
                out.line("  produced by  " + describe(graph, p, out));
            }
            if (producers.isEmpty() && !ch.getKey().contains(" pattern:")) {
                out.line("  produced by  none in this workspace (external, or not indexed)");
            }
            for (Message c : consumers) {
                out.line("  consumed by  " + describe(graph, c, out));
            }
            if (consumers.isEmpty()) {
                List<Message> viaPattern = producers.isEmpty() ? List.of() : integ.counterparts(producers.get(0));
                if (viaPattern.isEmpty()) {
                    out.line("  consumed by  none in this workspace (external, or not indexed)");
                } else {
                    viaPattern.forEach(c -> out.line("  consumed by  " + describe(graph, c, out) + "  [pattern " + c.channel() + "]"));
                }
            }
        }
        if (!unresolved.isEmpty() && filter == null) {
            out.blank();
            out.line("Channel not known statically (" + unresolved.size() + "): a runtime value or an unresolved placeholder");
            for (Message m : unresolved) {
                out.line("  " + m.system() + " " + (m.producer() ? "producer" : "consumer") + " " + m.channel() + "  "
                        + m.owner().name() + "#" + m.method().name() + "  " + out.ref(m.owner().file(), m.line()));
            }
        }
    }

    static String describe(CodeGraph graph, Message m, Answer out) {
        StringBuilder sb = new StringBuilder(graph.moduleKey(m.owner().file()));
        if (m.group() != null) {
            sb.append(" (group ").append(m.group()).append(')');
        }
        sb.append("  ").append(m.owner().name()).append('#').append(m.method().name())
                .append("  ").append(out.ref(m.owner().file(), m.line()));
        if (m.detail() != null) {
            sb.append("  [").append(m.detail()).append(']');
        }
        return sb.toString();
    }
}
