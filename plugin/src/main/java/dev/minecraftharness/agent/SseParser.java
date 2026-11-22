package dev.minecraftharness.agent;

import java.util.function.BiConsumer;

/** Minimal Server-Sent Events line parser: feeds lines, emits (event, data) on blank lines. */
public final class SseParser {

    private final BiConsumer<String, String> onEvent;
    private String event = "";
    private final StringBuilder data = new StringBuilder();
    private boolean hasData;

    public SseParser(BiConsumer<String, String> onEvent) {
        this.onEvent = onEvent;
    }

    public void feedLine(String line) {
        if (line == null) return;
        if (line.isEmpty()) {
            flush();
            return;
        }
        if (line.startsWith(":")) return;
        int colon = line.indexOf(':');
        String field = colon < 0 ? line : line.substring(0, colon);
        String value = colon < 0 ? "" : line.substring(colon + 1);
        if (value.startsWith(" ")) value = value.substring(1);
        switch (field) {
            case "event" -> event = value;
            case "data" -> {
                if (hasData) data.append('\n');
                data.append(value);
                hasData = true;
            }
            default -> { }
        }
    }

    /** Drops an unterminated event at end of stream; SSE only commits an event on a blank line. */
    public void finish() {
        event = "";
        data.setLength(0);
        hasData = false;
    }

    private void flush() {
        if (hasData) onEvent.accept(event, data.toString());
        event = "";
        data.setLength(0);
        hasData = false;
    }
}
