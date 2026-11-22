package dev.minecraftharness.intent;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.minecraftharness.agent.ToolHost;
import dev.minecraftharness.review.ReviewChoice;
import dev.minecraftharness.review.ReviewLevel;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class IntentTests {

    @Test
    void explicitWinsOverModelAndDefault() {
        assertEquals(ReviewLevel.OFF, ReviewChoice.resolve(ReviewLevel.OFF, ReviewLevel.MAX, ReviewLevel.LIGHT));
        assertEquals(ReviewLevel.STRICT, ReviewChoice.resolve(null, ReviewLevel.STRICT, ReviewLevel.LIGHT));
        assertEquals(ReviewLevel.LIGHT, ReviewChoice.resolve(null, null, ReviewLevel.LIGHT));
        assertEquals(ReviewLevel.OFF, ReviewChoice.resolve(null, null, null));
        assertFalse(ReviewChoice.modelMayChoose(ReviewLevel.LIGHT));
        assertTrue(ReviewChoice.modelMayChoose(null));
    }

    @Test
    void shouldRunAfterBuildOrOnRequest() {
        assertTrue(ReviewChoice.shouldRun(ReviewLevel.LIGHT, true, false, true));
        assertFalse(ReviewChoice.shouldRun(ReviewLevel.LIGHT, false, false, true));
        assertTrue(ReviewChoice.shouldRun(ReviewLevel.STRICT, false, true, true));
        assertFalse(ReviewChoice.shouldRun(ReviewLevel.OFF, true, true, true));
        assertFalse(ReviewChoice.shouldRun(ReviewLevel.NORMAL, true, true, false));
    }

    @Test
    void toolIsAdvertisedWithEveryLevel() {
        String json = ToolHost.loadResourceText("/tools.json", "[]");
        JsonArray tools = JsonParser.parseString(json).getAsJsonArray();
        JsonObject t = null;
        for (JsonElement e : tools) if ("mc_review".equals(e.getAsJsonObject().get("name").getAsString())) t = e.getAsJsonObject();
        assertNotNull(t);
        JsonArray levels = t.getAsJsonObject("input_schema").getAsJsonObject("properties")
            .getAsJsonObject("level").getAsJsonArray("enum");
        for (String id : ReviewLevel.ids()) {
            boolean found = false;
            for (JsonElement l : levels) found |= l.getAsString().equals(id);
            assertTrue(found, id);
        }
    }

    @Test
    void promptHasIntentCriteria() {
        String p = ToolHost.loadResourceText("/agent-prompt.md", "");
        for (String k : new String[] {"Continuation", "New build", "Review request", "mc_review"}) {
            assertTrue(p.contains(k), k);
        }
    }
}
