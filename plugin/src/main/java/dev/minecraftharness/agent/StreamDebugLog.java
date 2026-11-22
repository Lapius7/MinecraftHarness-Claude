package dev.minecraftharness.agent;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Appends raw claude CLI stream-json lines to a file. Off unless config {@code claude.debugLog} is true. */
public final class StreamDebugLog {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private final File file;

    public StreamDebugLog(File file) {
        this.file = file;
    }

    public synchronized void line(String tag, String text) {
        if (file == null) return;
        try {
            file.getParentFile().mkdirs();
            if (file.length() > MAX_BYTES) {
                Files.deleteIfExists(file.toPath());
            }
            Files.writeString(file.toPath(), LocalTime.now().format(TS) + " [" + tag + "] " + text + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // debugging aid only
        }
    }
}
