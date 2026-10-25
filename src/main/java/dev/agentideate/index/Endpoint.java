package dev.agentideate.index;

/**
 * An HTTP mapping on a method. {@code path} is the method-level path only; the class-level base
 * path lives on {@link TypeDecl#basePath()}. {@code client} is true for a Feign client method,
 * which is an outbound call, not a route this service exposes.
 */
public record Endpoint(String httpMethod, String path, String handler, int line, boolean client) {
}
