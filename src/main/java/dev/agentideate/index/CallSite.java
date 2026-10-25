package dev.agentideate.index;

import java.util.List;

/**
 * A method call found in a method body.
 *
 * @param scopeType raw simple name of the receiver's declared type, or {@code null} for a call on
 *                  {@code this} (an unqualified call or {@code this.method()})
 * @param method    the called method's name
 * @param line      1-based source line of the call
 * @param args      the first arguments rendered like annotation values: a literal as itself, a
 *                  constant or variable as {@code ${NAME}}, a {@code @Value}-injected field as its
 *                  property placeholder, anything else as {@code ?}. Enough to read a topic or
 *                  queue name from {@code kafkaTemplate.send(TOPIC, ...)}.
 */
public record CallSite(String scopeType, String method, int line, List<String> args) {
}
