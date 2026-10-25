package dev.agentideate.index;

import java.util.List;

public record FieldDecl(String name, String type, int line, List<Ann> annotations,
                        boolean isStatic, boolean isFinal, boolean initialized) {
}
