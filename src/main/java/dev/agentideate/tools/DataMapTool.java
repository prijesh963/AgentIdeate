package dev.agentideate.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.agentideate.graph.CodeGraph;
import dev.agentideate.graph.Integrations;
import dev.agentideate.graph.Integrations.DirectAccess;
import dev.agentideate.graph.Integrations.Entity;
import dev.agentideate.graph.Integrations.Repo;
import dev.agentideate.graph.Integrations.Store;
import dev.agentideate.index.SourceFile;
import dev.agentideate.workspace.WorkspaceService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Per service: the configured datastores, each entity with the table or collection it maps to, its
 * repositories and the migrations that touch its table, plus classes that bypass repositories
 * through a template ({@code JdbcTemplate}, {@code MongoTemplate}, ...).
 */
final class DataMapTool implements Tool {

    @Override
    public String name() {
        return "data_map";
    }

    @Override
    public String description() {
        return "Datastores per service: entity -> table/collection, Spring Data repositories, Flyway/Liquibase "
                + "migrations, direct JdbcTemplate/MongoTemplate use, with file:line.";
    }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode s = ToolRegistry.objectSchema();
        ((ObjectNode) s.get("properties")).putObject("name").put("type", "string")
                .put("description", "Only entities/tables whose name contains this (optional)");
        return s;
    }

    @Override
    public void run(ToolArgs args, WorkspaceService.Snapshot snapshot, CodeGraph graph, Answer out) {
        String filter = args.string("name");
        String f = filter == null ? null : filter.toLowerCase(Locale.ROOT);
        Integrations integ = graph.integrations();

        Set<String> modules = new TreeSet<>();
        integ.entities().forEach(e -> modules.add(graph.moduleKey(e.type().file())));
        integ.repos().forEach(r -> modules.add(graph.moduleKey(r.repo().file())));
        integ.directAccess().forEach(d -> modules.add(graph.moduleKey(d.owner().file())));
        integ.stores().forEach((m, s) -> {
            if (s.stream().anyMatch(x -> x.role().equals("datastore"))) {
                modules.add(m);
            }
        });

        int shown = 0;
        for (String module : modules) {
            List<Entity> entities = integ.entities().stream()
                    .filter(e -> graph.moduleKey(e.type().file()).equals(module))
                    .filter(e -> f == null || e.name().toLowerCase(Locale.ROOT).contains(f)
                            || e.type().name().toLowerCase(Locale.ROOT).contains(f))
                    .toList();
            List<DirectAccess> direct = integ.directAccess().stream()
                    .filter(d -> graph.moduleKey(d.owner().file()).equals(module)).toList();
            List<Repo> orphanRepos = integ.repos().stream()
                    .filter(r -> graph.moduleKey(r.repo().file()).equals(module))
                    .filter(r -> r.entity() == null || integ.entityOf(r.entity()) == null)
                    .filter(r -> f == null || r.entityName().toLowerCase(Locale.ROOT).contains(f)
                            || r.repo().name().toLowerCase(Locale.ROOT).contains(f))
                    .toList();
            if (f != null && entities.isEmpty() && orphanRepos.isEmpty()) {
                continue;
            }
            shown++;
            out.line(module + "  " + storesLine(integ.stores().getOrDefault(module, List.of())));
            for (Entity e : entities) {
                String kind = e.store().equals("mongodb") ? "collection" : e.store().equals("redis") ? "hash" : "table";
                StringBuilder line = new StringBuilder("  " + kind + " " + e.name()
                        + (e.explicitName() ? "" : " (default name)") + " <- " + e.type().name()
                        + "  " + out.ref(e.type().file(), e.type().decl().line()));
                out.line(line.toString());
                for (Repo r : integ.reposFor(e.type())) {
                    out.line("    repo " + r.repo().name() + "  " + out.ref(r.repo().file(), r.repo().decl().line()));
                }
                if (!kind.equals("collection")) {
                    List<SourceFile> migrations = integ.migrationsFor(e.name(), e.type());
                    if (!migrations.isEmpty()) {
                        List<String> names = new ArrayList<>();
                        migrations.forEach(m -> names.add(out.ref(m)));
                        out.line("    migrations: " + String.join(", ", names));
                    } else if (e.store().equals("jpa") || e.store().equals("jdbc")) {
                        out.line("    migrations: none found for this table");
                    }
                }
            }
            for (Repo r : orphanRepos) {
                out.line("  repo " + r.repo().name() + " for " + r.entityName() + " [" + r.store() + ", entity not indexed]  "
                        + out.ref(r.repo().file(), r.repo().decl().line()));
            }
            if (f == null) {
                for (DirectAccess d : direct) {
                    out.line("  direct " + d.via() + " in " + d.owner().name() + "  " + out.ref(d.owner().file(), d.line()));
                }
            }
        }
        if (shown == 0) {
            out.line(f == null ? "Not found: no entities, repositories or datastores in this workspace."
                    : "Not found: no entity, table or collection named like \"" + filter + "\".");
            out.line("Searched: @Entity/@Table/@Document/@RedisHash types, Spring Data repositories, datasource config.");
            return;
        }
        if (f == null) {
            Set<String> migrationOnly = new LinkedHashSet<>();
            for (SourceFile file : graph.files()) {
                for (String t : file.tables()) {
                    if (integ.entities().stream().noneMatch(e -> e.name().equals(t))) {
                        migrationOnly.add(t);
                    }
                }
            }
            if (!migrationOnly.isEmpty()) {
                out.line("Tables in migrations with no entity: " + String.join(", ", migrationOnly));
            }
        }
    }

    static String storesLine(List<Store> stores) {
        List<String> parts = new ArrayList<>();
        for (Store s : stores) {
            if (s.role().equals("datastore")) {
                parts.add(s.kind() + (s.name().isEmpty() ? "" : " db \"" + s.name() + "\""));
            }
        }
        return parts.isEmpty() ? "(no datasource in config files)" : String.join(" · ", parts);
    }
}
