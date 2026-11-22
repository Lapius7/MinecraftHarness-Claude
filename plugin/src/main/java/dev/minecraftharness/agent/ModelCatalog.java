package dev.minecraftharness.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Model references. A reference is {@code <backend>/<model>}:
 * {@code cli/opus} runs through the user's claude CLI (subscription),
 * {@code api/claude-opus-5-5} calls the Anthropic Messages API with an API key.
 */
public final class ModelCatalog {

    public enum Backend {
        CLI("cli"), API("api");

        private final String id;

        Backend(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    public record Model(Backend backend, String id, String label) {
        public String ref() {
            return backend.id() + "/" + id;
        }
    }

    public static final String DEFAULT_REF = "cli/opus";

    private static final List<Model> BUILTIN = List.of(
        new Model(Backend.CLI, "opus", "Claude Opus (サブスクリプション)"),
        new Model(Backend.CLI, "sonnet", "Claude Sonnet (サブスクリプション)"),
        new Model(Backend.CLI, "haiku", "Claude Haiku (サブスクリプション)"),
        new Model(Backend.API, "claude-opus-5-5", "Claude Opus 5.5 (APIキー)"),
        new Model(Backend.API, "claude-sonnet-5-5", "Claude Sonnet 5.5 (APIキー)"),
        new Model(Backend.API, "claude-haiku-5-5", "Claude Haiku 5.5 (APIキー)"),
        new Model(Backend.API, "claude-fable-5-2", "Claude Fable 5.2 (APIキー)")
    );

    private ModelCatalog() {}

    public static List<Model> all() {
        return BUILTIN;
    }

    public static List<String> refs() {
        List<String> out = new ArrayList<>();
        for (Model m : BUILTIN) out.add(m.ref());
        return out;
    }

    /**
     * Resolves user input to a model. Accepts a full ref ({@code cli/opus}), a bare id
     * ({@code sonnet}, {@code claude-opus-5-5}), a backend alone ({@code cli}, {@code api},
     * which keeps the first model of that backend), or {@code <backend>/<any-id>} for ids
     * that are not in the built-in list. Returns empty when nothing matches.
     */
    public static Optional<Model> resolve(String input) {
        if (input == null) return Optional.empty();
        String q = input.trim();
        if (q.isEmpty()) return Optional.empty();
        String lower = q.toLowerCase(Locale.ROOT);

        for (Model m : BUILTIN) {
            if (m.ref().equals(lower)) return Optional.of(m);
        }
        for (Backend b : Backend.values()) {
            if (b.id().equals(lower)) {
                return BUILTIN.stream().filter(m -> m.backend() == b).findFirst();
            }
        }
        for (Model m : BUILTIN) {
            if (m.id().equals(lower)) return Optional.of(m);
        }
        int slash = lower.indexOf('/');
        if (slash > 0) {
            String prefix = lower.substring(0, slash);
            String id = q.substring(slash + 1).trim();
            if (!id.isEmpty() && id.matches("[A-Za-z0-9._:-]+")) {
                for (Backend b : Backend.values()) {
                    if (b.id().equals(prefix)) {
                        return Optional.of(new Model(b, id, id + " (" + b.id() + ")"));
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** Maps the in-game reasoning level to the effort value accepted by the API and the CLI. */
    public static String effortFor(String level) {
        if (level == null) return "low";
        return switch (level.toLowerCase(Locale.ROOT)) {
            case "medium" -> "medium";
            case "high" -> "high";
            case "xhigh" -> "xhigh";
            case "max" -> "max";
            default -> "low";
        };
    }

    public static List<String> effortLevels() {
        return List.of("low", "medium", "high", "xhigh", "max");
    }
}
