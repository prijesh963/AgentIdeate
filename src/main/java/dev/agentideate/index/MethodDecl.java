package dev.agentideate.index;

import java.util.List;

/**
 * A method or constructor. {@code signature} is a compact one-liner such as
 * {@code Order placeOrder(OrderRequest request)}, which is often all an agent needs.
 */
public record MethodDecl(String name, String signature, String returnType, int line, int endLine,
                         List<Ann> annotations, List<CallSite> calls) {
}
