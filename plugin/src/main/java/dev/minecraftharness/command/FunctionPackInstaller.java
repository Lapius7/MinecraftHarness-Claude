package dev.minecraftharness.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.minecraftharness.context.SpatialContextProvider.BuildAnchor;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;
/** One replaceable directory per mechanic in the primary world's datapacks directory. */
public final class FunctionPackInstaller {
    private static final Pattern ID = Pattern.compile("[a-z0-9_]{1,48}");
    private static final Pattern FUNCTION = Pattern.compile("[a-z0-9_-]+(?:/[a-z0-9_-]+)*");
    private static final String MARKER = ".minecraftharness";

    public static JsonObject apply(JsonObject request, BuildAnchor anchor, CommandWirer commands) throws IOException {
        String id = request.get("id").getAsString();
        if (!ID.matcher(id).matches()) throw new IllegalArgumentException("Invalid pack id");
        String namespace = "mh_" + id;
        Path directory = Bukkit.getWorlds().getFirst().getWorldFolder().toPath().resolve("datapacks");
        Files.createDirectories(directory);
        Path target = directory.resolve(namespace);
        boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (exists && (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
            || !Files.isRegularFile(target.resolve(MARKER), LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("Existing pack is not managed by MinecraftHarness");
        }
        String action = request.has("action") ? request.get("action").getAsString() : "deploy";
        if (!action.equals("deploy") && !action.equals("remove")) throw new IllegalArgumentException("Unknown action");
        // Stage outside datapacks so Minecraft cannot discover an incomplete or previous revision.
        Path staging = Files.createTempDirectory(directory.getParent(), ".mh-pack-");
        Path next = staging.resolve("next");
        Path previous = staging.resolve("previous");
        boolean committed = false;
        try {
            if (action.equals("deploy")) {
                Files.createDirectory(next);
                writePack(next, namespace, request.getAsJsonObject("functions"), anchor, commands);
                Files.createFile(next.resolve(MARKER));
            }
            if (exists) Files.move(target, previous, StandardCopyOption.ATOMIC_MOVE);
            if (action.equals("deploy")) Files.move(next, target, StandardCopyOption.ATOMIC_MOVE);
            committed = true;
        } finally {
            if (!committed && Files.exists(previous)) Files.move(previous, target, StandardCopyOption.ATOMIC_MOVE);
            try (var paths = Files.walk(staging)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        boolean reload = exists || action.equals("deploy");
        if (reload) Bukkit.reloadData();
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("namespace", namespace);
        result.addProperty("directory", target.getFileName().toString());
        result.addProperty("state", action.equals("deploy") ? "installed" : "removed");
        result.addProperty("reloadRequested", reload);
        return result;
    }

    private static int detectPackFormat() {
        String ver = Bukkit.getMinecraftVersion();
        if (ver.startsWith("1.21.11") || ver.startsWith("1.21.10")) return 88;
        if (ver.startsWith("1.21.9")) return 88;
        if (ver.startsWith("1.21.8") || ver.startsWith("1.21.7")) return 81;
        if (ver.startsWith("1.21.6")) return 80;
        if (ver.startsWith("1.21.5")) return 71;
        if (ver.startsWith("1.21.4")) return 61;
        if (ver.startsWith("1.21.3") || ver.startsWith("1.21.2")) return 57;
        return 48; // 1.21.0 / 1.21.1 baseline
    }

    private static void writePack(Path file, String namespace, JsonObject functions, BuildAnchor anchor,
                                  CommandWirer commands) throws IOException {
        if (functions == null || functions.isEmpty()) throw new IllegalArgumentException("Supply at least one function");
        int pf = detectPackFormat();
        String mcmeta = "{\"pack\":{\"pack_format\":" + pf
            + ",\"supported_formats\":{\"min_inclusive\":48,\"max_inclusive\":99}"
            + ",\"min_format\":48,\"max_format\":99"
            + ",\"description\":\"MinecraftHarness: " + namespace + "\"}}";
        entry(file, "pack.mcmeta", mcmeta);
        String base = "data/" + namespace + "/function/";
        for (var function : functions.entrySet()) {
            String name = function.getKey();
            if (!FUNCTION.matcher(name).matches() || name.startsWith("__mh/")) {
                throw new IllegalArgumentException("Invalid function path: " + name);
            }
            JsonArray lines = function.getValue().getAsJsonArray();
            StringBuilder body = new StringBuilder();
            int lineNumber = 0;
            for (JsonElement line : lines) {
                lineNumber++;
                String raw = line.getAsString();
                if (raw.contains("\n") || raw.contains("\r")) throw new IllegalArgumentException("Expected one command per line");
                String cmd = commands.expandMacros(raw, anchor);
                if (!cmd.isBlank() && !cmd.startsWith("#") && !cmd.startsWith("$")) {
                    String error = commands.validateCommand(cmd);
                    if (error != null) throw new IllegalArgumentException(name + ":" + lineNumber + ": " + error);
                }
                body.append(cmd).append('\n');
            }
            entry(file, base + name + ".mcfunction", body.toString());
            if (name.equals("tick") || name.equals("load")) {
                String wrapper = "__mh/" + name;
                String invocation = "execute in " + anchor.world().getKey() + " positioned "
                    + commands.expandMacros("{{REL 0 0 0}}", anchor) + " run function " + namespace + ":" + name;
                entry(file, base + wrapper + ".mcfunction", invocation + "\n");
                entry(file, "data/minecraft/tags/function/" + name + ".json",
                    "{\"values\":[\"" + namespace + ":" + wrapper + "\"]}");
            }
        }
    }

    private static void entry(Path root, String name, String contents) throws IOException {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents, StandardCharsets.UTF_8);
    }
}
