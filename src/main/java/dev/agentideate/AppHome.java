package dev.agentideate;

import java.nio.file.Path;

/**
 * Where agentideate keeps its own state: {@code ~/.agentideate} unless {@code AGENTIDEATE_HOME} says
 * otherwise. Nothing is ever written into the developer's project.
 */
public record AppHome(Path dir) {

    public static AppHome resolve() {
        String env = System.getenv("AGENTIDEATE_HOME");
        if (env != null && !env.isBlank()) {
            return new AppHome(Path.of(env).toAbsolutePath());
        }
        return new AppHome(Path.of(System.getProperty("user.home"), ".agentideate"));
    }

    public Path workspaceDir(String workspaceId) {
        return dir.resolve("workspaces").resolve(workspaceId);
    }
}
