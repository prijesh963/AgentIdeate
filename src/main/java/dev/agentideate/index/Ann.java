package dev.agentideate.index;

import java.util.Map;

/**
 * An annotation as written in source: its simple name and its attributes rendered to strings.
 * A single-member annotation ({@code @GetMapping("/x")}) stores its value under {@code "value"}.
 * An array value is joined with {@code ","}. A reference to a constant is kept as {@code ${NAME}}
 * so the graph can resolve it later instead of reading it as literal text.
 */
public record Ann(String name, Map<String, String> attrs) {

    public String attr(String key) {
        return attrs.get(key);
    }

    /** The first present attribute among {@code keys}, e.g. {@code value} then {@code path}. */
    public String firstAttr(String... keys) {
        for (String key : keys) {
            String v = attrs.get(key);
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return null;
    }
}
