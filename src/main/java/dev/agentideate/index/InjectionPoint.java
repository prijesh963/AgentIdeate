package dev.agentideate.index;

/**
 * A dependency injected into a class.
 *
 * @param via one of {@code constructor}, {@code field}, {@code lombok} (a final field filled by a
 *            Lombok-generated constructor) or {@code setter}
 */
public record InjectionPoint(String name, String type, String via, int line) {
}
