package dev.minecraftharness.agent;

import java.util.List;
import java.util.Locale;

/** Reply-style addendum appended to the system prompt. Chosen by config {@code agent.tone}. */
public final class TonePrompt {
    public static final String DEFAULT = "friendly";
    private static final List<String> IDS = List.of("friendly", "concise");

    private TonePrompt() {}

    public static List<String> ids() {
        return IDS;
    }

    public static String normalize(String tone) {
        String t = tone == null ? "" : tone.trim().toLowerCase(Locale.ROOT);
        return IDS.contains(t) ? t : DEFAULT;
    }

    /** The text added after the base prompt. */
    public static String addendum(String tone) {
        if ("concise".equals(normalize(tone))) {
            return "\n\n## Reply style\n"
                + "- Reply in the player's language, as briefly as possible: one or two plain sentences.\n"
                + "- After a build, say only what was built. No suggestions unless asked.\n"
                + "\n" + CONTINUITY;
        }
        return "\n\n## Reply style\n"
            + "- Reply in the player's language in a friendly, helpful tone (for Japanese: です・ます調 with a relaxed feel). Keep it short. No emoji.\n"
            + "- After a build, say briefly what you built, then offer 2-3 concrete next ideas "
            + "(e.g. add a garden, furnish the interior, lighting, change the roof shape).\n"
            + "- End with one short line inviting fixes (for Japanese: 「直したいところがあれば言ってください」).\n"
            + "\n" + CONTINUITY;
    }

    private static final String CONTINUITY =
        "## Conversation continuity\n"
        + "- Remember the structures built in this conversation (`recentStructures`: s1, s2, ... / `anchorId`).\n"
        + "- Treat follow-ups such as \"add a garden\" or \"make the roof round\" as edits to the most recent structure: "
        + "pass its `anchorId` and add or change only that part. Start a new build only when the player asks for a new building.";
}
