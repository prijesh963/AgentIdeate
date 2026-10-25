package dev.agentideate.index;

/**
 * A method call found in a method body.
 *
 * @param scopeType raw simple name of the receiver's declared type, or {@code null} for a call on
 *                  {@code this} (an unqualified call or {@code this.method()})
 * @param method    the called method's name
 * @param line      1-based source line of the call
 */
public record CallSite(String scopeType, String method, int line) {
}
