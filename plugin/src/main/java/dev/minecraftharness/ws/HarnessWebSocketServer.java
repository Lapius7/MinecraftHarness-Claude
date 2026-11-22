package dev.minecraftharness.ws;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.logging.Level;

/**
 * Localhost WebSocket server (ws://127.0.0.1:25580) connecting the Node/OpenClaw Bridge
 * with PaperMC main-thread operations.
 */
public final class HarnessWebSocketServer extends WebSocketServer {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Plugin plugin;
    private final BiFunction<String, JsonObject, JsonObject> syncRequestHandler;
    private Runnable onDisconnectCallback;

    public HarnessWebSocketServer(
        Plugin plugin,
        String host,
        int port,
        BiFunction<String, JsonObject, JsonObject> syncRequestHandler
    ) {
        super(new InetSocketAddress(host, port));
        this.plugin = plugin;
        this.syncRequestHandler = syncRequestHandler;
        setReuseAddr(true);
    }

    public void setOnDisconnectCallback(Runnable callback) {
        this.onDisconnectCallback = callback;
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        plugin.getLogger().info("MinecraftHarness bridge connected from " + conn.getRemoteSocketAddress());
        JsonObject hello = new JsonObject();
        hello.addProperty("type", "event");
        hello.addProperty("event", "bridge_hello");
        JsonObject data = new JsonObject();
        data.addProperty("plugin", "MinecraftHarness");
        data.addProperty("version", plugin.getDescription().getVersion());
        hello.add("data", data);
        conn.send(GSON.toJson(hello));
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        plugin.getLogger().info("MinecraftHarness bridge disconnected (" + code + "): " + reason);
        if (onDisconnectCallback != null) {
            Bukkit.getScheduler().runTask(plugin, onDisconnectCallback);
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        JsonObject req;
        try {
            req = JsonParser.parseString(message).getAsJsonObject();
        } catch (Exception ex) {
            plugin.getLogger().warning("Invalid JSON over WS: " + ex.getMessage());
            return;
        }

        String id = req.has("id") ? req.get("id").getAsString() : null;
        String action = req.has("action") ? req.get("action").getAsString() : "";
        JsonObject params = req.has("params") && req.get("params").isJsonObject()
            ? req.getAsJsonObject("params")
            : new JsonObject();

        // Marshal Bukkit/Paper world operations cleanly onto the primary server thread
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                JsonObject res = syncRequestHandler.apply(action, params);
                future.complete(res != null ? res : new JsonObject());
            } catch (Throwable t) {
                plugin.getLogger().log(Level.SEVERE, "Error handling WS action " + action, t);
                JsonObject err = new JsonObject();
                err.addProperty("ok", false);
                err.addProperty("error", t.getClass().getSimpleName() + ": " + t.getMessage());
                future.complete(err);
            }
        });

        future.orTimeout(15, TimeUnit.SECONDS).whenComplete((result, err) -> {
            JsonObject reply = new JsonObject();
            reply.addProperty("type", "response");
            if (id != null) reply.addProperty("id", id);
            if (err != null) {
                JsonObject errPayload = new JsonObject();
                errPayload.addProperty("ok", false);
                errPayload.addProperty("error", err.getMessage());
                reply.add("result", errPayload);
            } else {
                reply.add("result", result);
            }
            if (conn.isOpen()) {
                conn.send(GSON.toJson(reply));
            }
        });
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        plugin.getLogger().log(Level.WARNING, "WebSocket server error", ex);
    }

    @Override
    public void onStart() {
        plugin.getLogger().info("MinecraftHarness WebSocket server listening on " + getAddress());
    }

    public int getConnectedClientCount() {
        return getConnections().size();
    }

    public void broadcastEvent(String eventName, JsonObject data) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "event");
        msg.addProperty("event", eventName);
        msg.add("data", data != null ? data : new JsonObject());
        String raw = GSON.toJson(msg);
        for (WebSocket conn : getConnections()) {
            if (conn.isOpen()) {
                conn.send(raw);
            }
        }
    }
}
