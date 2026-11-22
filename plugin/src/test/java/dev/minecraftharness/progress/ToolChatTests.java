package dev.minecraftharness.progress;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolChatTests {

    @Test
    void sameToolInARowIsFolded() {
        ToolChat c = new ToolChat();
        assertEquals(List.of("🔧 mc_build_batch 3 操作"), c.onTool("mc_build_batch", "3 操作"));
        assertTrue(c.onTool("mc_build_batch", "2 操作").isEmpty());
        assertTrue(c.onTool("mc_build_batch", "").isEmpty());
        List<String> next = c.onTool("mc_fx", "finish");
        assertEquals(2, next.size());
        assertTrue(next.get(0).contains("さらに 2 回"));
        assertEquals("🔧 mc_fx finish", next.get(1));
        assertNull(c.flush());
    }

    @Test
    void flushReturnsPendingRunOnce() {
        ToolChat c = new ToolChat();
        c.onTool("mc_take_photo", "");
        c.onTool("mc_take_photo", "");
        assertTrue(c.flush().contains("さらに 1 回"));
        assertNull(c.flush());
    }

    @Test
    void describeUsesOpsAndShortFields() {
        var args = JsonParser.parseString("{\"ops\":[{},{}],\"anchorId\":\"last\"}").getAsJsonObject();
        assertEquals("2 操作 / last", ToolChat.describe(args));
        assertEquals("", ToolChat.describe(null));
        var long1 = JsonParser.parseString("{\"effect\":\"" + "x".repeat(60) + "\"}").getAsJsonObject();
        assertTrue(ToolChat.describe(long1).length() <= 24);
    }
}
